package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConversationMessageRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskInstructionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario B (session continuation) end-to-end verification through the real HTTP
 * surface: an intake submission creates a coding session; continuing the session
 * creates a follow-up task bound to the same session and the same sandbox; previous
 * changes are preserved and the accumulated diff contains both iterations.
 */
class SessionContinuationHttpTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteSessionRepository sessionRepository;
    private SqliteConversationMessageRepository conversationMessageRepository;
    private SqliteTaskInstructionRepository taskInstructionRepository;
    private SqliteExecutionResultRepository executionResultRepository;
    private SqliteCodeReviewRepository codeReviewRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;
    private TaskOrchestrator orchestrator;
    private WorkspaceService workspaceService;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private int port;
    private Project project;
    private Path sourceRepo;

    private static void runGit(Path dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(dir.toFile());
        pb.environment().put("GIT_AUTHOR_NAME", "test");
        pb.environment().put("GIT_AUTHOR_EMAIL", "test@example.com");
        pb.environment().put("GIT_COMMITTER_NAME", "test");
        pb.environment().put("GIT_COMMITTER_EMAIL", "test@example.com");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.getErrorStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited with " + code);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Path dbPath = tempDir.resolve("continue.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        sessionRepository = new SqliteSessionRepository(databaseManager);
        conversationMessageRepository = new SqliteConversationMessageRepository(databaseManager);
        taskInstructionRepository = new SqliteTaskInstructionRepository(databaseManager);
        executionResultRepository = new SqliteExecutionResultRepository(databaseManager);
        codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);
        SqliteWorkspaceRepository workspaceRepository =
                new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);

        PermissionManager permissionManager = new PermissionManager();
        lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);
        workspaceService = new WorkspaceService(tempDir.resolve("ws"), workspaceRepository,
                projectRepository, taskRepository, permissionManager, null);

        // Stateful fake AI: iteration 1 writes FirstFeature.java, iteration 2 writes
        // SecondFeature.java — verifying accumulated workspace state across continuations.
        AtomicInteger iteration = new AtomicInteger(0);
        AiInferenceService statefulAi = new AiInferenceService() {
            @Override
            public CompletableFuture<Boolean> checkConnection() {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<List<ModelMetadata>> listModels() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest req) {
                return CompletableFuture.completedFuture(new InferenceResponse(directive(), "stop", 5, 1, 1));
            }

            @Override
            public CompletableFuture<Void> streamResponse(InferenceRequest req, java.util.function.Consumer<String> chunkConsumer) {
                if (chunkConsumer != null) {
                    chunkConsumer.accept(directive());
                }
                return CompletableFuture.completedFuture(null);
            }

            private String directive() {
                int n = iteration.incrementAndGet();
                String file = n == 1 ? "FirstFeature.java" : "SecondFeature.java";
                return "file: " + file + "\n```\npublic class " + file.substring(0, file.length() - 5)
                        + " {\n    // iteration " + n + "\n}\n```";
            }
        };

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new eu.kalafatic.tryst.domain.tool.FileTools.WriteFileTool());
        toolRegistry.register(new eu.kalafatic.tryst.domain.tool.GitTools.GitDiffTool());

        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                toolRegistry, permissionManager, statefulAi, null, conversationMessageRepository,
                executionResultRepository, codeReviewRepository,
                new eu.kalafatic.tryst.domain.review.CodeReviewService(), 2
        );
        TestAiFakes.configureModelSelection(orchestrator, "test-model");
        orchestrator.setTaskInstructionRepository(taskInstructionRepository);
        orchestrator.setGitHubService(new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService(
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitHubCredentialRepository(databaseManager),
                new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubCredentialEncryptionService(),
                new eu.kalafatic.tryst.infrastructure.vcs.GitService()));
        orchestrator.start();

        ConfigurationService configService = new ConfigurationService(tempDir);
        httpServer = new TrystHttpServer("127.0.0.1", 0, workspaceService, projectRepository, taskRepository,
                codeReviewRepository, new eu.kalafatic.tryst.infrastructure.vcs.GitService(),
                new eu.kalafatic.tryst.infrastructure.build.BuildAndTestService(), permissionManager);
        httpServer.setTaskOrchestrator(orchestrator);
        httpServer.setCodingSessionRepository(sessionRepository);
        httpServer.setConversationMessageRepository(conversationMessageRepository);
        httpServer.setTaskInstructionRepository(taskInstructionRepository);
        httpServer.setExecutionResultRepository(executionResultRepository);
        httpServer.setTaskIntakeService(new eu.kalafatic.tryst.domain.intake.DefaultTaskIntakeService(
                orchestrator, taskRepository, taskInstructionRepository, projectRepository,
                workspaceService, null, null, permissionManager,
                sessionRepository, conversationMessageRepository));
        httpServer.start();
        port = httpServer.getPort();

        project = new Project("proj-cont", "user-1", "Continuation Project", tempDir.resolve("proj-cont"), true);

        // Real git repository used as the task source so the sandbox clone produces
        // genuine diffs for both continuation iterations
        sourceRepo = tempDir.resolve("repo-src");
        Files.createDirectories(sourceRepo);
        runGit(sourceRepo, "git", "init", "-b", "main");
        Files.writeString(sourceRepo.resolve("README.md"), "# Baseline\n");
        runGit(sourceRepo, "git", "add", "README.md");
        runGit(sourceRepo, "git", "commit", "-m", "baseline");

        project.setRepositoryUrl(sourceRepo.toString());
        projectRepository.save(project);
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

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-User-Id", "user-1")
                .method("POST", HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-User-Id", "user-1").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitStatus(String taskId, TaskStatus expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(taskId).orElseThrow();
            if (t.getStatus() == expected) {
                return;
            }
            if (t.getStatus() == TaskStatus.FAILED) {
                fail("Task failed: " + t.getFailureReason());
            }
            Thread.sleep(100);
        }
        fail("Task " + taskId + " did not reach " + expected + " in time; status="
                + taskRepository.findTaskById(taskId).orElseThrow().getStatus());
    }

    @Test
    void testSessionContinuationPreservesWorkspaceAndAccumulatesDiff() throws Exception {
        // 1. Intake submission creates task + session + workspace
        String intake = "{\"title\": \"Iteration One\", \"description\": \"Create the first feature file with at least ten characters of instruction\","
                + "\"projectId\": \"proj-cont\", \"repositoryUrl\": \"" + sourceRepo.toUri() + "\", \"branch\": \"main\", \"priority\": \"NORMAL\"}";
        HttpResponse<String> submit = post("/api/tasks/intake/submit", intake);
        assertEquals(201, submit.statusCode(), submit.body());
        String taskId1 = extractJsonString(submit.body(), "taskId");
        String sessionId = extractJsonString(submit.body(), "sessionId");
        assertNotNull(taskId1);
        assertNotNull(sessionId, "Intake must return the coding session id");

        awaitStatus(taskId1, TaskStatus.WAITING_FOR_REVIEW, 30000);

        // First iteration changed the shared sandbox
        Workspace ws1 = workspaceService.getWorkspaceForTask(taskId1);
        assertNotNull(ws1);
        assertTrue(Files.exists(ws1.getRootPath().resolve("FirstFeature.java")),
                "First iteration must modify the sandbox");

        // 2. Continue the same session with an additional instruction
        String cont = "{\"instruction\": \"Add a second feature file now with more than ten characters\", \"title\": \"Iteration Two\"}";
        HttpResponse<String> contRes = post("/api/tasks/" + taskId1 + "/continue", cont);
        assertEquals(201, contRes.statusCode(), contRes.body());
        String taskId2 = extractJsonString(contRes.body(), "taskId");
        assertNotNull(taskId2);
        assertTrue(contRes.body().contains("\"workspaceReused\": true"),
                "Continuation must reuse the existing sandbox: " + contRes.body());

        awaitStatus(taskId2, TaskStatus.WAITING_FOR_REVIEW, 30000);

        // 3. Same session binding
        Task t1 = taskRepository.findTaskById(taskId1).orElseThrow();
        Task t2 = taskRepository.findTaskById(taskId2).orElseThrow();
        assertEquals(t1.getSessionId(), t2.getSessionId(), "Both tasks must belong to the same session");
        assertEquals(sessionId, t2.getSessionId());

        // 4. Same working state preserved: both files exist and the new review's diff
        //    contains BOTH iterations (previous changes are never discarded)
        Workspace ws2 = workspaceService.getWorkspaceForTask(taskId2);
        assertNotNull(ws2);
        assertEquals(ws1.getRootPath(), ws2.getRootPath(), "Continuation must share the same sandbox directory");
        assertTrue(Files.exists(ws2.getRootPath().resolve("FirstFeature.java")),
                "Previous iteration changes must be preserved");
        assertTrue(Files.exists(ws2.getRootPath().resolve("SecondFeature.java")),
                "New iteration changes must be applied");

        var review2 = codeReviewRepository.findByTaskId(taskId2);
        assertTrue(review2.isPresent(), "Follow-up task must produce a review");
        String diff = review2.get().getEffectiveDiff() != null
                ? review2.get().getEffectiveDiff() : review2.get().getInitialDiff();
        assertTrue(diff.contains("FirstFeature.java"), "Accumulated diff must contain previous iteration changes");
        assertTrue(diff.contains("SecondFeature.java"), "Accumulated diff must contain new iteration changes");

        // 5. Session detail reflects both tasks and the conversation history
        HttpResponse<String> sessRes = get("/api/sessions/" + sessionId);
        assertEquals(200, sessRes.statusCode());
        assertTrue(sessRes.body().contains(taskId1) && sessRes.body().contains(taskId2),
                "Session detail must list both tasks");
        assertTrue(sessRes.body().contains("Add a second feature file"),
                "Session conversation must record the continuation instruction");
    }

    private static String extractJsonString(String json, String field) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
