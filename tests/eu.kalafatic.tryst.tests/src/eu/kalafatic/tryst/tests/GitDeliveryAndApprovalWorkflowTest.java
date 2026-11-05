package eu.kalafatic.tryst.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.GitCommitRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.GitCommitRecord;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitCommitRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

public class GitDeliveryAndApprovalWorkflowTest {

    private Path tempDir;
    private Path repoDir;
    private Path remoteDir;
    private GitService git;
    private SqliteDatabaseManager dbManager;
    private GitCommitRepository commitRepository;
    private SqliteProjectRepository projectRepo;
    private SqliteTaskRepository taskRepo;
    private SqliteCodeReviewRepository reviewRepo;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tryst-git-delivery-test-");
        repoDir = tempDir.resolve("local-repo");
        remoteDir = tempDir.resolve("remote-repo");
        Files.createDirectories(repoDir);
        Files.createDirectories(remoteDir);

        git = new GitService();

        // Initialize bare remote repo with initial branch main
        ProcessBuilder pbRemote = new ProcessBuilder("git", "init", "--bare", "-b", "main", remoteDir.toString());
        assertEquals(0, pbRemote.start().waitFor());

        // Initialize local repo with branch main
        ProcessBuilder pbLocal = new ProcessBuilder("git", "init", "-b", "main", repoDir.toString());
        assertEquals(0, pbLocal.start().waitFor());

        // Config git user in local repo
        new ProcessBuilder("git", "-C", repoDir.toString(), "config", "user.name", "Tryst Test").start().waitFor();
        new ProcessBuilder("git", "-C", repoDir.toString(), "config", "user.email", "test@tryst.local").start().waitFor();

        // Create initial commit
        Path readme = repoDir.resolve("README.md");
        Files.writeString(readme, "# Tryst Project\n");
        new ProcessBuilder("git", "-C", repoDir.toString(), "add", "README.md").start().waitFor();
        new ProcessBuilder("git", "-C", repoDir.toString(), "commit", "-m", "Initial commit").start().waitFor();

        // Link remote origin and push main branch
        new ProcessBuilder("git", "-C", repoDir.toString(), "remote", "add", "origin", remoteDir.toString()).start().waitFor();
        new ProcessBuilder("git", "-C", repoDir.toString(), "push", "-u", "origin", "main").start().waitFor();

        // Initialize SQLite db
        Path dbPath = tempDir.resolve("test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();
        commitRepository = new SqliteGitCommitRepository(dbManager);
        projectRepo = new SqliteProjectRepository(dbManager);
        taskRepo = new SqliteTaskRepository(dbManager);
        reviewRepo = new SqliteCodeReviewRepository(dbManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbManager != null) {
            dbManager.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testAiAgentCannotApproveOrCommitChanges() {
        CodeReview review = new CodeReview("proj-1", "task-1", "main", "diff", "SUCCESS", "Summary");

        // AI agents must not be permitted to approve reviews
        assertThrows(TrystDomainException.class, () -> review.approve("agent:code-agent"));
        assertThrows(TrystDomainException.class, () -> review.approve("task-worker-ai"));
        assertThrows(TrystDomainException.class, () -> review.approve("bot-reviewer"));

        // User can approve
        review.approve("human-user");
        assertTrue(review.isApproved());
        assertEquals("human-user", review.getApprovedBy());

        // AI agents must not be permitted to commit
        GitService.AuthorizedCommitResult aiCommit = git.commitAuthorizedReview(repoDir, review, "agent:code-agent", "Auto commit");
        assertFalse(aiCommit.success());
        assertTrue(aiCommit.error().contains("AI agents are strictly forbidden"));
    }

    @Test
    void testApprovalRequiresExactRevision() {
        CodeReview review = new CodeReview("proj-1", "task-1", "main", "diff", "SUCCESS", "Summary");
        assertEquals(1, review.getRevisionCount());

        // Increment revision count to simulate rework
        review.incrementRevisionCount();
        assertEquals(2, review.getRevisionCount());

        // Stale revision approval must fail
        assertThrows(TrystDomainException.class, () -> review.approve("human-user", 1));

        // Current revision approval succeeds
        review.approve("human-user", 2);
        assertTrue(review.isApproved());
        assertEquals(2, review.getApprovedRevision());
    }

    @Test
    void testUnresolvedBlockingCommentsPreventApproval() {
        CodeReview review = new CodeReview("proj-1", "task-1", "main", "diff", "SUCCESS", "Summary");
        ReviewComment blockingComment = new ReviewComment("c-1", review.getId(), "reviewer", "Security flaw detected!", true);

        assertThrows(TrystDomainException.class, () -> review.approve("human-user", 1, List.of(blockingComment)));

        // Resolve comment
        blockingComment.setResolved(true);
        review.approve("human-user", 1, List.of(blockingComment));
        assertTrue(review.isApproved());
    }

    @Test
    void testConcurrentModificationInvalidatesApproval() throws Exception {
        Path readmeFile = repoDir.resolve("README.md");
        Files.writeString(readmeFile, "# Tryst Project\nInitial change\n");

        GitService.GitCommandResult diffResult = git.getDiff(repoDir);
        CodeReview review = new CodeReview("proj-1", "task-1", "main", diffResult.output(), "SUCCESS", "Summary");
        review.approve("human-user");

        // Simulate concurrent working tree modification
        Files.writeString(readmeFile, "# Tryst Project\nUnauthorized modification after approval\n");

        GitService.AuthorizedCommitResult commitResult = git.commitAuthorizedReview(repoDir, review, "human-user", "Commit README change");
        assertFalse(commitResult.success(), "Commit must be rejected if working tree was modified after approval");
        assertTrue(commitResult.error().contains("does not match approved review fingerprint"));
        assertFalse(review.isApproved(), "Approval must be invalidated on concurrent modification");
    }

    @Test
    void testSuccessfulCommitFlowAndIdempotency() throws Exception {
        Project project = new Project("proj-1", "human-user", "Test Project", repoDir, true);
        projectRepo.save(project);

        AgentTask task = new AgentTask("task-1", "proj-1", null, "Title", "Desc", TaskStatus.RUNNING, null, null, null, null, Instant.now(), null);
        taskRepo.save(task);

        Path readmeFile = repoDir.resolve("README.md");
        Files.writeString(readmeFile, "# Tryst Project\nAuthorized feature update\n");

        GitService.GitCommandResult diffResult = git.getDiff(repoDir);
        CodeReview review = new CodeReview("proj-1", "task-1", "main", diffResult.output(), "SUCCESS", "Summary");
        reviewRepo.save(review);
        review.approve("human-user");

        // Commit authorized review
        GitService.AuthorizedCommitResult commitResult = git.commitAuthorizedReview(repoDir, review, "human-user", "Update README.md");
        assertTrue(commitResult.success(), "Commit must succeed: " + commitResult.error());
        assertNotNull(commitResult.commitHash());
        assertEquals(CodeReview.ReviewStatus.COMMITTED, review.getStatus());

        // Persist GitCommitRecord
        GitCommitRecord record = new GitCommitRecord(
                commitResult.commitHash(),
                review.getId(),
                review.getTaskId(),
                "proj-1",
                1,
                "human-user",
                "Update README.md",
                review.getContentFingerprint(),
                "main",
                1
        );
        commitRepository.save(record);

        List<GitCommitRecord> forReview = commitRepository.findByReviewId(review.getId());
        assertEquals(1, forReview.size());
        assertEquals(commitResult.commitHash(), forReview.get(0).getCommitHash());

        // Duplicate commit attempt must be safely recognized as idempotent
        GitService.AuthorizedCommitResult retryCommit = git.commitAuthorizedReview(repoDir, review, "human-user", "Update README.md");
        assertTrue(retryCommit.success(), "Idempotent commit retry must succeed");
        assertEquals(commitResult.commitHash(), retryCommit.commitHash());
    }

    @Test
    void testPushRequiresExplicitAuthorizationAndDetectsRemoteConflict() throws Exception {
        // Prepare local commit
        Path readmeFile = repoDir.resolve("README.md");
        Files.writeString(readmeFile, "# Tryst Project\nLocal feature\n");
        GitService.GitCommandResult diffResult = git.getDiff(repoDir);
        CodeReview review = new CodeReview("proj-1", "task-1", "main", diffResult.output(), "SUCCESS", "Summary");
        review.approve("human-user");

        GitService.AuthorizedCommitResult commitResult = git.commitAuthorizedReview(repoDir, review, "human-user", "Add local feature");
        assertTrue(commitResult.success());

        // Clone a second repo to simulate another developer pushing conflicting changes to remote
        Path otherRepoDir = tempDir.resolve("other-repo");
        ProcessBuilder pbClone = new ProcessBuilder("git", "clone", remoteDir.toString(), otherRepoDir.toString());
        assertEquals(0, pbClone.start().waitFor());
        new ProcessBuilder("git", "-C", otherRepoDir.toString(), "config", "user.name", "Other Dev").start().waitFor();
        new ProcessBuilder("git", "-C", otherRepoDir.toString(), "config", "user.email", "other@tryst.local").start().waitFor();

        // Other dev pushes conflicting README.md to remote
        Files.writeString(otherRepoDir.resolve("README.md"), "# Tryst Project\nRemote conflicting change\n");
        new ProcessBuilder("git", "-C", otherRepoDir.toString(), "add", "README.md").start().waitFor();
        new ProcessBuilder("git", "-C", otherRepoDir.toString(), "commit", "-m", "Remote conflicting change").start().waitFor();
        new ProcessBuilder("git", "-C", otherRepoDir.toString(), "push", "origin", "main").start().waitFor();

        // Check remote conflicts in local repo
        assertTrue(git.hasRemoteConflicts(repoDir, "origin", "main"), "GitService must detect remote conflict");
        String details = git.getRemoteConflictDetails(repoDir, "origin", "main");
        assertNotNull(details);
        assertTrue(details.contains("diverged") || details.contains("behind"));
    }
}
