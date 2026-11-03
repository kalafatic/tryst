package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.domain.ai.*;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.*;
import eu.kalafatic.tryst.domain.tool.FileTools;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.ai.OllamaProvider;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for the AI development execution subsystem:
 * - Mock AI Provider exercising the 10-step development workflow
 * - Model selection, timeouts, cancellations, retry policies, failure classification
 * - OllamaProvider HTTP API (/api/tags, /api/chat, streaming) against mock server
 * - Conditional real Ollama test if available at http://localhost:11434
 */
class AiExecutionSubsystemIntegrationTest {

    private static HttpServer mockOllamaServer;
    private static int mockOllamaPort;

    @BeforeAll
    static void setUpMockServer() throws Exception {
        mockOllamaServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockOllamaPort = mockOllamaServer.getAddress().getPort();

        mockOllamaServer.createContext("/api/tags", exchange -> {
            String json = """
                {
                    "models": [
                        {"name": "codellama:latest", "size": 3825000000},
                        {"name": "deepseek-coder:6.7b", "size": 3900000000}
                    ]
                }
            """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        mockOllamaServer.createContext("/api/chat", exchange -> {
            boolean isStream = exchange.getRequestURI().toString().contains("stream")
                    || exchange.getRequestHeaders().getFirst("Accept") != null && exchange.getRequestHeaders().getFirst("Accept").contains("stream");

            // Check request body to see if stream: true
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    String c1 = "{\"message\":{\"role\":\"assistant\",\"content\":\"Processing \"},\"done\":false}\n";
                    String c2 = "{\"message\":{\"role\":\"assistant\",\"content\":\"task changes.\\n\"},\"done\":false}\n";
                    String c3 = "{\"message\":{\"role\":\"assistant\",\"content\":\"TOOL:write_file PARAM:path=README.md PARAM:content=UpdatedByOllama\\n\"},\"done\":true}\n";
                    os.write(c1.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write(c2.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.write(c3.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } else {
                String json = """
                    {
                        "model": "codellama",
                        "message": {
                            "role": "assistant",
                            "content": "Analysis complete.\\nTOOL:write_file PARAM:path=hello.txt PARAM:content=HelloFromOllama\\nDone."
                        },
                        "done": true
                    }
                """;
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        mockOllamaServer.start();
    }

    @AfterAll
    static void tearDownMockServer() {
        if (mockOllamaServer != null) {
            mockOllamaServer.stop(0);
        }
    }

    @Test
    void testOllamaProviderTagsAndChatSynchronousAndStreaming() throws Exception {
        OllamaProvider provider = new OllamaProvider("mock-ollama", "http://localhost:" + mockOllamaPort, Duration.ofSeconds(5));

        assertTrue(provider.checkConnection().get());
        List<AIProvider.ProviderModelMetadata> models = provider.listAvailableModels().get();
        assertEquals(2, models.size());
        assertEquals("codellama:latest", models.get(0).name());

        // Synchronous generateResponse
        AIProvider.InferenceRequest req = new AIProvider.InferenceRequest(
                "codellama",
                List.of(AIProvider.ChatMessage.user("Generate code"))
        );
        AIProvider.InferenceResponse resp = provider.generateResponse(req).get();
        assertNotNull(resp);
        assertTrue(resp.content().contains("TOOL:write_file"));

        // Streaming streamResponse
        List<String> chunks = new ArrayList<>();
        provider.streamResponse(req, chunks::add).get();
        assertFalse(chunks.isEmpty());
        String aggregated = String.join("", chunks);
        assertTrue(aggregated.contains("Processing task changes"));
    }

    @Test
    void testModelSelectionServiceFallbackAndRoleFiltering() {
        AiModelRegistryService registry = new AiModelRegistryService("default-model");
        registry.publishModel(new AiModelRegistryService.PublishedModel(
                "model-user", "provider1", "codellama", "Code Llama", 4096, true, true, UserRole.USER, java.time.Instant.now()
        ));
        registry.publishModel(new AiModelRegistryService.PublishedModel(
                "model-admin", "provider1", "gpt-4-local", "Admin Model", 8192, true, true, UserRole.ADMIN, java.time.Instant.now()
        ));

        ModelSelectionService selector = new ModelSelectionService(registry);
        Task task = new Task("t1", "p1", null, "Build feature", "Desc", TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null, java.time.Instant.now(), null);

        // Standard user gets user model
        String selectedForUser = selector.selectModel(task, null, UserRole.USER);
        assertEquals("codellama", selectedForUser);

        // Admin requesting admin model gets it
        String selectedForAdmin = selector.selectModel(task, "model-admin", UserRole.ADMIN);
        assertEquals("gpt-4-local", selectedForAdmin);
    }

    @Test
    void testFull10StepAutonomousDevelopmentWorkflowWithMockAI(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("tryst-ai.db");
        Path workspaceDir = tempDir.resolve("sandbox-ai");
        Files.createDirectories(workspaceDir);

        // Prepopulate workspace with a mock repository structure and convention
        Files.writeString(workspaceDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Path srcDir = workspaceDir.resolve("src").resolve("main");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Calculator.java"), "public class Calculator {}");

        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            new SchemaMigrationService(dbManager).migrate();

            TaskRepository taskRepo = new SqliteTaskRepository(dbManager);
            ProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
            SqliteExecutionResultRepository execResultRepo = new SqliteExecutionResultRepository(dbManager);
            SqliteCodeReviewRepository reviewRepo = new SqliteCodeReviewRepository(dbManager);

            PermissionManager permissionManager = new PermissionManager();
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.register(new FileTools.ReadFileTool());
            toolRegistry.register(new FileTools.WriteFileTool());
            toolRegistry.register(new FileTools.ListDirectoryTool());

            Project project = new Project("proj-ai", "AI Test Project", "Project for AI workflow", workspaceDir, true);
            projectRepo.save(project);

            Workspace ws = new Workspace(UUID.randomUUID().toString(), workspaceDir);
            ws.setActiveProject(project.getId());

            Task task = new Task("task-100", project.getId(), null, "Update Calculator.java to add sum function", "Detailed specs", TaskStatus.CLAIMED, TaskPriority.NORMAL, null, "worker-1", null, null, 0, null, java.time.Instant.now(), null);
            taskRepo.saveTask(task);

            TaskExecutionContext execContext = new TaskExecutionContext(task, project, ws, "user1", "worker-1", "mock-model");
            TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepo, null);

            // Mock AI provider that returns a tool command modifying Calculator.java
            AIProvider mockProvider = new AIProvider() {
                @Override
                public String getProviderId() {
                    return "controlled-mock";
                }

                @Override
                public CompletableFuture<Boolean> checkConnection() {
                    return CompletableFuture.completedFuture(true);
                }

                @Override
                public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
                    return CompletableFuture.completedFuture(List.of(new ProviderModelMetadata("mock-model", 0, "", "", "", "")));
                }

                @Override
                public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
                    String reply = "Plan approved.\nTOOL:write_file PARAM:path=src/main/Calculator.java PARAM:content=public class Calculator { public int add(int a, int b) { return a + b; } }\nCompleted.";
                    return CompletableFuture.completedFuture(new InferenceResponse(reply, "stop", 50, 10, 20));
                }

                @Override
                public CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer) {
                    chunkConsumer.accept("Plan approved.\n");
                    chunkConsumer.accept("TOOL:write_file PARAM:path=src/main/Calculator.java PARAM:content=public class Calculator { public int add(int a, int b) { return a + b; } }\n");
                    chunkConsumer.accept("Completed.");
                    return CompletableFuture.completedFuture(null);
                }
            };

            PromptContextBuilder promptContextBuilder = new PromptContextBuilder(4096);
            TaskAnalyzer taskAnalyzer = new TaskAnalyzer(permissionManager);
            ImplementationPlanner implementationPlanner = new ImplementationPlanner();
            CodeChangeExecutor codeChangeExecutor = new CodeChangeExecutor(toolRegistry, permissionManager);
            ResultValidator resultValidator = new ResultValidator(toolRegistry, permissionManager);
            CodeAgent codeAgent = new CodeAgent(mockProvider, promptContextBuilder, codeChangeExecutor, 2, 10);

            TaskWorker worker = new TaskWorker(
                    execContext,
                    lifecycleManager,
                    toolRegistry,
                    permissionManager,
                    mockProvider,
                    promptContextBuilder,
                    taskAnalyzer,
                    implementationPlanner,
                    codeChangeExecutor,
                    resultValidator,
                    codeAgent,
                    null,
                    execResultRepo,
                    reviewRepo,
                    null,
                    null
            );

            ExecutionResult result = worker.call();

            // Verify task execution succeeded and moved to WAITING_FOR_REVIEW
            assertEquals(0, result.getExitCode());
            Task updatedTask = taskRepo.findTaskById(task.getId()).orElseThrow();
            assertEquals(TaskStatus.WAITING_FOR_REVIEW, updatedTask.getStatus());
            assertNotNull(updatedTask.getReviewId());

            // Verify that Calculator.java was modified
            String modifiedContent = Files.readString(srcDir.resolve("Calculator.java"));
            assertTrue(modifiedContent.contains("public int add(int a, int b)"));

            // Verify execution result output contains stages
            assertTrue(result.getOutput().contains("Stage: CLONING"));
            assertTrue(result.getOutput().contains("Stage: ANALYZING"));
            assertTrue(result.getOutput().contains("Stage: PLANNING"));
            assertTrue(result.getOutput().contains("Stage: IMPLEMENTING"));
            assertTrue(result.getOutput().contains("Stage: BUILDING"));
            assertTrue(result.getOutput().contains("Stage: TESTING"));
            assertTrue(result.getOutput().contains("Tracked Modifications"));
        }
    }

    @Test
    void testCodeAgentTimeoutAndRetries() {
        AIProvider timingOutProvider = new AIProvider() {
            @Override
            public String getProviderId() {
                return "timeout-provider";
            }

            @Override
            public CompletableFuture<Boolean> checkConnection() {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
                return new CompletableFuture<>(); // Never completes
            }

            @Override
            public CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer) {
                return new CompletableFuture<>(); // Never completes
            }
        };

        CodeAgent agent = new CodeAgent(timingOutProvider, new PromptContextBuilder(), null, 1, 1);
        CodeAgent.AgentExecutionResult res = agent.runStep(
                new Task("t1", "p1", null, "Test", "Desc", TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null, java.time.Instant.now(), null),
                null,
                "Instructions",
                List.of(),
                "Step prompt",
                "model",
                null,
                null
        );

        assertFalse(res.success());
        assertEquals(AiFailureClassification.TIMEOUT, res.failureClassification());
    }

    @Test
    void testCodeAgentCancellation() {
        AIProvider provider = new AIProvider() {
            @Override
            public String getProviderId() { return "p"; }
            @Override
            public CompletableFuture<Boolean> checkConnection() { return CompletableFuture.completedFuture(true); }
            @Override
            public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() { return CompletableFuture.completedFuture(List.of()); }
            @Override
            public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
                return CompletableFuture.completedFuture(new InferenceResponse("Ok", "stop", 0, 0, 0));
            }
            @Override
            public CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer) {
                return CompletableFuture.completedFuture(null);
            }
        };

        CodeAgent agent = new CodeAgent(provider, new PromptContextBuilder(), null, 0, 5);
        CodeAgent.AgentExecutionResult res = agent.runStep(
                new Task("t1", "p1", null, "Test", "Desc", TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null, java.time.Instant.now(), null),
                null,
                "Instructions",
                List.of(),
                "Prompt",
                "model",
                null,
                () -> { throw new java.util.concurrent.CancellationException("Cancelled"); }
        );

        assertFalse(res.success());
        assertEquals(AiFailureClassification.CANCELLATION, res.failureClassification());
    }

    @Test
    void testLiveOllamaIfAvailable() {
        // Probe local Ollama on standard port
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/tags"))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build();

        boolean liveOllamaAvailable = false;
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            liveOllamaAvailable = (resp.statusCode() == 200);
        } catch (Exception ignored) {
            // Not running in this environment, which is normal for sandboxed CI
        }

        if (liveOllamaAvailable) {
            OllamaProvider provider = new OllamaProvider("real-ollama", "http://localhost:11434", Duration.ofSeconds(10));
            assertTrue(provider.checkConnection().join());
            List<AIProvider.ProviderModelMetadata> models = provider.listAvailableModels().join();
            assertNotNull(models);
        }
    }
}
