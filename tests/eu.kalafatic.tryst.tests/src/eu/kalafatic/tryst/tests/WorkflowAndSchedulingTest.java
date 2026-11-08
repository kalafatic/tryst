package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.schedule.ScheduleType;
import eu.kalafatic.tryst.domain.schedule.TaskSchedule;
import eu.kalafatic.tryst.domain.service.RepositoryLockManager;
import eu.kalafatic.tryst.domain.service.ResourceGovernor;
import eu.kalafatic.tryst.domain.workflow.*;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskScheduleRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowDefinitionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for Workflow Designer, Task Scheduling,
 * Repository Concurrency Locking, and Resource Throttling.
 */
public class WorkflowAndSchedulingTest {

    private Path tempDbDir;
    private SqliteDatabaseManager databaseManager;
    private SqliteWorkflowDefinitionRepository definitionRepo;
    private SqliteWorkflowExecutionRepository executionRepo;
    private SqliteTaskScheduleRepository scheduleRepo;

    @BeforeEach
    public void setUp() throws IOException {
        tempDbDir = Files.createTempDirectory("tryst-wf-test-");
        Path dbPath = tempDbDir.resolve("test-tryst-wf.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(databaseManager).migrate();

        definitionRepo = new SqliteWorkflowDefinitionRepository(databaseManager);
        executionRepo = new SqliteWorkflowExecutionRepository(databaseManager);
        scheduleRepo = new SqliteTaskScheduleRepository(databaseManager);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (tempDbDir != null && Files.exists(tempDbDir)) {
            try (var walk = Files.walk(tempDbDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    public void testWorkflowDefinitionPersistence() {
        String defId = UUID.randomUUID().toString();
        WorkflowNode start = new WorkflowNode("start", "Start", NodeType.WORKFLOW_START, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());
        WorkflowNode coding = new WorkflowNode("coding", "Code", NodeType.AI_CODING, "", "Implement fix", "", "HIGH", null, 600, 0, "", 100, 0, Map.of());
        WorkflowEdge edge = new WorkflowEdge("e1", "start", "coding", "default", "input", "");

        WorkflowDefinition def = new WorkflowDefinition(defId, "Feature Pipeline", "Test description", "proj-1", "user-1", 1, false,
                List.of(start, coding), List.of(edge), "{\"nodes\":[], \"edges\":[]}", Instant.now(), Instant.now());

        definitionRepo.save(def);

        Optional<WorkflowDefinition> found = definitionRepo.findById(defId);
        assertTrue(found.isPresent());
        assertEquals("Feature Pipeline", found.get().getName());
        assertEquals("proj-1", found.get().getProjectId());
    }

    @Test
    public void testWorkflowExecutionLifecycle() {
        // Save parent workflow definition first for foreign key constraint
        String defId = "def-" + UUID.randomUUID();
        WorkflowDefinition def = new WorkflowDefinition(defId, "Lifecycle Test", "", "proj-1", "user-1", 1, false,
                List.of(), List.of(), "{}", Instant.now(), Instant.now());
        definitionRepo.save(def);

        String execId = UUID.randomUUID().toString();
        WorkflowExecution exec = new WorkflowExecution(execId, defId, "proj-1", "user-1",
                WorkflowExecution.Status.PENDING, "start", "{}", Instant.now(), null, null);

        executionRepo.save(exec);

        Optional<WorkflowExecution> found = executionRepo.findById(execId);
        assertTrue(found.isPresent());
        assertEquals(WorkflowExecution.Status.PENDING, found.get().getStatus());

        exec.setStatus(WorkflowExecution.Status.RUNNING);
        executionRepo.save(exec);

        WorkflowExecution updated = executionRepo.findById(execId).orElseThrow();
        assertEquals(WorkflowExecution.Status.RUNNING, updated.getStatus());
        assertNotNull(updated.getStartedAt());
    }

    @Test
    public void testWorkflowValidatorCycleDetection() {
        WorkflowNode n1 = new WorkflowNode("n1", "Step 1", NodeType.AI_ANALYSIS, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());
        WorkflowNode n2 = new WorkflowNode("n2", "Step 2", NodeType.AI_CODING, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());

        // Create cyclic edges: n1 -> n2 and n2 -> n1
        WorkflowEdge e1 = new WorkflowEdge("e1", "n1", "n2", "default", "input", "");
        WorkflowEdge e2 = new WorkflowEdge("e2", "n2", "n1", "default", "input", "");

        WorkflowDefinition cyclicDef = new WorkflowDefinition("Cyclic Def", "", "p1", "u1",
                List.of(n1, n2), List.of(e1, e2), "{}");

        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(cyclicDef);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(err -> err.toLowerCase().contains("cycl")));
    }

    @Test
    public void testWorkflowValidatorValidTopologicalPhases() {
        WorkflowNode start = new WorkflowNode("start", "Start", NodeType.WORKFLOW_START, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());
        WorkflowNode p1 = new WorkflowNode("p1", "Analysis 1", NodeType.AI_ANALYSIS, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());
        WorkflowNode p2 = new WorkflowNode("p2", "Analysis 2", NodeType.AI_ANALYSIS, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());
        WorkflowNode join = new WorkflowNode("join", "Coding", NodeType.AI_CODING, "", "", "", "NORMAL", null, 600, 0, "", 0, 0, Map.of());

        WorkflowEdge e1 = new WorkflowEdge("e1", "start", "p1", "default", "input", "");
        WorkflowEdge e2 = new WorkflowEdge("e2", "start", "p2", "default", "input", "");
        WorkflowEdge e3 = new WorkflowEdge("e3", "p1", "join", "default", "input", "");
        WorkflowEdge e4 = new WorkflowEdge("e4", "p2", "join", "default", "input", "");

        WorkflowDefinition validDef = new WorkflowDefinition("Valid Parallel Def", "", "p1", "u1",
                List.of(start, p1, p2, join), List.of(e1, e2, e3, e4), "{}");

        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(validDef);
        assertTrue(result.valid());
        assertEquals(3, result.executionPhases().size());
        assertEquals(List.of("start"), result.executionPhases().get(0));
        assertTrue(result.executionPhases().get(1).containsAll(List.of("p1", "p2")));
        assertEquals(List.of("join"), result.executionPhases().get(2));
    }

    @Test
    public void testTaskSchedulePersistence() {
        String schedId = UUID.randomUUID().toString();
        Instant due = Instant.now().minusSeconds(10);
        TaskSchedule sched = new TaskSchedule(schedId, "Nightly Tests", "proj-1", "user-1",
                ScheduleType.DAILY, "0 0 * * *", due, "{\"title\":\"Run Nightly\"}",
                TaskSchedule.Status.ACTIVE, null, due, 0, Instant.now());

        scheduleRepo.save(sched);

        Optional<TaskSchedule> found = scheduleRepo.findById(schedId);
        assertTrue(found.isPresent());
        assertEquals("Nightly Tests", found.get().getName());

        List<TaskSchedule> dueList = scheduleRepo.findDueSchedules(Instant.now());
        assertEquals(1, dueList.size());
        assertEquals(schedId, dueList.get(0).getId());
    }

    @Test
    public void testRepositoryLockManagerReadWrite() throws Exception {
        RepositoryLockManager lockManager = new RepositoryLockManager();
        String repoId = "repo-tryst";

        // Two tasks can acquire concurrent read locks (for analysis)
        assertTrue(lockManager.acquireReadLock(repoId, "task-1", "user-1", 60));
        assertTrue(lockManager.acquireReadLock(repoId, "task-2", "user-2", 60));

        // In a concurrent thread, write lock must fail while read locks are held
        boolean[] results = new boolean[2];
        Thread writerThread = new Thread(() -> {
            results[0] = lockManager.acquireWriteLock(repoId, "task-3", "user-3", 60);
        });
        writerThread.start();
        writerThread.join();
        assertFalse(results[0], "Write lock should fail when read locks are held");

        // Release read locks
        lockManager.releaseLock("task-1");
        lockManager.releaseLock("task-2");

        // Now write lock in writerThread succeeds
        Thread writerThread2 = new Thread(() -> {
            results[1] = lockManager.acquireWriteLock(repoId, "task-3", "user-3", 60);
        });
        writerThread2.start();
        writerThread2.join();
        assertTrue(results[1], "Write lock should succeed once read locks are released");
        assertTrue(lockManager.isWriteLocked(repoId));

        // Concurrently, reader on another thread is rejected while write lock is active
        boolean[] readerResult = new boolean[1];
        Thread readerThread = new Thread(() -> {
            readerResult[0] = lockManager.acquireReadLock(repoId, "task-4", "user-4", 60);
        });
        readerThread.start();
        readerThread.join();
        assertFalse(readerResult[0], "Read lock should fail while write lock is held");

        // Release write lock in owner thread
        Thread writerReleaseThread = new Thread(() -> {
            lockManager.releaseLock("task-3");
        });
        writerReleaseThread.start();
        writerReleaseThread.join();
        assertFalse(lockManager.isWriteLocked(repoId));
    }

    @Test
    public void testResourceGovernorThrottling() {
        ResourceGovernor governor = new ResourceGovernor(3, 2, 0.90);

        assertTrue(governor.canDispatchTask(1, 0));
        assertTrue(governor.canDispatchTask(2, 0));
        // Throttled when reaching max concurrency
        assertFalse(governor.canDispatchTask(3, 0));

        assertTrue(governor.canDispatchBuild(1));
        assertFalse(governor.canDispatchBuild(2));
    }
}
