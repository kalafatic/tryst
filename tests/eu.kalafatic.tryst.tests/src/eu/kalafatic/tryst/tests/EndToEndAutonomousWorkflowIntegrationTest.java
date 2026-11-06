package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.AutonomousDevelopmentService;
import eu.kalafatic.tryst.domain.service.SessionTaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.domain.tool.FileTools;
import eu.kalafatic.tryst.domain.tool.GitTools;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConversationMessageRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndAutonomousWorkflowIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("e2e.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(databaseManager).migrate();
    }

    @AfterEach
    void tearDown() {
        eu.kalafatic.tryst.domain.tool.GitTools.resetRunner();
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testEndToEndAutonomousWorkflow() throws Exception {
        Path wsRoot = tempDir.resolve("workspace");
        Files.createDirectories(wsRoot);

        // 1. Repositories & Services
        SqliteProjectRepository projRepo = new SqliteProjectRepository(databaseManager);
        SqliteTaskRepository taskRepo = new SqliteTaskRepository(databaseManager);
        SqliteWorkspaceRepository wsRepo = new SqliteWorkspaceRepository(databaseManager, projRepo, taskRepo);
        SqliteSessionRepository sessionRepo = new SqliteSessionRepository(databaseManager);
        SqliteConversationMessageRepository msgRepo = new SqliteConversationMessageRepository(databaseManager);
        SqliteExecutionResultRepository resultRepo = new SqliteExecutionResultRepository(databaseManager);

        WorkspaceService workspaceService = new WorkspaceService(wsRoot, wsRepo, projRepo, taskRepo);
        Project project = workspaceService.addProject("TestApp", wsRoot);
        workspaceService.selectProject(project.getId());

        PermissionManager permissionManager = new PermissionManager();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FileTools.WriteFileTool());
        toolRegistry.register(new FileTools.ReadFileTool());
        toolRegistry.register(new FileTools.ListDirectoryTool());

        // Setup mock git runner for GitDiffTool
        GitTools.setRunner((dir, args, out, err) -> {
            out.append("diff --git a/Feature.java b/Feature.java\n+public class Feature {}\n");
            return 0;
        });
        toolRegistry.register(new GitTools.GitDiffTool());

        EventBus eventBus = new EventBus();
        AiContextManager contextManager = new AiContextManager(2048);

        SessionTaskOrchestrator orchestrator = new SessionTaskOrchestrator(
                sessionRepo, taskRepo, msgRepo, resultRepo,
                toolRegistry, permissionManager, eventBus, workspaceService, contextManager
        );

        CodeReviewService codeReviewService = new CodeReviewService();

        AutonomousDevelopmentService devService = new AutonomousDevelopmentService(
                orchestrator, workspaceService, taskRepo, resultRepo,
                toolRegistry, permissionManager, codeReviewService, eventBus
        );

        // 2. Create Session & Task
        Session session = orchestrator.createSession(project.getId(), "E2E Feature Session");
        assertNotNull(session);
        AgentTask task = workspaceService.createTask("Create Feature", "Create a new Feature.java class");
        assertNotNull(task);
        assertEquals(ExecutionStatus.IDLE, task.getStatus());

        // 3. Mock AI client simulating planned tool commands and task completion
        AiInferenceService mockAi = new AiInferenceService() {
            @Override
            public CompletableFuture<Boolean> checkConnection() {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<List<ModelMetadata>> listModels() {
                return CompletableFuture.completedFuture(List.of(new ModelMetadata("mock", 0, "", "", "", "")));
            }

            @Override
            public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
                String responseText = """
                    I will create the requested file.
                    TOOL:write_file PARAM:path=Feature.java PARAM:content=public class Feature {}
                    Task completed successfully.
                    """;
                return CompletableFuture.completedFuture(new InferenceResponse(responseText, "stop", 50, 0, 0));
            }

            @Override
            public CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer) {
                chunkConsumer.accept("streaming chunk");
                return CompletableFuture.completedFuture(null);
            }
        };

        // 4. Execute Autonomous Workflow
        AutonomousDevelopmentService.AutonomousExecutionSummary summary =
                devService.executeAutonomousDevelopment(task, mockAi, "mock", 3, chunk -> {}).get(10, TimeUnit.SECONDS);

        // 5. Verify results
        assertNotNull(summary);
        assertTrue(summary.successful());
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());

        // Verify file written within workspace
        Path createdFile = wsRoot.resolve("Feature.java");
        assertTrue(Files.exists(createdFile));
        assertEquals("public class Feature {}", Files.readString(createdFile));

        // Verify code review executed and approved
        assertNotNull(summary.reviewReport());
        assertTrue(summary.reviewReport().approved());

        // Verify persistence survived to DB
        assertTrue(taskRepo.findById(task.getId()).isPresent());
        assertEquals(ExecutionStatus.COMPLETED, taskRepo.findById(task.getId()).get().getStatus());
        assertFalse(resultRepo.findByTaskId(task.getId()).isEmpty());
    }
}
