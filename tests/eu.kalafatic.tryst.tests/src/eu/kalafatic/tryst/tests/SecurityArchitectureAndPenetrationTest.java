package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.CsrfTokenManager;
import eu.kalafatic.tryst.domain.security.PermissionDecision;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.security.RateLimiter;
import eu.kalafatic.tryst.domain.security.SecurityContext;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAuditEventRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive authorization and penetration-oriented test suite for TRYST security architecture.
 * Validates explicit ALLOW/DENY precedence, cross-user isolation, symlink/traversal defenses,
 * rate limiting, CSRF tokens, HTTP security headers, and spoofing prevention.
 */
class SecurityArchitectureAndPenetrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteUserRepository userRepository;
    private SqliteUserSessionRepository sessionRepository;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private SqliteAuditEventRepository auditEventRepository;

    private PermissionManager permissionManager;
    private AuthenticationService authService;
    private WorkspaceService workspaceService;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private String baseUrl;

    private String savedDevMode;

    @BeforeEach
    void setUp() throws IOException {
        savedDevMode = System.getProperty("tryst.dev.mode");
        System.setProperty("tryst.dev.mode", "false");

        Path dbPath = tempDir.resolve("sec_arch_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        auditEventRepository = new SqliteAuditEventRepository(databaseManager);
        permissionManager = new PermissionManager(auditEventRepository);

        userRepository = new SqliteUserRepository(databaseManager);
        sessionRepository = new SqliteUserSessionRepository(databaseManager);
        authService = new AuthenticationService(userRepository, sessionRepository, permissionManager);

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);

        workspaceService = new WorkspaceService(
                tempDir.resolve("workspaces"),
                workspaceRepository,
                projectRepository,
                taskRepository,
                permissionManager
        );

        authService.bootstrapAdmin("admin", "AdminPass#2026");

        httpServer = new TrystHttpServer("127.0.0.1", 0, workspaceService, projectRepository, taskRepository,
                null, null, null, permissionManager);
        httpServer.setPhase4Dependencies(authService, userRepository, sessionRepository, auditEventRepository,
                null, null, null, null, null, null, null, null, null, databaseManager, null, null);
        httpServer.start();

        baseUrl = "http://127.0.0.1:" + httpServer.getPort() + "/tryst";
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (savedDevMode != null) {
            System.setProperty("tryst.dev.mode", savedDevMode);
        } else {
            System.clearProperty("tryst.dev.mode");
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testExplicitDenyPrecedence() {
        assertEquals(PermissionDecision.DENY, PermissionDecision.combine(PermissionDecision.ALLOW, PermissionDecision.DENY));
        assertEquals(PermissionDecision.DENY, PermissionDecision.combine(PermissionDecision.DENY, PermissionDecision.ALLOW));
        assertEquals(PermissionDecision.DENY, PermissionDecision.combine(PermissionDecision.ALLOW, null));
        assertEquals(PermissionDecision.DENY, PermissionDecision.combine());
        assertEquals(PermissionDecision.ALLOW, PermissionDecision.combine(PermissionDecision.ALLOW, PermissionDecision.ALLOW));

        PermissionDecision.DecisionResult res = permissionManager.decide(PermissionDecision.ALLOW, PermissionDecision.DENY)
                .isAllowed() ? PermissionDecision.DecisionResult.allow("Ok") : PermissionDecision.DecisionResult.deny("Denied by policy");
        assertTrue(res.isDenied());
        assertEquals("Denied by policy", res.reason());
    }

    @Test
    void testCrossUserProjectAccessDenied() {
        User alice = new User("alice", "h1", "s1", UserRole.PROJECT_OWNER);
        User bob = new User("bob", "h2", "s2", UserRole.DEVELOPER);
        userRepository.save(alice);
        userRepository.save(bob);

        Project aliceProj = new Project("proj-alice", alice.getId(), "Alice Project", tempDir.resolve("alice"), true);
        projectRepository.save(aliceProj);

        SecurityContext bobCtx = new SecurityContext(bob);

        TrystDomainException ex = assertThrows(TrystDomainException.class, () ->
                permissionManager.validateUserProjectAccess(bob.getId(), aliceProj, bobCtx));
        assertTrue(ex.getMessage().contains("not authorized"));

        assertDoesNotThrow(() ->
                permissionManager.validateUserProjectAccess(alice.getId(), aliceProj, new SecurityContext(alice)));

        User admin = userRepository.findByUsername("admin").orElseThrow();
        assertDoesNotThrow(() ->
                permissionManager.validateUserProjectAccess(admin.getId(), aliceProj, new SecurityContext(admin)));
    }

    @Test
    void testCrossUserWorkspaceAccessDenied() {
        User alice = new User("alice_ws", "h1", "s1", UserRole.DEVELOPER);
        User bob = new User("bob_ws", "h2", "s2", UserRole.DEVELOPER);
        userRepository.save(alice);
        userRepository.save(bob);

        Workspace aliceWs = new Workspace("ws-alice", tempDir.resolve("alice_ws"), alice.getId(), "t-1",
                "https://github.com/alice/repo.git", "main", WorkspaceStatus.ACTIVE, Instant.now(), Instant.now(), Map.of());
        workspaceRepository.save(aliceWs);

        SecurityContext bobCtx = new SecurityContext(bob);
        assertThrows(TrystDomainException.class, () ->
                permissionManager.validateWorkspaceAccess(bob.getId(), aliceWs, bobCtx));

        SecurityContext aliceCtx = new SecurityContext(alice);
        assertDoesNotThrow(() ->
                permissionManager.validateWorkspaceAccess(alice.getId(), aliceWs, aliceCtx));
    }

    @Test
    void testCrossUserTaskManipulationBlocked() {
        User alice = new User("alice_t", "h1", "s1", UserRole.DEVELOPER);
        User reviewer = new User("rev_t", "h2", "s2", UserRole.REVIEWER);
        userRepository.save(alice);
        userRepository.save(reviewer);

        Project testProj = new Project("p-1", alice.getId(), "Project 1", tempDir.resolve("proj1"), true);
        projectRepository.save(testProj);

        Task task = new Task("t-123", testProj.getId(), null, "Secret Task", "Desc", TaskStatus.CREATED,
                TaskPriority.HIGH, null, null, null, null, 0, null, Instant.now(), null);
        taskRepository.saveTask(task);

        SecurityContext reviewerCtx = new SecurityContext(reviewer);

        assertThrows(TrystDomainException.class, () ->
                permissionManager.validateTaskAccess(reviewer.getId(), task, reviewerCtx, Permission.CANCEL_TASKS));

        SecurityContext aliceCtx = new SecurityContext(alice);
        assertDoesNotThrow(() ->
                permissionManager.validateTaskAccess(alice.getId(), task, aliceCtx, Permission.CANCEL_TASKS));
    }

    @Test
    void testPathTraversalAndSymlinkEscapes() throws IOException {
        Path wsRoot = tempDir.resolve("sandbox_root");
        Files.createDirectories(wsRoot);
        Workspace ws = new Workspace(wsRoot);

        Path outside = wsRoot.resolve("../../etc/passwd");
        assertThrows(TrystDomainException.class, () ->
                permissionManager.validatePathWithinWorkspace(outside, ws));

        assertThrows(Exception.class, () -> {
            Path nullByteTarget = Path.of(wsRoot.toString(), "file.txt\0.secret");
            permissionManager.validatePathWithinWorkspace(nullByteTarget, ws);
        });

        Path secretDir = tempDir.resolve("system_secrets");
        Files.createDirectories(secretDir);
        Path secretFile = secretDir.resolve("confidential.txt");
        Files.writeString(secretFile, "TOP_SECRET");

        Path evilSymlink = wsRoot.resolve("evil_link");
        try {
            Files.createSymbolicLink(evilSymlink, secretDir);
            Path targetThroughLink = evilSymlink.resolve("confidential.txt");
            assertThrows(TrystDomainException.class, () ->
                    permissionManager.validatePathWithinWorkspace(targetThroughLink, ws));
        } catch (UnsupportedOperationException ignored) {
        }

        Path goodFile = wsRoot.resolve("src").resolve("App.java");
        Files.createDirectories(goodFile.getParent());
        Files.writeString(goodFile, "class App {}");
        assertDoesNotThrow(() -> permissionManager.validatePathWithinWorkspace(goodFile, ws));
    }

    @Test
    void testRateLimiterSlidingWindow() {
        RateLimiter limiter = new RateLimiter(3, 5);

        assertTrue(limiter.allowRequest("ip-1"));
        assertTrue(limiter.allowRequest("ip-1"));
        assertTrue(limiter.allowRequest("ip-1"));

        assertFalse(limiter.allowRequest("ip-1"));

        assertTrue(limiter.allowRequest("ip-2"));

        limiter.reset("ip-1");
        assertTrue(limiter.allowRequest("ip-1"));
    }

    @Test
    void testCsrfProtection() {
        CsrfTokenManager csrfManager = new CsrfTokenManager();
        String sessionId = "sess-12345";

        String token = csrfManager.getOrCreateToken(sessionId);
        assertNotNull(token);
        assertEquals(64, token.length());

        assertTrue(csrfManager.validateToken(sessionId, token));
        assertFalse(csrfManager.validateToken(sessionId, "invalid-token"));

        csrfManager.invalidateToken(sessionId);
        assertFalse(csrfManager.validateToken(sessionId, token));
    }

    @Test
    void testHttpSecurityHeadersPresentInResponses() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/status"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", resp.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(resp.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals("strict-origin-when-cross-origin", resp.headers().firstValue("Referrer-Policy").orElse(null));
        assertTrue(resp.headers().firstValue("Permissions-Policy").isPresent());
    }

    @Test
    void testClientHeaderSpoofingRejectedWhenAdminExists() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/overview"))
                .header("X-User-Id", "admin")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("Access denied"));
    }
}
