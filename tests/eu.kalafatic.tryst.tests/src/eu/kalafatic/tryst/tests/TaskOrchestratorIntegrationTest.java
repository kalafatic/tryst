package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.service.TaskRecoveryService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class TaskOrchestratorIntegrationTest {

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;
    private TaskOrchestrator orchestrator;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("orch_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        orchestrator = new TaskOrchestrator(
                taskQueue,
                taskRepository,
                lifecycleManager,
                null,
                projectRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                2
        );
    }

    @AfterEach
    public void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    public void testTaskSubmissionAndAsynchronousProgression(@TempDir Path tempDir) throws InterruptedException {
        projectRepository.save(new Project("proj-1", "user-1", "Project 1", tempDir.resolve("proj-1"), true));

        Task submitted = orchestrator.submitTask(
                "proj-1", null, "Build Feature X", "Implement core logic",
                TaskPriority.HIGH, "user-1", "deepseek-coder:6.7b"
        );

        assertNotNull(submitted);
        assertEquals(TaskStatus.QUEUED, submitted.getStatus());
        assertEquals(1, orchestrator.getQueueDepth());

        // Start orchestrator to process queue
        orchestrator.start();

        // Wait until task reaches WAITING_FOR_REVIEW
        long deadline = System.currentTimeMillis() + 5000;
        Task completed = null;
        while (System.currentTimeMillis() < deadline) {
            Optional<Task> opt = taskRepository.findTaskById(submitted.getId());
            if (opt.isPresent() && (opt.get().getStatus() == TaskStatus.WAITING_FOR_REVIEW || opt.get().getStatus() == TaskStatus.COMPLETED)) {
                completed = opt.get();
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completed, "Task should reach WAITING_FOR_REVIEW within deadline");
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, completed.getStatus());

        // Verify persisted events
        List<TaskEvent> events = taskRepository.findEventsByTaskId(submitted.getId());
        assertFalse(events.isEmpty(), "Events should be recorded in task_events table");
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.CLONING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.ANALYZING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.PLANNING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.IMPLEMENTING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.BUILDING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.TESTING));
        assertTrue(events.stream().anyMatch(e -> e.getStatus() == TaskStatus.WAITING_FOR_REVIEW));
    }

    @Test
    public void testTaskCancellation(@TempDir Path tempDir) {
        projectRepository.save(new Project("proj-2", "user-2", "Project 2", tempDir.resolve("proj-2"), true));

        Task submitted = orchestrator.submitTask(
                "proj-2", null, "Cancel Me", "To be cancelled",
                TaskPriority.NORMAL, "user-2", null
        );

        boolean cancelled = orchestrator.cancelTask(submitted.getId(), "User requested cancellation");
        assertTrue(cancelled);

        Task reloaded = taskRepository.findTaskById(submitted.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, reloaded.getStatus());
        assertTrue(reloaded.getStatus().isTerminal());
    }

    @Test
    public void testRestartRecoveryReconciliation(@TempDir Path tempDir) {
        projectRepository.save(new Project("proj-rec", "user-rec", "Project Rec", tempDir.resolve("proj-rec"), true));

        // Simulate interrupted task left in IMPLEMENTING
        Task activeTask = new Task("Interrupted", "Server crashed");
        activeTask.transitionTo(TaskStatus.QUEUED);
        activeTask.transitionTo(TaskStatus.CLAIMED);
        activeTask.transitionTo(TaskStatus.IMPLEMENTING);
        taskRepository.saveTask(activeTask);

        // WAITING_FOR_REVIEW task should be preserved
        Task reviewTask = new Task("In Review", "Awaiting human review");
        reviewTask.transitionTo(TaskStatus.QUEUED);
        reviewTask.transitionTo(TaskStatus.CLAIMED);
        reviewTask.transitionTo(TaskStatus.IMPLEMENTING);
        reviewTask.transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        taskRepository.saveTask(reviewTask);

        TaskRecoveryService recoveryService = new TaskRecoveryService(taskRepository, lifecycleManager);
        List<Task> recovered = recoveryService.reconcileInterruptedTasks();

        assertEquals(1, recovered.size());
        assertEquals(activeTask.getId(), recovered.get(0).getId());

        Task reloadedActive = taskRepository.findTaskById(activeTask.getId()).orElseThrow();
        assertEquals(TaskStatus.RECOVERY_REQUIRED, reloadedActive.getStatus());

        Task reloadedReview = taskRepository.findTaskById(reviewTask.getId()).orElseThrow();
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, reloadedReview.getStatus(),
                "WAITING_FOR_REVIEW tasks must be preserved across restarts");

        // Retry recovered task
        boolean retried = recoveryService.retryRecoveredTask(activeTask.getId());
        assertTrue(retried);
        Task retriedTask = taskRepository.findTaskById(activeTask.getId()).orElseThrow();
        assertEquals(TaskStatus.QUEUED, retriedTask.getStatus());
    }
}
