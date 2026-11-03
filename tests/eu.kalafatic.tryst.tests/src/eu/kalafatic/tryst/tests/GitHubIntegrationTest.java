package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.github.*;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.infrastructure.github.DefaultGitHubCredentialEncryptionService;
import eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitHubCredentialRepository;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GitHubIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteGitHubCredentialRepository credentialRepository;
    private DefaultGitHubCredentialEncryptionService encryptionService;
    private GitService gitService;
    private HttpServer mockGitHubServer;
    private int mockPort;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test_github.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        // Seed users into users table for foreign key constraint
        var userRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository(databaseManager);
        userRepo.save(new eu.kalafatic.tryst.domain.model.User("user-1", "user-1", "hash", "salt", eu.kalafatic.tryst.domain.model.UserRole.DEVELOPER, true, false, 0, java.time.Instant.now(), java.time.Instant.now()));
        userRepo.save(new eu.kalafatic.tryst.domain.model.User("user-alice", "alice", "hash", "salt", eu.kalafatic.tryst.domain.model.UserRole.DEVELOPER, true, false, 0, java.time.Instant.now(), java.time.Instant.now()));

        credentialRepository = new SqliteGitHubCredentialRepository(databaseManager);
        encryptionService = new DefaultGitHubCredentialEncryptionService("test-secret-salt-key-12345");
        gitService = new GitService();

        // Start mock GitHub API server
        mockGitHubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockGitHubServer.getAddress().getPort();
        setupMockGitHubEndpoints();
        mockGitHubServer.start();
    }

    @AfterEach
    void tearDown() {
        if (mockGitHubServer != null) {
            mockGitHubServer.stop(0);
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private void setupMockGitHubEndpoints() {
        // Mock /user
        mockGitHubServer.createContext("/user", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer valid_pat_")) {
                byte[] err = "{\"message\": \"Bad credentials\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            String json = """
                {
                  "login": "octocat",
                  "name": "Mona Lisa Octocat",
                  "email": "octocat@github.com",
                  "avatar_url": "https://github.com/images/error/octocat_happy.gif"
                }
                """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Mock /user/repos
        mockGitHubServer.createContext("/user/repos", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer valid_pat_")) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            String json = """
                [
                  {
                    "id": 1296269,
                    "name": "Hello-World",
                    "full_name": "octocat/Hello-World",
                    "private": false,
                    "default_branch": "main",
                    "clone_url": "https://github.com/octocat/Hello-World.git",
                    "html_url": "https://github.com/octocat/Hello-World",
                    "description": "This is your first repo!"
                  },
                  {
                    "id": 9876543,
                    "name": "Private-Project",
                    "full_name": "octocat/Private-Project",
                    "private": true,
                    "default_branch": "dev",
                    "clone_url": "https://github.com/octocat/Private-Project.git",
                    "html_url": "https://github.com/octocat/Private-Project",
                    "description": "Top secret private repository"
                  }
                ]
                """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Mock /repos/{owner}/{repo}/branches
        mockGitHubServer.createContext("/repos/octocat/Private-Project/branches", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer valid_pat_")) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            String json = """
                [
                  {
                    "name": "main",
                    "commit": { "sha": "6dcb09b5b57875f334f61aebed695e2e4193db5e" },
                    "protected": true
                  },
                  {
                    "name": "feature-ai",
                    "commit": { "sha": "7fd1a60b01f91b314f59955a4e4d4e80d8edf11d" },
                    "protected": false
                  }
                ]
                """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
    }

    @Test
    void testEncryptionAndMasking() {
        String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";
        String encrypted = encryptionService.encrypt(token);

        assertNotNull(encrypted);
        assertNotEquals(token, encrypted);
        assertFalse(encrypted.contains("ghp_"));

        String decrypted = encryptionService.decrypt(encrypted);
        assertEquals(token, decrypted);

        String masked = encryptionService.maskToken(token);
        assertEquals("ghp_****wxyz", masked);
        assertFalse(masked.contains("1234567890"));
    }

    @Test
    void testGitHubAccountConnectionAndRevocation() {
        String mockApi = "http://127.0.0.1:" + mockPort;
        DefaultGitHubService github = new DefaultGitHubService(
                mockApi, credentialRepository, encryptionService, gitService, Duration.ofSeconds(5)
        );

        // 1. Connection with invalid token fails
        assertThrows(TrystDomainException.class, () -> github.connectAccount("user-1", "invalid_token"));

        // 2. Connection with valid token succeeds
        GitHubCredential cred = github.connectAccount("user-1", "valid_pat_secret12345678");
        assertNotNull(cred);
        assertEquals("user-1", cred.getUserId());
        assertEquals("octocat", cred.getGithubUsername());

        // 3. Credential is encrypted in database
        Optional<GitHubCredential> persistent = credentialRepository.findByUserId("user-1");
        assertTrue(persistent.isPresent());
        assertNotEquals("valid_pat_secret12345678", persistent.get().getEncryptedToken());

        // 4. Disconnect revokes and removes stored token
        github.disconnectAccount("user-1");
        assertTrue(credentialRepository.findByUserId("user-1").isEmpty());
    }

    @Test
    void testRepositoryAndBranchDiscovery() {
        String mockApi = "http://127.0.0.1:" + mockPort;
        DefaultGitHubService github = new DefaultGitHubService(
                mockApi, credentialRepository, encryptionService, gitService, Duration.ofSeconds(5)
        );

        github.connectAccount("user-alice", "valid_pat_user_alice");

        // Discover repositories (both public and private)
        List<GitHubRepository> repos = github.listRepositories("user-alice");
        assertEquals(2, repos.size());

        GitHubRepository pubRepo = repos.stream().filter(r -> !r.isPrivate()).findFirst().orElseThrow();
        assertEquals("octocat/Hello-World", pubRepo.fullName());
        assertEquals("main", pubRepo.defaultBranch());

        GitHubRepository privRepo = repos.stream().filter(GitHubRepository::isPrivate).findFirst().orElseThrow();
        assertEquals("octocat/Private-Project", privRepo.fullName());
        assertTrue(privRepo.isPrivate());

        // Discover branches
        List<GitHubBranch> branches = github.listBranches("user-alice", "octocat", "Private-Project");
        assertEquals(2, branches.size());
        assertTrue(branches.stream().anyMatch(b -> b.name().equals("main") && b.isProtected()));
        assertTrue(branches.stream().anyMatch(b -> b.name().equals("feature-ai") && !b.isProtected()));
    }

    @Test
    void testGitOperationsInSandbox() throws Exception {
        // Create an origin repository to act as remote
        Path originDir = tempDir.resolve("origin_remote.git");
        GitService.GitCommandResult initOrigin = gitService.runGit(tempDir, List.of("init", "--bare", originDir.getFileName().toString()));
        assertTrue(initOrigin.isSuccess());

        // Create seed repo, push initial commit to origin
        Path seedDir = tempDir.resolve("seed");
        Files.createDirectories(seedDir);
        gitService.runGit(seedDir, List.of("init"));
        gitService.runGit(seedDir, List.of("config", "user.email", "test@tryst.ai"));
        gitService.runGit(seedDir, List.of("config", "user.name", "TRYST Builder"));
        Files.writeString(seedDir.resolve("README.md"), "# Seed Project");
        gitService.runGit(seedDir, List.of("add", "."));
        gitService.runGit(seedDir, List.of("commit", "-m", "Initial commit"));
        gitService.runGit(seedDir, List.of("branch", "-M", "main"));
        gitService.runGit(seedDir, List.of("remote", "add", "origin", originDir.toUri().toString()));
        gitService.runGit(seedDir, List.of("push", "-u", "origin", "main"));

        // Test 1: Clone into isolated task sandbox
        Path sandboxDir = tempDir.resolve("sandboxes").resolve("user-1").resolve("task-100");
        GitService.GitCommandResult cloneRes = gitService.cloneOrFetch(sandboxDir, originDir.toUri().toString(), "main", null);
        assertTrue(cloneRes.isSuccess());
        assertTrue(gitService.isGitRepository(sandboxDir));
        assertEquals("# Seed Project", Files.readString(sandboxDir.resolve("README.md")));

        // Test 2: Branch creation & switching
        GitService.GitCommandResult branchRes = gitService.createBranch(sandboxDir, "feature/tryst-task-100");
        assertTrue(branchRes.isSuccess());

        // Test 3: Local modification and diff generation
        Files.writeString(sandboxDir.resolve("README.md"), "# Seed Project\nAutonomous Task Modification");
        GitService.GitCommandResult diffRes = gitService.getDiff(sandboxDir);
        assertTrue(diffRes.isSuccess());
        assertTrue(diffRes.output().contains("+Autonomous Task Modification"));

        // Test 4: Commit preparation & review gating
        CodeReview review = new CodeReview("proj-1", "task-100", "feature/tryst-task-100", diffRes.output(), "SUCCESS", "Build OK");

        // Attempt commit without approval -> MUST FAIL
        GitService.AuthorizedCommitResult unapprovedCommit = gitService.commitAuthorizedReview(sandboxDir, review, "user-1", "Unapproved");
        assertFalse(unapprovedCommit.success());

        // Approve review and commit
        review.approve("user-1");
        GitService.AuthorizedCommitResult authorizedCommit = gitService.commitAuthorizedReview(sandboxDir, review, "user-1", "Task 100 Finished");
        assertTrue(authorizedCommit.success());
        assertNotNull(authorizedCommit.commitHash());

        // Test 5: Explicit authorized push
        GitService.GitCommandResult pushRes = gitService.push(sandboxDir, "origin", "feature/tryst-task-100", null, false);
        assertTrue(pushRes.isSuccess());

        // Verify remote has the new branch
        GitService.GitCommandResult branchCheck = gitService.runGit(originDir, List.of("branch", "--list"));
        assertTrue(branchCheck.output().contains("feature/tryst-task-100"));
    }

    @Test
    void testInterruptedCloneRecovery() throws Exception {
        Path sandboxDir = tempDir.resolve("sandboxes").resolve("interrupted");
        Files.createDirectories(sandboxDir);
        Files.writeString(sandboxDir.resolve("partial_file.tmp"), "corrupted clone content");

        // Create a valid bare repository
        Path originDir = tempDir.resolve("recovery_origin.git");
        gitService.runGit(tempDir, List.of("init", "--bare", originDir.getFileName().toString()));

        Path seedDir = tempDir.resolve("recovery_seed");
        Files.createDirectories(seedDir);
        gitService.runGit(seedDir, List.of("init"));
        gitService.runGit(seedDir, List.of("config", "user.email", "recovery@tryst.ai"));
        gitService.runGit(seedDir, List.of("config", "user.name", "Recovery"));
        Files.writeString(seedDir.resolve("data.txt"), "hello recovery");
        gitService.runGit(seedDir, List.of("add", "."));
        gitService.runGit(seedDir, List.of("commit", "-m", "init"));
        gitService.runGit(seedDir, List.of("branch", "-M", "main"));
        gitService.runGit(seedDir, List.of("remote", "add", "origin", originDir.toUri().toString()));
        gitService.runGit(seedDir, List.of("push", "-u", "origin", "main"));

        // Running cloneOrFetch over dirty/interrupted directory safely cleans and succeeds
        GitService.GitCommandResult res = gitService.cloneOrFetch(sandboxDir, originDir.toUri().toString(), "main", null);
        assertTrue(res.isSuccess());
        assertTrue(gitService.isGitRepository(sandboxDir));
        assertEquals("hello recovery", Files.readString(sandboxDir.resolve("data.txt")).trim());
    }

    @Test
    void testRemoteConflictsDetection() throws Exception {
        // Create origin
        Path originDir = tempDir.resolve("conflicts_origin.git");
        gitService.runGit(tempDir, List.of("init", "--bare", originDir.getFileName().toString()));

        // Create seed
        Path seedDir = tempDir.resolve("conflicts_seed");
        Files.createDirectories(seedDir);
        gitService.runGit(seedDir, List.of("init"));
        gitService.runGit(seedDir, List.of("config", "user.email", "test@tryst.ai"));
        gitService.runGit(seedDir, List.of("config", "user.name", "Tester"));
        Files.writeString(seedDir.resolve("file.txt"), "line 1\n");
        gitService.runGit(seedDir, List.of("add", "."));
        gitService.runGit(seedDir, List.of("commit", "-m", "init"));
        gitService.runGit(seedDir, List.of("branch", "-M", "main"));
        gitService.runGit(seedDir, List.of("remote", "add", "origin", originDir.toUri().toString()));
        gitService.runGit(seedDir, List.of("push", "-u", "origin", "main"));

        // Clone to local repo A
        Path repoA = tempDir.resolve("repoA");
        gitService.cloneOrFetch(repoA, originDir.toUri().toString(), "main", null);

        // Advance origin by pushing from seed
        Files.writeString(seedDir.resolve("file.txt"), "line 1\nremote update\n");
        gitService.runGit(seedDir, List.of("commit", "-am", "remote update"));
        gitService.runGit(seedDir, List.of("push", "origin", "main"));

        // Detect remote changes in repoA
        boolean hasConflicts = gitService.hasRemoteConflicts(repoA, "origin", "main", null);
        assertTrue(hasConflicts, "Should detect remote changes that are ahead of local HEAD");
    }
}
