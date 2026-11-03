package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.domain.ai.CodeAgent;
import eu.kalafatic.tryst.domain.ai.CodeChangeExecutor;
import eu.kalafatic.tryst.domain.ai.ImplementationPlanner;
import eu.kalafatic.tryst.domain.ai.PromptContextBuilder;
import eu.kalafatic.tryst.domain.ai.ResultValidator;
import eu.kalafatic.tryst.domain.ai.TaskAnalyzer;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.review.ReviewRevision;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.NotificationService;
import eu.kalafatic.tryst.domain.service.TaskExecutionContext;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.TaskWorker;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class IterativeReviewAndReworkWorkflowTest {

    private Path tempDir;
    private SqliteDatabaseManager dbManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteCodeReviewRepository reviewRepository;
    private SqliteNotificationRepository notificationRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private NotificationService notificationService;
    private TaskOrchestrator taskOrchestrator;
    private WorkspaceService workspaceService;
    private TrystHttpServer server;
    private int boundPort;
    private HttpClient httpClient;

    private String projectId;
    private String taskId;
    private String reviewId;
    private String ownerUserId = "user-reviewer";
    private Path workspaceDir;

    private static final String REV1_DIFF =
            "diff --git a/src/UserRepository.java b/src/UserRepository.java\n" +
            "--- a/src/UserRepository.java\n" +
            "+++ b/src/UserRepository.java\n" +
            "@@ -10,6 +10,12 @@\n" +
            " public class UserRepository {\n" +
            "+    public User findById(String id) {\n" +
            "+        // Query database\n" +
            "+        Database.query(\"SELECT * FROM users WHERE id = \" + id);\n" +
            "+        return Database.query(\"SELECT * FROM users WHERE id = \" + id);\n" +
            "+    }\n" +
            " }\n";

    private static final String REV2_DIFF =
            "diff --git a/src/UserRepository.java b/src/UserRepository.java\n" +
            "--- a/src/UserRepository.java\n" +
            "+++ b/src/UserRepository.java\n" +
            "@@ -10,6 +10,12 @@\n" +
            " public class UserRepository {\n" +
            "+    public User findById(String id) {\n" +
            "+        // Avoid duplicated database queries and preserve existing public API\n" +
            "+        return Database.querySingle(\"SELECT * FROM users WHERE id = ?\", id);\n" +
            "+    }\n" +
            " }\n";

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tryst-iterative-rework-");
        Path dbPath = tempDir.resolve("tryst.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();

        projectRepository = new SqliteProjectRepository(dbManager);
        taskRepository = new SqliteTaskRepository(dbManager);
        reviewRepository = new SqliteCodeReviewRepository(dbManager);
        notificationRepository = new SqliteNotificationRepository(dbManager);
        workspaceRepository = new SqliteWorkspaceRepository(dbManager, projectRepository, taskRepository);
        eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository userRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository(dbManager);

        EventBus eventBus = new EventBus();
        notificationService = new NotificationService(notificationRepository, eventBus);

        projectId = "proj-" + UUID.randomUUID();
        taskId = "task-" + UUID.randomUUID();
        reviewId = "rev-" + UUID.randomUUID();

        workspaceDir = tempDir.resolve("ws");
        Files.createDirectories(workspaceDir);

        eu.kalafatic.tryst.domain.model.User ownerUser = new eu.kalafatic.tryst.domain.model.User(
                ownerUserId,
                "reviewer",
                "hash",
                "salt",
                eu.kalafatic.tryst.domain.model.UserRole.DEVELOPER,
                true,
                false,
                0,
                Instant.now(),
                Instant.now()
        );
        userRepo.save(ownerUser);

        Project project = new Project(projectId, ownerUserId, "Iterative Rework Project", workspaceDir, true);
        projectRepository.save(project);

        Workspace ws = new Workspace(
                UUID.randomUUID().toString(),
                workspaceDir,
                ownerUserId,
                taskId,
                null,
                "main",
                WorkspaceStatus.WAITING_FOR_REVIEW,
                Instant.now(),
                Instant.now(),
                Map.of()
        );
        workspaceRepository.save(ws);

        workspaceService = new WorkspaceService(workspaceDir, workspaceRepository, projectRepository, taskRepository);

        Task task = new Task(taskId, projectId, null, "Refactor UserRepository", "Refactor data access layer",
                TaskStatus.WAITING_FOR_REVIEW, TaskPriority.HIGH, null, null, null, reviewId, 0, null, Instant.now(), null);
        taskRepository.saveTask(task);

        CodeReview review = new CodeReview(
                reviewId, projectId, taskId, "main", CodeReview.ReviewStatus.PENDING,
                REV1_DIFF, REV1_DIFF, "fp-rev-1", null, null, null, null, null,
                "SUCCESS", "All tests passed", "Initial AI implementation with duplicated queries", 1,
                Instant.now(), Instant.now()
        );
        reviewRepository.save(review);

        ReviewRevision rev1 = new ReviewRevision(
                UUID.randomUUID().toString(), reviewId, 1, REV1_DIFF,
                review.getAiExplanation(), "SUCCESS", "All tests passed", Instant.now()
        );
        reviewRepository.saveRevision(rev1);

        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, eventBus);
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        taskOrchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                null, new PermissionManager(), null, null, null, null, reviewRepository, null, 2
        );
        taskOrchestrator.setNotificationService(notificationService);

        server = new TrystHttpServer("127.0.0.1", 0, workspaceService, projectRepository, taskRepository, reviewRepository, null, null, new PermissionManager());
        server.setTaskOrchestrator(taskOrchestrator);
        server.setPhase4Dependencies(
                null, null, null, null, notificationRepository, null,
                null, null, null, null, notificationService, null, null, dbManager, null, null
        );
        server.start();
        boundPort = server.getPort();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (taskOrchestrator != null) {
            taskOrchestrator.shutdown();
        }
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

    private String getBaseUrl() {
        return "http://localhost:" + boundPort;
    }

    @Test
    public void testCompleteIterativeReviewAndReworkCycle() throws Exception {
        // 1. Post a Line Comment targeting lines 120-135 with code context
        String lineCommentJson = """
                {
                    "filePath": "src/UserRepository.java",
                    "diffSide": "RIGHT",
                    "startLine": 120,
                    "endLine": 135,
                    "codeContext": "Database.query(\\\"SELECT * FROM users WHERE id = \\\" + id);",
                    "commentType": "LINE",
                    "content": "Refactor this method. Avoid duplicated database queries and preserve the existing public API.",
                    "includeInRework": true
                }
                """;

        HttpRequest postCommentReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .POST(HttpRequest.BodyPublishers.ofString(lineCommentJson))
                .build();
        HttpResponse<String> postCommentResp = httpClient.send(postCommentReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postCommentResp.statusCode());
        String commentBody = postCommentResp.body();
        assertTrue(commentBody.contains("\"startLine\":120"));
        assertTrue(commentBody.contains("\"endLine\":135"));
        assertTrue(commentBody.contains("Avoid duplicated database queries"));

        // 2. Post a Global Review Instruction
        String globalCommentJson = """
                {
                    "diffSide": "RIGHT",
                    "commentType": "GENERAL",
                    "content": "Ensure zero memory leaks and run PRAGMA integrity checks.",
                    "includeInRework": true
                }
                """;
        HttpRequest postGlobalReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .POST(HttpRequest.BodyPublishers.ofString(globalCommentJson))
                .build();
        HttpResponse<String> postGlobalResp = httpClient.send(postGlobalReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postGlobalResp.statusCode());

        // Extract line comment ID from review comments list
        List<ReviewComment> comments = reviewRepository.findCommentsByReviewId(reviewId);
        assertEquals(2, comments.size());
        ReviewComment lineComment = comments.stream().filter(c -> c.getStartLine() == 120).findFirst().orElseThrow();

        // 3. Request AI Explanation for the snippet
        String explainJson = String.format("""
                {
                    "filePath": "src/UserRepository.java",
                    "codeSnippet": "%s",
                    "question": "Why is the database queried twice?"
                }
                """, "Database.query(...)");
        HttpRequest explainReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/explain"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .POST(HttpRequest.BodyPublishers.ofString(explainJson))
                .build();
        HttpResponse<String> explainResp = httpClient.send(explainReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, explainResp.statusCode());
        assertTrue(explainResp.body().contains("explanation"));

        // 4. Test resolving and reopening comment
        HttpRequest resolveReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments/" + lineComment.getId()))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"resolved\": true}"))
                .build();
        HttpResponse<String> resolveResp = httpClient.send(resolveReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resolveResp.statusCode());
        assertTrue(resolveResp.body().contains("\"resolved\":true"));

        // Reopen comment so it is included in rework
        HttpRequest reopenReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments/" + lineComment.getId()))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"resolved\": false}"))
                .build();
        HttpResponse<String> reopenResp = httpClient.send(reopenReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reopenResp.statusCode());
        assertTrue(reopenResp.body().contains("\"resolved\":false"));

        // 5. Submit Asynchronous Rework Operation
        String reworkReqJson = "{\"reason\": \"Address line 120-135 feedback: Avoid duplicated database queries\"}";
        HttpRequest reworkReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/rework"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ownerUserId)
                .POST(HttpRequest.BodyPublishers.ofString(reworkReqJson))
                .build();
        HttpResponse<String> reworkResp = httpClient.send(reworkReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reworkResp.statusCode());
        assertTrue(reworkResp.body().contains("\"success\": true"));

        // 6. Verify task transitions to QUEUED with reworkCount = 1
        Task queuedTask = taskRepository.findTaskById(taskId).orElseThrow();
        assertEquals(TaskStatus.QUEUED, queuedTask.getStatus());
        assertEquals(1, queuedTask.getReworkCount());

        // 7. Simulate claiming task and TaskWorker executing the rework iteration with AI Provider
        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);
        Task claimedTask = taskQueue.claimNext("worker-rework-1").orElseThrow();
        assertEquals(TaskStatus.CLAIMED, claimedTask.getStatus());

        MockReworkAiProvider mockAi = new MockReworkAiProvider();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new Tool() {
            @Override
            public String getName() { return "git_diff"; }
            @Override
            public String getDescription() { return "Returns diff"; }
            @Override
            public PermissionManager.PermissionLevel getRequiredPermission() {
                return PermissionManager.PermissionLevel.READ_ONLY;
            }
            @Override
            public ToolResult execute(Map<String, String> p, Workspace w, PermissionManager pm) {
                return ToolResult.ok(REV2_DIFF);
            }
        });

        TaskExecutionContext execCtx = new TaskExecutionContext(
                claimedTask,
                projectRepository.findById(projectId).get(),
                workspaceRepository.findByTaskId(taskId).get(),
                ownerUserId,
                "worker-rework-1",
                "mock-model"
        );

        PromptContextBuilder promptContextBuilder = new PromptContextBuilder(4096);
        TaskAnalyzer taskAnalyzer = new TaskAnalyzer(new PermissionManager());
        ImplementationPlanner implementationPlanner = new ImplementationPlanner();
        CodeChangeExecutor codeChangeExecutor = new CodeChangeExecutor(toolRegistry, new PermissionManager());
        ResultValidator resultValidator = new ResultValidator(toolRegistry, new PermissionManager());
        CodeAgent codeAgent = new CodeAgent(mockAi, promptContextBuilder, codeChangeExecutor, 1, 10);

        TaskWorker worker = new TaskWorker(
                execCtx,
                lifecycleManager,
                toolRegistry,
                new PermissionManager(),
                mockAi,
                promptContextBuilder,
                taskAnalyzer,
                implementationPlanner,
                codeChangeExecutor,
                resultValidator,
                codeAgent,
                null,
                null,
                reviewRepository,
                null,
                null,
                notificationService
        );

        worker.call();

        // 8. Verify the AI received the structured instructions & scope constraints
        assertTrue(mockAi.capturedPrompt.contains("MANDATORY ITERATIVE REVIEW REWORK INSTRUCTIONS"));
        assertTrue(mockAi.capturedPrompt.contains("Lines: 120–135"));
        assertTrue(mockAi.capturedPrompt.contains("Avoid duplicated database queries"));
        assertTrue(mockAi.capturedPrompt.contains("Preserve all existing public APIs"));

        // 9. Verify Revision History was preserved (Revision 1 exists, Revision 2 created)
        List<ReviewRevision> revisions = reviewRepository.findRevisionsByReviewId(reviewId);
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.get(0).getRevisionNumber());
        assertEquals(2, revisions.get(1).getRevisionNumber());
        assertEquals(REV1_DIFF, revisions.get(0).getDiff());
        assertEquals(REV2_DIFF, revisions.get(1).getDiff());

        // 10. Verify User Notification was generated
        List<Notification> userNotifs = notificationRepository.findByUserId(ownerUserId, false, 10);
        assertFalse(userNotifs.isEmpty());
        assertTrue(userNotifs.stream().anyMatch(n -> n.getTitle().contains("Rework Complete (Rev 2)")));

        // 11. Test Comparing Arbitrary Revisions (Revision 2 vs Base Revision 1)
        HttpRequest compareDiffReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/diff?revision=2&baseRevision=1"))
                .header("X-User-Id", ownerUserId)
                .GET()
                .build();
        HttpResponse<String> compareDiffResp = httpClient.send(compareDiffReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, compareDiffResp.statusCode());
        String diffBody = compareDiffResp.body();
        assertTrue(diffBody.contains("\"files\":["));
        assertTrue(diffBody.contains("UserRepository.java"));
    }

    private static class MockReworkAiProvider implements AIProvider {
        String capturedPrompt = "";

        @Override
        public String getProviderId() { return "mock-rework-ai"; }

        @Override
        public CompletableFuture<Boolean> checkConnection() { return CompletableFuture.completedFuture(true); }

        @Override
        public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
            return CompletableFuture.completedFuture(List.of(new ProviderModelMetadata("mock-model", 1000L, Instant.now().toString(), "gguf", "llama", "7b")));
        }

        @Override
        public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
            if (!request.messages().isEmpty()) {
                capturedPrompt = request.messages().get(request.messages().size() - 1).content();
            }
            return CompletableFuture.completedFuture(new InferenceResponse("DONE: Refactored query to use querySingle without duplicates.", "stop", 50, 10, 10));
        }

        @Override
        public CompletableFuture<Void> streamResponse(InferenceRequest request, java.util.function.Consumer<String> chunkConsumer) {
            if (!request.messages().isEmpty()) {
                capturedPrompt = request.messages().get(request.messages().size() - 1).content();
            }
            chunkConsumer.accept("DONE: Refactored query to use querySingle without duplicates.");
            return CompletableFuture.completedFuture(null);
        }
    }
}
