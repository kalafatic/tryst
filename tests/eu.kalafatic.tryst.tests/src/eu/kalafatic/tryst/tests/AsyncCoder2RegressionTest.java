package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiFailureClassification;
import eu.kalafatic.tryst.domain.ai.CodeAgent;
import eu.kalafatic.tryst.domain.intake.DefaultTaskIntakeService;
import eu.kalafatic.tryst.domain.intake.TaskIntakeService;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.TaskRecoveryService;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConversationMessageRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskInstructionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asynchronous Coder 2 regression tests: honest AI failure, agent-type persistence,
 * execution-result binding, repository-mirror persistence, project configuration
 * persistence, session continuation with workspace reuse, pause/resume and
 * recovery retry.
 */
class AsyncCoder2RegressionTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private SqliteSessionRepository sessionRepository;
    private SqliteConversationMessageRepository conversationMessageRepository;
    private SqliteTaskInstructionRepository taskInstructionRepository;
    private SqliteExecutionResultRepository executionResultRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;
    private TaskOrchestrator orchestrator;
    private WorkspaceService workspaceService;
    private PermissionManager permissionManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("ac2.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);
        sessionRepository = new SqliteSessionRepository(databaseManager);
        conversationMessageRepository = new SqliteConversationMessageRepository(databaseManager);
        taskInstructionRepository = new SqliteTaskInstructionRepository(databaseManager);
        executionResultRepository = new SqliteExecutionResultRepository(databaseManager);
        permissionManager = new PermissionManager();
        lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);
        workspaceService = new WorkspaceService(tempDir.resolve("ws"), workspaceRepository,
                projectRepository, taskRepository, permissionManager, null);
    }

    private TaskOrchestrator newOrchestrator(int concurrency) {
        eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository codeReviewRepository =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository(databaseManager);
        TaskOrchestrator o = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                new ToolRegistry(), permissionManager, TestAiFakes.codingAi(), null, conversationMessageRepository,
                executionResultRepository, codeReviewRepository,
                new eu.kalafatic.tryst.domain.review.CodeReviewService(), concurrency
        );
        TestAiFakes.configureModelSelection(o, "test-model");
        o.setTaskRecoveryService(new TaskRecoveryService(taskRepository, lifecycleManager));
        o.setTaskInstructionRepository(taskInstructionRepository);
        return o;
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    // ------------------------------------------------------------------
    // 1. Honest failure: no AI provider must never fabricate changes
    // ------------------------------------------------------------------

    @Test
    void testCodeAgentWithoutProviderFailsHonestly() {
        CodeAgent agent = new CodeAgent(null, null, null);
        Workspace ws = new Workspace("ws-honest", tempDir.resolve("ws-honest"), WorkspaceStatus.READY,
                java.time.Instant.now(), java.time.Instant.now());
        Task task = new Task("t-honest", null, null, "Test", "desc", TaskStatus.IMPLEMENTING,
                TaskPriority.NORMAL, null, null, null, null, 0, null, java.time.Instant.now(), null);

        CodeAgent.AgentExecutionResult res = agent.runStep(task, ws, "sys", java.util.List.of(),
                "Create important file", "model", chunk -> {}, () -> {});

        assertFalse(res.success(), "Code agent without a provider must never report success");
        assertEquals(AiFailureClassification.MODEL_UNAVAILABLE, res.failureClassification());
        assertFalse(Files.exists(tempDir.resolve("ws-honest").resolve("important.txt")),
                "No file may be fabricated when the AI provider is unavailable");
    }

    // ------------------------------------------------------------------
    // 2. Agent type persists across save/reload
    // ------------------------------------------------------------------

    @Test
    void testAgentTypePersistenceRoundTrip() {
        projectRepository.save(new Project("p1", "user-1", "AgentType Project", tempDir.resolve("p1"), true));
        Task task = new Task("t-agenttype", "p1", null, "Agent Type Task", "desc",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, "TRYST", null, null, 0, null,
                java.time.Instant.now(), null);
        taskRepository.saveTask(task);

        Task reloaded = taskRepository.findTaskById("t-agenttype").orElseThrow();
        assertEquals("TRYST", reloaded.getAgentType(), "agent_type must survive persistence");
    }

    // ------------------------------------------------------------------
    // 3. Execution result id is persisted on the task after execution
    // ------------------------------------------------------------------

    @Test
    void testExecutionResultIdPersistedAfterExecution() throws Exception {
        projectRepository.save(new Project("p-exec", "user-1", "Exec Project", tempDir.resolve("p-exec"), true));
        orchestrator = newOrchestrator(1);
        orchestrator.start();

        Task submitted = orchestrator.submitTask("p-exec", null, "Exec Task", "Description",
                TaskPriority.HIGH, "user-1", null);

        // Wait until the task finishes execution (WAITING_FOR_REVIEW is reached before
        // the late execution_result_id binding is persisted, so poll for the binding)
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(submitted.getId()).orElseThrow();
            if ((t.getStatus() == TaskStatus.WAITING_FOR_REVIEW || t.getStatus() == TaskStatus.FAILED)
                    && t.getExecutionResultId() != null) {
                break;
            }
            Thread.sleep(100);
        }

        Task reloaded = taskRepository.findTaskById(submitted.getId()).orElseThrow();
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, reloaded.getStatus());
        assertNotNull(reloaded.getExecutionResultId(), "execution_result_id must be persisted on the task");
        assertTrue(executionResultRepository.findLatestByTaskId(submitted.getId()).isPresent(),
                "ExecutionResult row must exist");
    }

    // ------------------------------------------------------------------
    // 4. Repository mirror persistence matches the V14 schema
    // ------------------------------------------------------------------

    @Test
    void testRepositoryMirrorPersistenceUsesSchemaColumns() throws Exception {
        Path mirrorsDir = tempDir.resolve("mirrors");
        RepositoryPoolManager pool = new RepositoryPoolManager(mirrorsDir,
                new eu.kalafatic.tryst.infrastructure.vcs.GitService());
        pool.setDatabaseManager(databaseManager);

        // Pre-create the mirror directory structure so ensureMirror records its metadata
        // (the mirror update itself is a no-op warning for a non-git fixture directory)
        Path mirrorDir = mirrorsDir.resolve("file____tmp_repo_mirror.git");
        Files.createDirectories(mirrorDir);
        Files.writeString(mirrorDir.resolve("config"), "[core]\n\trepositoryformatversion = 0\n");
        pool.ensureMirror("file:///tmp/repo_mirror", null);

        synchronized (databaseManager.getLock()) {
            try (var stmt = databaseManager.getConnection().createStatement();
                 var rs = stmt.executeQuery("SELECT remote_url, mirror_path, last_synced_at, mirror_fingerprint FROM repository_mirrors")) {
                assertTrue(rs.next(), "Mirror metadata must be persisted");
                assertEquals("file:///tmp/repo_mirror", rs.getString("remote_url"));
                assertNotNull(rs.getString("mirror_path"));
                assertNotNull(rs.getString("last_synced_at"));
                assertNotNull(rs.getString("mirror_fingerprint"));
            }
        }

        assertFalse(pool.listMirrorStatuses().isEmpty(), "Mirror status listing must include the persisted mirror");
    }

    // ------------------------------------------------------------------
    // 5. Project configuration persistence
    // ------------------------------------------------------------------

    @Test
    void testProjectConfigurationPersistenceRoundTrip() {
        Project p = new Project("p-config", "user-1", "Config Project", tempDir.resolve("p-config"), true);
        p.setRepositoryUrl("https://example.com/org/repo.git");
        p.setDefaultBranch("develop");
        p.setBuildCommand("mvn -q compile");
        p.setTestCommand("mvn -q test");
        p.setInstructions("Preserve public APIs");
        projectRepository.save(p);

        Project reloaded = projectRepository.findById("p-config").orElseThrow();
        assertEquals("https://example.com/org/repo.git", reloaded.getRepositoryUrl());
        assertEquals("develop", reloaded.getDefaultBranch());
        assertEquals("mvn -q compile", reloaded.getBuildCommand());
        assertEquals("mvn -q test", reloaded.getTestCommand());
        assertEquals("Preserve public APIs", reloaded.getInstructions());
    }

    // ------------------------------------------------------------------
    // 6. Intake submission creates a persistent coding session
    // ------------------------------------------------------------------

    @Test
    void testIntakeSubmissionCreatesPersistentSession() {
        orchestrator = newOrchestrator(1);
        DefaultTaskIntakeService intake = new DefaultTaskIntakeService(
                orchestrator, taskRepository, taskInstructionRepository, projectRepository,
                workspaceService, null, null, permissionManager,
                sessionRepository, conversationMessageRepository
        );

        TaskIntakeService.TaskIntakeRequest req = new TaskIntakeService.TaskIntakeRequest(
                null, tempDir.resolve("repo").toString(), "main", "Session Test", "Implement a session-bound change",
                TaskPriority.NORMAL, "All tests pass", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), TaskIntakeService.ExecutionOptions.defaults()
        );
        TaskIntakeService.TaskIntakeResult res = intake.submitIntake(req, "user-1", null);

        assertNotNull(res.task().getSessionId(), "Intake tasks must be bound to a coding session");
        Optional<Session> sess = sessionRepository.findById(res.task().getSessionId());
        assertTrue(sess.isPresent(), "Session must be persisted");
        assertFalse(conversationMessageRepository.findBySessionId(res.task().getSessionId()).isEmpty(),
                "Original request must be recorded in the session conversation");

        Task reloaded = taskRepository.findTaskById(res.task().getId()).orElseThrow();
        assertEquals(res.task().getSessionId(), reloaded.getSessionId(), "Session id must survive reload");
    }

    // ------------------------------------------------------------------
    // 7. Pause suspends an active task at a stage boundary; resume continues
    // ------------------------------------------------------------------

    @Test
    void testPauseAndResumeOfActiveTask() throws Exception {
        projectRepository.save(new Project("p-pause", "user-1", "Pause Project", tempDir.resolve("p-pause"), true));

        // A slow fake AI keeps the task in IMPLEMENTING long enough to pause deterministically
        eu.kalafatic.tryst.domain.ai.AiInferenceService slowAi = new eu.kalafatic.tryst.domain.ai.AiInferenceService() {
            @Override
            public java.util.concurrent.CompletableFuture<Boolean> checkConnection() {
                return java.util.concurrent.CompletableFuture.completedFuture(true);
            }

            @Override
            public java.util.concurrent.CompletableFuture<java.util.List<ModelMetadata>> listModels() {
                return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
            }

            @Override
            public java.util.concurrent.CompletableFuture<InferenceResponse> generateResponse(InferenceRequest req) {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new InferenceResponse(TestAiFakes.FAKE_FILE_DIRECTIVE, "stop", 8000, 1, 1));
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> streamResponse(InferenceRequest req, java.util.function.Consumer<String> chunkConsumer) {
                try {
                    Thread.sleep(8000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (chunkConsumer != null) {
                    chunkConsumer.accept(TestAiFakes.FAKE_FILE_DIRECTIVE);
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };

        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                new ToolRegistry(), permissionManager, slowAi, null, null, null,
                null, null, 1
        );
        TestAiFakes.configureModelSelection(orchestrator, "test-model");
        orchestrator.setTaskRecoveryService(new TaskRecoveryService(taskRepository, lifecycleManager));
        orchestrator.setTaskInstructionRepository(taskInstructionRepository);
        orchestrator.start();

        Task submitted = orchestrator.submitTask("p-pause", null, "Pause Task", "Description",
                TaskPriority.HIGH, "user-1", null);

        // Wait until the task is actively executing (past CLONING)
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(submitted.getId()).orElseThrow();
            if (t.getStatus().isActive() && t.getStatus() != TaskStatus.CLAIMED) {
                break;
            }
            if (t.getStatus().isTerminal()) {
                fail("Task reached terminal state before pause: " + t.getStatus());
            }
            Thread.sleep(50);
        }

        assertTrue(orchestrator.pauseTask(submitted.getId(), "user-1"), "Pause must be accepted for active task");

        // The worker suspends at the next stage boundary and transitions to PAUSED
        deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(submitted.getId()).orElseThrow();
            if (t.getStatus() == TaskStatus.PAUSED) {
                break;
            }
            if (t.getStatus() == TaskStatus.CANCELLED) {
                fail("Pause must not cancel the task");
            }
            Thread.sleep(100);
        }
        assertEquals(TaskStatus.PAUSED, taskRepository.findTaskById(submitted.getId()).orElseThrow().getStatus(),
                "Active task must transition to PAUSED at a stage boundary");

        // Resume: the same worker continues to completion
        assertTrue(orchestrator.resumeTask(submitted.getId(), "user-1"), "Resume must be accepted");
        deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(submitted.getId()).orElseThrow();
            if (t.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
                return;
            }
            if (t.getStatus() == TaskStatus.CANCELLED) {
                fail("Resumed task must not be cancelled");
            }
            Thread.sleep(100);
        }
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, taskRepository.findTaskById(submitted.getId()).orElseThrow().getStatus(),
                "Resumed task must complete to WAITING_FOR_REVIEW");
    }

    // ------------------------------------------------------------------
    // 8. Recovery retry re-queues a RECOVERY_REQUIRED task and executes it
    // ------------------------------------------------------------------

    @Test
    void testRecoveryRetryRequeuesAndExecutes() throws Exception {
        projectRepository.save(new Project("p-rec", "user-1", "Recovery Project", tempDir.resolve("p-rec"), true));
        orchestrator = newOrchestrator(1);

        // Simulate a task interrupted in IMPLEMENTING (parked by reconciliation)
        Task crashed = new Task("t-rec", "p-rec", null, "Crashed Task", "desc",
                TaskStatus.RECOVERY_REQUIRED, TaskPriority.HIGH, null, null, null, null, 0, null,
                java.time.Instant.now(), null);
        taskRepository.saveTask(crashed);

        orchestrator.start();
        assertTrue(orchestrator.retryRecoveredTask("t-rec", "user-1"), "Retry must re-queue the recovered task");

        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById("t-rec").orElseThrow();
            if (t.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
                return;
            }
            if (t.getStatus() == TaskStatus.FAILED) {
                fail("Retried task failed: " + t.getFailureReason());
            }
            Thread.sleep(100);
        }
        fail("Retried task must reach WAITING_FOR_REVIEW; stuck in "
                + taskRepository.findTaskById("t-rec").orElseThrow().getStatus());
    }
}
