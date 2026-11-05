package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.intake.DefaultTaskIntakeService;
import eu.kalafatic.tryst.domain.intake.TaskIntakeService;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskInstructionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration and workflow test suite for Intelligent Task Intake,
 * preliminary validation/analysis, persistence of original requests, acceptance criteria,
 * constraints, supplementary instructions, and HTTP intake endpoints.
 */
class TaskIntakeAndVoiceWorkflowTest {

    private SqliteDatabaseManager dbManager;
    private SqliteTaskRepository taskRepository;
    private SqliteTaskInstructionRepository instructionRepository;
    private SqliteProjectRepository projectRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private WorkspaceService workspaceService;
    private TaskOrchestrator orchestrator;
    private TaskIntakeService intakeService;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private String baseUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("intake_test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();

        SchemaMigrationService migration = new SchemaMigrationService(dbManager);
        migration.migrate();

        taskRepository = new SqliteTaskRepository(dbManager);
        instructionRepository = new SqliteTaskInstructionRepository(dbManager);
        projectRepository = new SqliteProjectRepository(dbManager);
        workspaceRepository = new SqliteWorkspaceRepository(dbManager, projectRepository, taskRepository);

        Path wsRoot = tempDir.resolve("workspace");
        workspaceService = new WorkspaceService(wsRoot, workspaceRepository, projectRepository, taskRepository);

        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, null);
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                null, new PermissionManager(), null, null, null, null, null, null, 2
        );
        orchestrator.setTaskInstructionRepository(instructionRepository);

        intakeService = new DefaultTaskIntakeService(
                orchestrator, taskRepository, instructionRepository, projectRepository,
                workspaceService, null, null, new PermissionManager()
        );

        httpServer = new TrystHttpServer("127.0.0.1", 0, workspaceService);
        httpServer.setTaskOrchestrator(orchestrator);
        httpServer.setTaskIntakeService(intakeService);
        httpServer.start();

        baseUrl = "http://127.0.0.1:" + httpServer.getPort();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void testPreliminaryAnalysisDetectsMissingInfoAndClassifiesBugFix() {
        TaskIntakeService.TaskIntakeRequest request = new TaskIntakeService.TaskIntakeRequest(
                null,
                "https://github.com/kalafatic/tryst.git",
                "main",
                null, // missing title
                "Fix NullPointerException in UserSession authentication token validation when session is expired",
                null, // missing priority
                null, // missing expected outcome
                List.of(), // missing acceptance criteria
                List.of("Preserve public API"),
                List.of()
        );

        TaskIntakeService.TaskIntakeAnalysis analysis = intakeService.analyzeAndValidate(request, "test-user", null);

        assertTrue(analysis.valid());
        assertEquals(TaskIntakeService.RequestClassification.BUG_FIX, analysis.classification());
        assertNotNull(analysis.suggestedTitle());
        assertTrue(analysis.suggestedTitle().contains("NullPointerException"));
        assertEquals(TaskPriority.NORMAL, analysis.suggestedPriority());
        assertFalse(analysis.missingInformation().isEmpty());
        assertTrue(analysis.missingInformation().stream().anyMatch(m -> m.contains("acceptance criteria")));
    }

    @Test
    void testPreliminaryAnalysisRejectsEmptyDescription() {
        TaskIntakeService.TaskIntakeRequest request = new TaskIntakeService.TaskIntakeRequest(
                null,
                "https://github.com/kalafatic/tryst.git",
                "main",
                "Some Title",
                "   ",
                TaskPriority.NORMAL,
                null,
                List.of(),
                List.of(),
                List.of()
        );

        TaskIntakeService.TaskIntakeAnalysis analysis = intakeService.analyzeAndValidate(request, "test-user", null);

        assertFalse(analysis.valid());
        assertFalse(analysis.validationErrors().isEmpty());
        assertTrue(analysis.validationErrors().get(0).contains("description is required"));
    }

    @Test
    void testSubmitIntakePersistsOriginalRequestAndInstructions() {
        TaskIntakeService.TaskIntakeRequest request = new TaskIntakeService.TaskIntakeRequest(
                null,
                "https://github.com/kalafatic/tryst.git",
                "feature/voice-intake",
                "Support Dictation in Browser",
                "Dictate a software request using webkitSpeechRecognition and submit directly",
                TaskPriority.HIGH,
                "User can speak requests and edit text before submission",
                List.of("SpeechRecognition fallback to keyboard", "Transcript editing enabled"),
                List.of("Zero external frontend JS frameworks"),
                List.of("Include microphone status indicator")
        );

        TaskIntakeService.TaskIntakeResult result = intakeService.submitIntake(request, "alice", null);

        assertNotNull(result.task());
        assertNotNull(result.task().getId());
        assertEquals("Support Dictation in Browser", result.task().getTitle());
        assertEquals(TaskPriority.HIGH, result.task().getPriority());
        assertEquals(TaskStatus.QUEUED, result.task().getStatus());

        // Verify instructions persisted in SQLite
        List<TaskInstruction> instructions = instructionRepository.findByTaskId(result.task().getId());
        assertEquals(6, instructions.size()); // 1 original + 1 outcome + 2 criteria + 1 constraint + 1 supplementary

        assertTrue(instructions.stream().anyMatch(i -> i.getInstructionType() == TaskInstruction.InstructionType.ORIGINAL_REQUEST));
        assertTrue(instructions.stream().anyMatch(i -> i.getInstructionType() == TaskInstruction.InstructionType.EXPECTED_OUTCOME));
        assertTrue(instructions.stream().anyMatch(i -> i.getInstructionType() == TaskInstruction.InstructionType.ACCEPTANCE_CRITERIA));
        assertTrue(instructions.stream().anyMatch(i -> i.getInstructionType() == TaskInstruction.InstructionType.CONSTRAINT));
        assertTrue(instructions.stream().anyMatch(i -> i.getInstructionType() == TaskInstruction.InstructionType.SUPPLEMENTARY));
    }

    @Test
    void testSupplementaryInstructionsCanBeAppendedToActiveTask() {
        TaskIntakeService.TaskIntakeRequest request = new TaskIntakeService.TaskIntakeRequest(
                null, null, null, "Refactor Logging", "Refactor logging calls across domain services",
                TaskPriority.NORMAL, "Clean structured logs", List.of(), List.of(), List.of()
        );

        TaskIntakeService.TaskIntakeResult result = intakeService.submitIntake(request, "bob", null);
        String taskId = result.task().getId();

        TaskInstruction supp = intakeService.addSupplementaryInstruction(
                taskId,
                "Ensure correlationId is properly formatted as [corr:<id>]",
                TaskInstruction.InstructionType.SUPPLEMENTARY,
                "bob",
                null
        );

        assertNotNull(supp.getId());
        assertEquals(taskId, supp.getTaskId());

        List<TaskInstruction> all = intakeService.getInstructionsForTask(taskId, "bob", null);
        assertTrue(all.stream().anyMatch(i -> i.getContent().contains("correlationId")));
    }

    @Test
    void testHttpIntakeAnalyzeAndSubmitEndpoints() throws Exception {
        // 1. Analyze endpoint
        String analyzeJson = """
            {
                "repositoryUrl": "https://github.com/kalafatic/tryst.git",
                "branch": "main",
                "description": "Urgent security vulnerability hotfix for session token deserialization",
                "acceptanceCriteria": ["Validate token signature", "Reject spoofed tokens"]
            }
        """;

        HttpRequest analyzeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/intake/analyze"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(analyzeJson))
                .build();

        HttpResponse<String> analyzeRes = httpClient.send(analyzeReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, analyzeRes.statusCode());
        assertTrue(analyzeRes.body().contains("\"valid\": true"));
        assertTrue(analyzeRes.body().contains("\"classification\": \"SECURITY\"") || analyzeRes.body().contains("\"classification\": \"BUG_FIX\""));
        assertTrue(analyzeRes.body().contains("\"suggestedPriority\": \"URGENT\""));

        // 2. Submit endpoint
        String submitJson = """
            {
                "repositoryUrl": "https://github.com/kalafatic/tryst.git",
                "branch": "main",
                "title": "Fix Security Vulnerability in Session Deserialization",
                "description": "Urgent security vulnerability hotfix for session token deserialization",
                "priority": "URGENT",
                "expectedOutcome": "Malicious serialized tokens are rejected immediately with 403",
                "acceptanceCriteria": ["Validate token signature", "Reject spoofed tokens"],
                "constraints": ["Zero regressions on valid bearer tokens"]
            }
        """;

        HttpRequest submitReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/intake/submit"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(submitJson))
                .build();

        HttpResponse<String> submitRes = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, submitRes.statusCode());
        assertTrue(submitRes.body().contains("\"taskId\""));
        assertTrue(submitRes.body().contains("\"persistedInstructionsCount\": 5"));

        // Extract taskId
        String taskId = submitRes.body().split("\"taskId\":\\s*\"")[1].split("\"")[0];

        // 3. GET /api/tasks/{id}/instructions
        HttpRequest getInstReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId + "/instructions"))
                .GET()
                .build();

        HttpResponse<String> getInstRes = httpClient.send(getInstReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getInstRes.statusCode());
        assertTrue(getInstRes.body().contains("ORIGINAL_REQUEST"));
        assertTrue(getInstRes.body().contains("EXPECTED_OUTCOME"));
        assertTrue(getInstRes.body().contains("ACCEPTANCE_CRITERIA"));
        assertTrue(getInstRes.body().contains("CONSTRAINT"));

        // 4. POST /api/tasks/{id}/instructions
        String addInstJson = """
            {
                "type": "SUPPLEMENTARY",
                "content": "Add integration test case with forged HMAC payload"
            }
        """;

        HttpRequest addInstReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId + "/instructions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(addInstJson))
                .build();

        HttpResponse<String> addInstRes = httpClient.send(addInstReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, addInstRes.statusCode());
        assertTrue(addInstRes.body().contains("forged HMAC payload"));
    }
}
