package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PasswordHasher;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
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
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class AsynchronousTaskExecutionHttpE2ETest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;
    private TaskOrchestrator orchestrator;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private String authToken;
    private String testProjectId;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        Path dbPath = tempDir.resolve("e2e_async_tasks.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        SqliteUserRepository userRepo = new SqliteUserRepository(databaseManager);
        SqliteUserSessionRepository sessionRepo = new SqliteUserSessionRepository(databaseManager);
        PermissionManager permissionManager = new PermissionManager();
        AuthenticationService authService = new AuthenticationService(userRepo, sessionRepo, permissionManager);

        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hashPassword("Password123!".toCharArray(), salt);
        User devUser = new User("developer", hash, salt, UserRole.DEVELOPER);
        userRepo.save(devUser);

        AuthenticationService.AuthResult authRes = authService.login("developer", "Password123!", "127.0.0.1", "TestAgent");
        assertTrue(authRes.success());
        this.authToken = authRes.token();

        projectRepository = new SqliteProjectRepository(databaseManager);
        Project project = new Project("proj-e2e", devUser.getId(), "E2E Async Project", tempDir.resolve("proj-e2e"), true);
        projectRepository.save(project);
        this.testProjectId = project.getId();

        taskRepository = new SqliteTaskRepository(databaseManager);
        lifecycleManager = new TaskLifecycleManager(taskRepository, null);
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        orchestrator = new TaskOrchestrator(
                taskQueue,
                taskRepository,
                lifecycleManager,
                null,
                projectRepository,
                null,
                permissionManager,
                null,
                null,
                null,
                null,
                null,
                null,
                2
        );
        orchestrator.start();

        ConfigurationService configService = new ConfigurationService(tempDir);
        httpServer = new TrystHttpServer("127.0.0.1", 0, null, projectRepository, taskRepository, null, null, null, permissionManager);
        httpServer.setPhase4Dependencies(
                authService, userRepo, sessionRepo, null, null, null, null, null, null, null, null, null,
                configService, databaseManager, null, null
        );
        httpServer.setTaskOrchestrator(orchestrator);
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testTaskSubmissionImmediateReturnAndBackgroundProcessing() throws Exception {
        int port = httpServer.getPort();

        // 1. Submit task through HTTP: Must respond immediately with status 201 and status CREATED/QUEUED
        String payload = String.format("""
                {
                    "title": "Refactor Async Queue",
                    "description": "Production-grade execution",
                    "projectId": "%s",
                    "priority": "HIGH"
                }
                """, testProjectId);

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        long submitStart = System.currentTimeMillis();
        HttpResponse<String> postRes = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        long submitDuration = System.currentTimeMillis() - submitStart;

        assertEquals(201, postRes.statusCode());
        assertTrue(submitDuration < 2000, "Task submission must return immediately (was " + submitDuration + "ms)");

        String respBody = postRes.body();
        assertTrue(respBody.contains("\"id\":"));
        assertTrue(respBody.contains("\"QUEUED\"") || respBody.contains("\"CLAIMED\""));

        // Extract taskId
        int idIdx = respBody.indexOf("\"id\": \"");
        String taskId = respBody.substring(idIdx + 7, respBody.indexOf("\"", idIdx + 7));

        // 2. Wait for asynchronous background worker execution
        long deadline = System.currentTimeMillis() + 6000;
        Task completed = null;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(taskId).orElse(null);
            if (t != null && (t.getStatus() == TaskStatus.WAITING_FOR_REVIEW || t.getStatus() == TaskStatus.COMPLETED)) {
                completed = t;
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completed, "Task should asynchronously reach WAITING_FOR_REVIEW");
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, completed.getStatus());

        // 3. Query task events via HTTP API /api/tasks/{id}/events
        HttpRequest eventsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + taskId + "/events"))
                .header("Authorization", "Bearer " + authToken)
                .GET()
                .build();
        HttpResponse<String> eventsRes = httpClient.send(eventsReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, eventsRes.statusCode());
        assertTrue(eventsRes.body().contains("\"status\": \"CLONING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"ANALYZING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"PLANNING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"IMPLEMENTING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"BUILDING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"TESTING\""));
        assertTrue(eventsRes.body().contains("\"status\": \"WAITING_FOR_REVIEW\""));
    }

    @Test
    void testTaskCancellationThroughHttpApi() throws Exception {
        int port = httpServer.getPort();

        // Enqueue a low priority task
        Task task = orchestrator.submitTask(testProjectId, null, "Long Task", "Will be cancelled",
                TaskPriority.LOW, "developer", null);

        // Cancel via HTTP
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + task.getId() + "/cancel"))
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> cancelRes = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelRes.statusCode());
        assertTrue(cancelRes.body().contains("\"success\": true"));

        Task reloaded = taskRepository.findTaskById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, reloaded.getStatus());
        assertTrue(reloaded.getStatus().isTerminal());
    }
}
