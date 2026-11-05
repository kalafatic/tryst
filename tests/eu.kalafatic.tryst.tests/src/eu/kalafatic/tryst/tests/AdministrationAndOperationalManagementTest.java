package eu.kalafatic.tryst.tests;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.CorrelationContext;
import eu.kalafatic.tryst.domain.model.ManagedProcessRecord;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.domain.repository.AuditEventRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TelemetryRepository;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.DataRetentionService;
import eu.kalafatic.tryst.domain.service.LogDiagnosticService;
import eu.kalafatic.tryst.domain.service.ManagedServiceController;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.TelemetryService;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAuditEventRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTelemetryRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;

public class AdministrationAndOperationalManagementTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager dbManager;
    private AuthenticationService authService;
    private PermissionManager permissionManager;
    private WorkspaceService workspaceService;
    private ProcessExecutionManager processExecutionManager;
    private TelemetryService telemetryService;
    private DataRetentionService dataRetentionService;
    private TaskOrchestrator taskOrchestrator;
    private ManagedServiceController managedServiceController;
    private LogDiagnosticService logService;
    private TaskRepository taskRepository;
    private UserRepository userRepository;
    private TrystHttpServer httpServer;
    private String adminToken;
    private String devToken;
    private int boundPort;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("admin-test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();

        userRepository = new SqliteUserRepository(dbManager);
        UserSessionRepository sessionRepo = new SqliteUserSessionRepository(dbManager);
        AuditEventRepository auditRepo = new SqliteAuditEventRepository(dbManager);
        taskRepository = new SqliteTaskRepository(dbManager);
        ProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
        WorkspaceRepository workspaceRepo = new SqliteWorkspaceRepository(dbManager, projectRepo, taskRepository);
        TelemetryRepository telemetryRepo = new SqliteTelemetryRepository(dbManager);
        SqliteCodeReviewRepository reviewRepo = new SqliteCodeReviewRepository(dbManager);

        permissionManager = new PermissionManager();
        authService = new AuthenticationService(userRepository, sessionRepo, permissionManager);
        logService = new LogDiagnosticService();

        User adminUser = authService.bootstrapAdmin("admin", "AdminPass123!");
        AuthenticationService.AuthResult adminAuth = authService.login("admin", "AdminPass123!", "127.0.0.1", "Admin-Agent");
        adminToken = adminAuth.token();

        User devUser = new User("developer", eu.kalafatic.tryst.domain.security.PasswordHasher.hashPassword("DevPass123!".toCharArray(), "devsalt"), "devsalt", UserRole.DEVELOPER);
        userRepository.save(devUser);
        AuthenticationService.AuthResult devAuth = authService.login("developer", "DevPass123!", "127.0.0.1", "Dev-Agent");
        devToken = devAuth.token();

        workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), workspaceRepo, projectRepo, taskRepository, permissionManager);
        processExecutionManager = new ProcessExecutionManager();
        ServerMonitoringService monitoringService = new ServerMonitoringService(tempDir.resolve("workspaces"));
        telemetryService = new TelemetryService(telemetryRepo, monitoringService, sessionRepo, taskRepository);
        dataRetentionService = new DataRetentionService(new eu.kalafatic.tryst.infrastructure.persistence.SqliteRetentionStore(dbManager), dbManager);

        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        taskOrchestrator = new TaskOrchestrator(
                taskQueue,
                taskRepository,
                lifecycleManager,
                workspaceService,
                projectRepo,
                null,
                permissionManager,
                null,
                null,
                null,
                null,
                reviewRepo,
                null,
                2
        );

        managedServiceController = new ManagedServiceController(telemetryService, dataRetentionService, taskOrchestrator);

        httpServer = new TrystHttpServer("127.0.0.1", 0, workspaceService, projectRepo, taskRepository, reviewRepo, null, null, permissionManager);
        httpServer.setPhase4Dependencies(
                authService,
                userRepository,
                sessionRepo,
                auditRepo,
                null,
                telemetryRepo,
                monitoringService,
                telemetryService,
                null,
                processExecutionManager,
                null,
                logService,
                null,
                dbManager,
                null,
                null
        );
        httpServer.setDataRetentionService(dataRetentionService);
        httpServer.setTaskOrchestrator(taskOrchestrator);
        httpServer.setManagedServiceController(managedServiceController);
        httpServer.start();
        boundPort = httpServer.getPort();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private record HttpResponse(int statusCode, String body, Map<String, List<String>> headers) {}

    private HttpResponse sendRequest(String method, String endpoint, String token, String body, String correlationId) throws Exception {
        URL url = URI.create("http://127.0.0.1:" + boundPort + "/tryst" + endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            conn.setRequestProperty("X-Correlation-Id", correlationId);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
        return new HttpResponse(code, responseBody, conn.getHeaderFields());
    }

    @Test
    void testAdminAuthorizationEnforced() throws Exception {
        // Unauthenticated access must be rejected
        HttpResponse unauth = sendRequest("GET", "/api/admin/server", null, null, null);
        assertEquals(403, unauth.statusCode());

        // Developer access without Admin role must be rejected
        HttpResponse devRes = sendRequest("GET", "/api/admin/server", devToken, null, null);
        assertEquals(403, devRes.statusCode());

        // Admin access succeeds
        HttpResponse adminRes = sendRequest("GET", "/api/admin/server", adminToken, null, null);
        assertEquals(200, adminRes.statusCode());
        assertTrue(adminRes.body().contains("DATABASE"));
    }

    @Test
    void testCorrelationIdPropagationInLogsAndResponses() throws Exception {
        String testCorrId = "corr-test-xyz-123";
        HttpResponse res = sendRequest("GET", "/api/admin/server", adminToken, null, testCorrId);
        assertEquals(200, res.statusCode());
        String returnedCorrId = res.headers().entrySet().stream()
                .filter(e -> "X-Correlation-Id".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .flatMap(l -> l.stream().findFirst())
                .orElse(null);
        assertNotNull(returnedCorrId, "Expected X-Correlation-Id header in response");
        assertEquals(testCorrId, returnedCorrId);

        // Verify diagnostic log capturing with correlation ID
        logService.log("INFO", "AdminTest", "Admin accessed server diagnostics", null, testCorrId, "task-999");
        List<LogDiagnosticService.LogEntry> entries = logService.query(null, null, "Admin accessed", testCorrId, null, 10);
        assertFalse(entries.isEmpty());
        assertEquals(testCorrId, entries.get(0).correlationId());
        assertEquals("task-999", entries.get(0).taskId());
    }

    @Test
    void testControlledAdministrationServicesStartAndStop() throws Exception {
        // Query services
        HttpResponse listRes = sendRequest("GET", "/api/admin/services", adminToken, null, null);
        assertEquals(200, listRes.statusCode());
        assertTrue(listRes.body().contains("telemetry"));
        assertTrue(listRes.body().contains("task-orchestrator"));

        // Stop telemetry service
        HttpResponse stopRes = sendRequest("POST", "/api/admin/services/telemetry/action", adminToken, "{\"action\": \"stop\"}", null);
        assertEquals(200, stopRes.statusCode());
        assertFalse(telemetryService.isRunning());

        // Start telemetry service
        HttpResponse startRes = sendRequest("POST", "/api/admin/services/telemetry/action", adminToken, "{\"action\": \"start\"}", null);
        assertEquals(200, startRes.statusCode());
        assertTrue(telemetryService.isRunning());
    }

    @Test
    void testTaskSupervisionAndCancellation() throws Exception {
        Task task = new Task("t-admin-1", null, null, "Supervised Task", "Long task",
                TaskStatus.QUEUED, TaskPriority.NORMAL, null, null, null, null, 0, null, java.time.Instant.now(), null);
        taskRepository.saveTask(task);

        HttpResponse tasksRes = sendRequest("GET", "/api/admin/tasks", adminToken, null, null);
        assertEquals(200, tasksRes.statusCode());
        assertTrue(tasksRes.body().contains("Supervised Task"));

        // Cancel task via administrative action
        HttpResponse cancelRes = sendRequest("POST", "/api/admin/tasks/t-admin-1/cancel", adminToken, "{\"reason\": \"Administrative abort\"}", null);
        assertEquals(200, cancelRes.statusCode());
        Optional<Task> updated = taskRepository.findTaskById("t-admin-1");
        assertTrue(updated.isPresent());
        assertEquals(TaskStatus.CANCELLED, updated.get().getStatus());
    }

    @Test
    void testProcessInspectionAndOutputStreaming() throws Exception {
        Process p = new ProcessBuilder("echo", "test").start();
        ManagedProcessRecord proc = processExecutionManager.registerProcess("test-task", p, "git checkout", "/sandboxes/ws1");
        processExecutionManager.appendProcessOutput(proc.getId(), "Checking out files...");
        processExecutionManager.appendProcessOutput(proc.getId(), "Done.");

        // Inspect processes
        HttpResponse procsRes = sendRequest("GET", "/api/admin/processes", adminToken, null, null);
        assertEquals(200, procsRes.statusCode());
        assertTrue(procsRes.body().contains("git checkout"));

        // Inspect output lines
        HttpResponse outRes = sendRequest("GET", "/api/admin/processes/" + proc.getId() + "/output", adminToken, null, null);
        assertEquals(200, outRes.statusCode());
        assertTrue(outRes.body().contains("Checking out files..."));
        assertTrue(outRes.body().contains("Done."));
    }

    @Test
    void testUserRoleManagementAndAuditing() throws Exception {
        User target = userRepository.findByUsername("developer").orElseThrow();
        assertEquals(UserRole.DEVELOPER, target.getRole());

        // Promote to REVIEWER
        HttpResponse roleRes = sendRequest("POST", "/api/admin/users/" + target.getId() + "/role", adminToken, "{\"role\": \"REVIEWER\"}", null);
        assertEquals(200, roleRes.statusCode());
        User updated = userRepository.findByUsername("developer").orElseThrow();
        assertEquals(UserRole.REVIEWER, updated.getRole());

        // Verify audit event was logged
        HttpResponse auditRes = sendRequest("GET", "/api/admin/audit", adminToken, null, null);
        assertEquals(200, auditRes.statusCode());
        assertTrue(auditRes.body().contains("USER_ROLE_CHANGE"));
    }
}
