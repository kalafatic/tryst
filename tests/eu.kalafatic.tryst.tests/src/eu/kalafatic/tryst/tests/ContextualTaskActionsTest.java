package eu.kalafatic.tryst.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.contextual.ContextualAction;
import eu.kalafatic.tryst.domain.contextual.ContextualActionIds;
import eu.kalafatic.tryst.domain.contextual.ContextualActionRegistry;
import eu.kalafatic.tryst.domain.contextual.ContextualSelection;
import eu.kalafatic.tryst.domain.contextual.DefaultContextualActionRegistry;
import eu.kalafatic.tryst.domain.contextual.TrystContextualActions;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;

/**
 * TRYST contextual task actions (Darwin Family Contextual Action Contract v1.0):
 * state-aware availability and real domain execution through the single registry —
 * the same operations the RCP navigator menu and the web surface execute.
 */
class ContextualTaskActionsTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private TaskOrchestrator orchestrator;
    private ContextualActionRegistry registry;

    @BeforeEach
    void setUp() {
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("ctx_test.db"));
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository,
                new EventBus());
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);
        orchestrator = new TaskOrchestrator(taskQueue, taskRepository, lifecycleManager,
                null, projectRepository, null, null, null, null, null, null, null, null, 2);
        TestAiFakes.configureModelSelection(orchestrator, "test-model");

        registry = new DefaultContextualActionRegistry();
        TrystContextualActions.install(registry, orchestrator, taskRepository, null,
                projectRepository);
    }

    @AfterEach
    void tearDown() {
        orchestrator.shutdown();
        databaseManager.close();
    }

    private Task createTask(String title) {
        return orchestrator.submitTask(null, null, title, "ctx-test-description", null,
                "dev-user", null);
    }

    private ContextualSelection selectionFor(Task task) {
        return ContextualSelection.builder("TRYST", "RCP", "task")
                .object(task.getId(), task.getTitle())
                .state("status", task.getStatus().name())
                .permissions(java.util.Set.of())
                .build();
    }

    @Test
    void cancelEnabledForActiveTaskAndChangesDomainState() {
        Task task = createTask("ctx task to cancel");
        ContextualAction cancel = registry.resolve(ContextualActionIds.SESSION_CANCEL,
                selectionFor(task)).orElseThrow();
        assertTrue(cancel.enabled(), "active task must allow cancel");

        assertTrue(registry.execute(ContextualActionIds.SESSION_CANCEL,
                selectionFor(task)).ok());
        assertEquals(TaskStatus.CANCELLED,
                taskRepository.findTaskById(task.getId()).orElseThrow().getStatus(),
                "execution must change the real domain state");
    }

    @Test
    void resumeDisabledForNonPausedTask() {
        Task task = createTask("ctx active task");
        ContextualAction resume = registry.resolve(ContextualActionIds.SESSION_RESUME,
                selectionFor(task)).orElseThrow();
        assertFalse(resume.enabled());
        assertTrue(resume.disabledReason().contains("not paused"));
    }

    @Test
    void pauseOnlyAvailableForActiveTasks() {
        Task task = createTask("ctx pause task");
        TaskStatus status = taskRepository.findTaskById(task.getId()).orElseThrow()
                .getStatus();
        ContextualAction pause = registry.resolve(ContextualActionIds.SESSION_PAUSE,
                selectionFor(task)).orElseThrow();
        assertEquals(status.isActive(), pause.enabled(),
                "pause availability must match the live task state");
        assertFalse(registry.resolve(ContextualActionIds.SESSION_RESUME,
                selectionFor(task)).orElseThrow().enabled(),
                "a non-paused task can never be resumed");
    }

    @Test
    void archiveDisabledForNonTerminalAndEnabledAfterCancel() {
        Task task = createTask("ctx archive task");
        ContextualAction archiveBefore = registry.resolve(
                ContextualActionIds.SESSION_ARCHIVE, selectionFor(task)).orElseThrow();
        assertFalse(archiveBefore.enabled(), "active task cannot be archived");

        registry.execute(ContextualActionIds.SESSION_CANCEL, selectionFor(task));
        Task cancelled = taskRepository.findTaskById(task.getId()).orElseThrow();
        ContextualAction archiveAfter = registry.resolve(
                ContextualActionIds.SESSION_ARCHIVE, selectionFor(cancelled))
                .orElseThrow();
        assertTrue(archiveAfter.enabled());
        assertTrue(registry.execute(ContextualActionIds.SESSION_ARCHIVE,
                selectionFor(cancelled)).ok());
        assertEquals(TaskStatus.ARCHIVED,
                taskRepository.findTaskById(task.getId()).orElseThrow().getStatus());
    }

    @Test
    void deleteRemovesTaskFromRepository() {
        Task task = createTask("ctx delete task");
        assertTrue(registry.execute(ContextualActionIds.SESSION_DELETE,
                selectionFor(task)).ok());
        assertTrue(taskRepository.findTaskById(task.getId()).isEmpty(),
                "delete must remove the persisted task");
    }

    @Test
    void unknownTaskYieldsTargetNotFound() {
        var result = registry.execute(ContextualActionIds.SESSION_DETAILS,
                ContextualSelection.builder("TRYST", "RCP", "task")
                        .object("unknown-id", "unknown").build());
        assertFalse(result.ok());
        assertEquals("TARGET_NOT_FOUND", result.errorCode());
    }
}
