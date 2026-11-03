package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TaskQueueConcurrencyTest {

    private SqliteDatabaseManager databaseManager;
    private SqliteTaskRepository taskRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("queue_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        taskRepository = new SqliteTaskRepository(databaseManager);
        lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);
    }

    @AfterEach
    public void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    public void testPriorityOrderingInQueue() {
        Task lowTask = new Task("Low", "Low priority");
        lowTask.setPriority(TaskPriority.LOW);
        taskRepository.saveTask(lowTask);
        taskQueue.enqueue(lowTask);

        Task urgentTask = new Task("Urgent", "Urgent priority");
        urgentTask.setPriority(TaskPriority.URGENT);
        taskRepository.saveTask(urgentTask);
        taskQueue.enqueue(urgentTask);

        Task normalTask = new Task("Normal", "Normal priority");
        normalTask.setPriority(TaskPriority.NORMAL);
        taskRepository.saveTask(normalTask);
        taskQueue.enqueue(normalTask);

        // Claiming next task must return URGENT first
        Optional<Task> firstClaim = taskQueue.claimNext("worker-1");
        assertTrue(firstClaim.isPresent());
        assertEquals("Urgent", firstClaim.get().getTitle());
        assertEquals(TaskPriority.URGENT, firstClaim.get().getPriority());

        // Then NORMAL
        Optional<Task> secondClaim = taskQueue.claimNext("worker-1");
        assertTrue(secondClaim.isPresent());
        assertEquals("Normal", secondClaim.get().getTitle());

        // Then LOW
        Optional<Task> thirdClaim = taskQueue.claimNext("worker-1");
        assertTrue(thirdClaim.isPresent());
        assertEquals("Low", thirdClaim.get().getTitle());

        // Empty afterwards
        assertTrue(taskQueue.claimNext("worker-1").isEmpty());
    }

    @Test
    public void testConcurrentClaimingNoDuplicateExecution() throws InterruptedException, ExecutionException {
        int taskCount = 20;
        for (int i = 0; i < taskCount; i++) {
            Task t = new Task("Task-" + i, "Concurrent task test");
            taskRepository.saveTask(t);
            taskQueue.enqueue(t);
        }

        assertEquals(taskCount, taskQueue.getQueueDepth());

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        Set<String> claimedTaskIds = ConcurrentHashMap.newKeySet();
        AtomicInteger totalClaims = new AtomicInteger(0);

        List<Callable<Void>> callers = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final String workerId = "concurrent-worker-" + i;
            callers.add(() -> {
                startLatch.await();
                while (true) {
                    Optional<Task> claimed = taskQueue.claimNext(workerId);
                    if (claimed.isEmpty()) {
                        break;
                    }
                    totalClaims.incrementAndGet();
                    boolean added = claimedTaskIds.add(claimed.get().getId());
                    assertTrue(added, "Duplicate claim detected for task: " + claimed.get().getId());
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> c : callers) {
            futures.add(executor.submit(c));
        }

        startLatch.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertEquals(taskCount, totalClaims.get(), "All tasks must be claimed exactly once");
        assertEquals(taskCount, claimedTaskIds.size(), "No duplicated task claims across concurrent threads");
        assertEquals(0, taskQueue.getQueueDepth(), "Queue must be empty after all claims");
    }
}
