package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.review.ReviewRevision;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

public class WebCodeReviewSubsystemTest {

    private Path tempDir;
    private SqliteDatabaseManager dbManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteCodeReviewRepository reviewRepository;
    private TaskOrchestrator taskOrchestrator;
    private TrystHttpServer server;
    private int boundPort;
    private HttpClient httpClient;

    private String projectId;
    private String taskId;
    private String reviewId;

    private static final String SAMPLE_DIFF =
            "diff --git a/src/Service.java b/src/Service.java\n" +
            "--- a/src/Service.java\n" +
            "+++ b/src/Service.java\n" +
            "@@ -1,4 +1,6 @@\n" +
            " public class Service {\n" +
            "-    void run() {}\n" +
            "+    void run() {\n" +
            "+        System.out.println(\"running\");\n" +
            "+    }\n" +
            " }\n";

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tryst-webreview-test-");
        Path dbPath = tempDir.resolve("tryst.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();

        projectRepository = new SqliteProjectRepository(dbManager);
        taskRepository = new SqliteTaskRepository(dbManager);
        reviewRepository = new SqliteCodeReviewRepository(dbManager);

        projectId = "p-" + UUID.randomUUID();
        taskId = "t-" + UUID.randomUUID();
        reviewId = "r-" + UUID.randomUUID();

        Project proj = new Project(projectId, "default-user", "Review Test Project", tempDir, true);
        projectRepository.save(proj);

        Task task = new Task(taskId, projectId, null, "Review Task Title", "Task Description",
                TaskStatus.WAITING_FOR_REVIEW, TaskPriority.HIGH, null, null, null, reviewId, 0, null, Instant.now(), null);
        taskRepository.saveTask(task);

        CodeReview review = new CodeReview(
                reviewId, projectId, taskId, "feature-branch", CodeReview.ReviewStatus.PENDING,
                SAMPLE_DIFF, SAMPLE_DIFF, "fp-12345", null, null, null, null, null,
                "SUCCESS", "15 passed, 0 failed", "Implemented logging in Service.java", 1,
                Instant.now(), Instant.now()
        );
        reviewRepository.save(review);

        ReviewRevision rev1 = new ReviewRevision(
                UUID.randomUUID().toString(), reviewId, 1, SAMPLE_DIFF,
                review.getAiExplanation(), "SUCCESS", "15 passed", Instant.now()
        );
        reviewRepository.saveRevision(rev1);

        EventBus eventBus = new EventBus();
        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, eventBus);
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        taskOrchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, null, projectRepository,
                null, new PermissionManager(), null, null, null, null, reviewRepository, null, 2
        );

        server = new TrystHttpServer("127.0.0.1", 0, null, projectRepository, taskRepository, reviewRepository, null, null, new PermissionManager());
        server.setTaskOrchestrator(taskOrchestrator);
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
    public void testGetReviewDetailsEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/details"))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertTrue(body.contains("\"id\":\"" + reviewId + "\""), "Expected review id in body: " + body);
        assertTrue(body.contains("Implemented logging in Service.java"), "Expected aiExplanation in body: " + body);
        assertTrue(body.contains("\"revisionCount\":1"), "Expected revisionCount in body: " + body);
        assertTrue(body.contains("\"totalFiles\":1"), "Expected totalFiles in body: " + body);
        assertTrue(body.contains("\"files\":["), "Expected files in body: " + body);
        assertTrue(body.contains("\"revisions\":["), "Expected revisions in body: " + body);
    }

    @Test
    public void testGetStructuredDiffUnifiedAndSideBySide() throws Exception {
        // Unified view
        HttpRequest reqUnified = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/diff?view=unified"))
                .GET()
                .build();
        HttpResponse<String> respUnified = httpClient.send(reqUnified, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respUnified.statusCode());
        assertTrue(respUnified.body().contains("\"lines\":["));

        // Side-by-side view
        HttpRequest reqSbs = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/diff?view=side-by-side"))
                .GET()
                .build();
        HttpResponse<String> respSbs = httpClient.send(reqSbs, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respSbs.statusCode());
        assertTrue(respSbs.body().contains("\"sideBySide\":["));
    }

    @Test
    public void testCommentLifecycleAndReworkSubmission() throws Exception {
        // 1. Post a new comment anchored to line 3
        String postBody = """
                {
                    "filePath": "src/Service.java",
                    "diffSide": "RIGHT",
                    "startLine": 3,
                    "endLine": 3,
                    "codeContext": "System.out.println(\\\"running\\\");",
                    "commentType": "LINE",
                    "content": "Use a logger instead of System.out.println",
                    "includeInRework": true
                }
                """;
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(postBody))
                .build();
        HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postResp.statusCode());
        assertTrue(postResp.body().contains("Use a logger instead of System.out.println"));

        // 2. Query comments list
        HttpRequest listReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments"))
                .GET()
                .build();
        HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResp.statusCode());
        assertTrue(listResp.body().contains("\"comments\":["));

        // 3. Test Explain endpoint
        String explainBody = """
                {
                    "filePath": "src/Service.java",
                    "codeSnippet": "System.out.println(\\\"running\\\");",
                    "question": "What is the purpose of this print statement?"
                }
                """;
        HttpRequest explainReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/explain"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(explainBody))
                .build();
        HttpResponse<String> explainResp = httpClient.send(explainReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, explainResp.statusCode());
        assertTrue(explainResp.body().contains("explanation"));

        // 4. Test Rework submission endpoint
        String reworkBody = "{\"reason\": \"Address review comment regarding logger usage\"}";
        HttpRequest reworkReq = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/rework"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reworkBody))
                .build();
        HttpResponse<String> reworkResp = httpClient.send(reworkReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reworkResp.statusCode());
        assertTrue(reworkResp.body().contains("\"success\": true"));

        // Verify task state transitioned to QUEUED with incremented rework count
        Task updatedTask = taskRepository.findTaskById(taskId).orElseThrow();
        assertEquals(TaskStatus.QUEUED, updatedTask.getStatus());
        assertEquals(1, updatedTask.getReworkCount());
    }

    @Test
    public void testConcurrentReviewComments() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                String body = String.format("""
                        {
                            "filePath": "src/Service.java",
                            "startLine": %d,
                            "content": "Concurrent comment %d",
                            "includeInRework": true
                        }
                        """, idx + 1, idx);
                HttpRequest r = HttpRequest.newBuilder()
                        .uri(URI.create(getBaseUrl() + "/api/reviews/" + reviewId + "/comments"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                return httpClient.send(r, HttpResponse.BodyHandlers.ofString()).statusCode();
            }));
        }

        for (Future<Integer> f : futures) {
            assertEquals(201, f.get());
        }
        pool.shutdown();

        List<ReviewComment> comments = reviewRepository.findCommentsByReviewId(reviewId);
        assertEquals(threads, comments.size());
    }
}
