package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.InterruptedOperationClassification;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskRecoveryCheckpoint;
import eu.kalafatic.tryst.domain.model.TaskRecoveryDecision;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.UserSession;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.service.DataRetentionService;
import eu.kalafatic.tryst.domain.service.RetentionPolicy;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskRecoveryService;
import eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteBackupService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteRetentionStore;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRecoveryCheckpointRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive persistence, restart recovery, database consistency,
 * and data retention test suite for TRYST.
 */
public class ComprehensivePersistenceAndRecoveryTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private SqliteCodeReviewRepository codeReviewRepository;
    private SqliteTaskRecoveryCheckpointRepository checkpointRepository;
    private TaskLifecycleManager lifecycleManager;
    private TaskRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("tryst_persistence_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);
        codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);
        checkpointRepository = new SqliteTaskRecoveryCheckpointRepository(databaseManager);

        lifecycleManager = new TaskLifecycleManager(taskRepository, new EventBus());
        recoveryService = new TaskRecoveryService(taskRepository, lifecycleManager, checkpointRepository);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testDatabaseConsistencyAndForeignKeyReferentialIntegrity() {
        // Assert that comprehensive consistency verification passes out of the box
        assertTrue(databaseManager.verifyDatabaseConsistency());

        // Test WAL checkpointing execution
        assertDoesNotThrow(() -> databaseManager.checkpointWal("PASSIVE"));
        assertDoesNotThrow(() -> databaseManager.checkpointWal("TRUNCATE"));
    }

    @Test
    void testRecoveryCheckpointsPersistenceAndQuerying() {
        Task task = new Task("Task Checkpoint Test", "Verifying recovery checkpoint persistence");
        taskRepository.saveTask(task);

        TaskRecoveryCheckpoint cp1 = TaskRecoveryCheckpoint.of(task.getId(), TaskStatus.CLONING, "{\"branch\":\"main\"}");
        TaskRecoveryCheckpoint cp2 = TaskRecoveryCheckpoint.of(task.getId(), TaskStatus.IMPLEMENTING, "{\"step\":2}");

        checkpointRepository.save(cp1);
        checkpointRepository.save(cp2);

        assertEquals(2, checkpointRepository.count());

        List<TaskRecoveryCheckpoint> checkpoints = checkpointRepository.findByTaskId(task.getId());
        assertEquals(2, checkpoints.size());

        Optional<TaskRecoveryCheckpoint> latest = checkpointRepository.findLatestByTaskId(task.getId());
        assertTrue(latest.isPresent());
        assertEquals(TaskStatus.IMPLEMENTING, latest.get().stage());

        Optional<TaskRecoveryCheckpoint> byStage = checkpointRepository.findByTaskIdAndStage(task.getId(), TaskStatus.CLONING);
        assertTrue(byStage.isPresent());
        assertEquals("{\"branch\":\"main\"}", byStage.get().checkpointDataJson());
    }

    @Test
    void testSimulatedRestartInterruptedTaskClassificationAndRecovery() {
        // Create active interrupted tasks across different stages
        Task gitTask = new Task("Git Task", "Interrupted during cloning");
        gitTask.transitionTo(TaskStatus.QUEUED);
        gitTask.transitionTo(TaskStatus.CLAIMED);
        gitTask.transitionTo(TaskStatus.CLONING);
        taskRepository.saveTask(gitTask);

        Task aiTask = new Task("AI Task", "Interrupted during AI code generation");
        aiTask.transitionTo(TaskStatus.QUEUED);
        aiTask.transitionTo(TaskStatus.CLAIMED);
        aiTask.transitionTo(TaskStatus.CLONING);
        aiTask.transitionTo(TaskStatus.ANALYZING);
        aiTask.transitionTo(TaskStatus.PLANNING);
        aiTask.transitionTo(TaskStatus.IMPLEMENTING);
        taskRepository.saveTask(aiTask);
        checkpointRepository.save(TaskRecoveryCheckpoint.of(aiTask.getId(), TaskStatus.IMPLEMENTING, "{\"filesModified\":2}"));

        Task commitTask = new Task("Commit Task", "Interrupted during git commit");
        commitTask.transitionTo(TaskStatus.QUEUED);
        commitTask.transitionTo(TaskStatus.CLAIMED);
        commitTask.transitionTo(TaskStatus.IMPLEMENTING);
        commitTask.transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        commitTask.transitionTo(TaskStatus.APPROVED);
        commitTask.transitionTo(TaskStatus.COMMITTING);
        taskRepository.saveTask(commitTask);

        Task reviewTask = new Task("Review Task", "Waiting for human review");
        reviewTask.transitionTo(TaskStatus.QUEUED);
        reviewTask.transitionTo(TaskStatus.CLAIMED);
        reviewTask.transitionTo(TaskStatus.IMPLEMENTING);
        reviewTask.transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        taskRepository.saveTask(reviewTask);

        // Simulate crash & startup recovery
        List<Task> recovered = recoveryService.reconcileInterruptedTasks();
        assertEquals(3, recovered.size(), "CLONING, IMPLEMENTING, and COMMITTING tasks must be reconciled");

        // Verify gitTask classification and decision
        Task loadedGit = taskRepository.findTaskById(gitTask.getId()).orElseThrow();
        assertEquals(TaskStatus.RECOVERY_REQUIRED, loadedGit.getStatus());
        TaskRecoveryService.RecoveryAssessment gitAssessment = recoveryService.assessTask(gitTask);
        assertEquals(InterruptedOperationClassification.INTERRUPTED_GIT_OPERATION, gitAssessment.classification());
        assertEquals(TaskRecoveryDecision.RECONCILE_AND_RETRY, gitAssessment.decision());

        // Verify aiTask classification and decision
        Task loadedAi = taskRepository.findTaskById(aiTask.getId()).orElseThrow();
        assertEquals(TaskStatus.RECOVERY_REQUIRED, loadedAi.getStatus());
        TaskRecoveryService.RecoveryAssessment aiAssessment = recoveryService.assessTask(aiTask);
        assertEquals(InterruptedOperationClassification.INTERRUPTED_AI_REQUEST, aiAssessment.classification());
        assertEquals(TaskRecoveryDecision.RECONCILE_AND_RETRY, aiAssessment.decision());

        // Verify commitTask requires human intervention to prevent duplicate commits
        TaskRecoveryService.RecoveryAssessment commitAssessment = recoveryService.assessTask(commitTask);
        assertEquals(TaskRecoveryDecision.REQUIRES_USER_INTERVENTION, commitAssessment.decision());
        assertFalse(recoveryService.retryRecoveredTask(commitTask.getId()), "Auto-retry must be rejected when user intervention is required");

        // Operator confirmation forces retry
        assertTrue(recoveryService.forceRetryRecoveredTask(commitTask.getId(), "admin-operator"));
        assertEquals(TaskStatus.QUEUED, taskRepository.findTaskById(commitTask.getId()).orElseThrow().getStatus());

        // Review task must remain intact
        Task loadedReview = taskRepository.findTaskById(reviewTask.getId()).orElseThrow();
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, loadedReview.getStatus(),
                "WAITING_FOR_REVIEW tasks must never be lost or modified across application restarts");
    }

    @Test
    void testBackupCreationVerificationAndRestoreIntegrity() throws IOException {
        Path backupDir = tempDir.resolve("backup_test_dir");
        Path restoreDir = tempDir.resolve("restored_db_dir");
        Files.createDirectories(backupDir);

        Project p = new Project("PersistentApp", Path.of("/workspace/proj"));
        projectRepository.save(p);

        SqliteBackupService backupService = new SqliteBackupService(databaseManager);
        Path backupFile = backupService.createBackup(backupDir);
        assertTrue(Files.exists(backupFile));
        assertTrue(Files.size(backupFile) > 0);

        // Verify backup integrity
        assertTrue(backupService.verifyBackup(backupFile));

        // Restore backup to new location
        Path restoredFile = restoreDir.resolve("tryst_restored.db");
        backupService.restoreFromBackup(backupFile, restoredFile);
        assertTrue(Files.exists(restoredFile));

        // Open restored DB and verify data is intact
        try (SqliteDatabaseManager restoredDb = new SqliteDatabaseManager(restoredFile)) {
            restoredDb.initialize();
            assertTrue(restoredDb.verifyDatabaseConsistency());
            SqliteProjectRepository restoredRepo = new SqliteProjectRepository(restoredDb);
            Optional<Project> restored = restoredRepo.findById(p.getId());
            assertTrue(restored.isPresent());
            assertEquals("PersistentApp", restored.get().getName());
        }
    }

    @Test
    void testDataRetentionAndArchivalPolicy() {
        SqliteRetentionStore store = new SqliteRetentionStore(databaseManager);
        DataRetentionService retentionService = new DataRetentionService(store, databaseManager);

        // Configure aggressive retention policy for testing
        RetentionPolicy testPolicy = new RetentionPolicy(
                Duration.ofMillis(10), // User sessions
                Duration.ofMillis(10), // Audit events
                Duration.ofMillis(10), // Telemetry
                Duration.ofDays(60),   // Completed tasks
                Duration.ofMillis(10)  // Task events
        );
        retentionService.setPolicy(testPolicy);

        // Create a task and task event
        Task task = new Task("Retention Task", "Testing event cleanup");
        taskRepository.saveTask(task);
        lifecycleManager.transition(task, TaskStatus.QUEUED, "Queued", null);
        lifecycleManager.transition(task, TaskStatus.CLAIMED, "Claimed", null);
        lifecycleManager.transition(task, TaskStatus.CLONING, "Cloning", null);

        // Allow cutoff duration to pass
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        // Execute retention purge
        DataRetentionService.RetentionReport report = retentionService.executeRetentionPolicy();
        assertNotNull(report);
        assertTrue(report.oldTaskEventsPurged() >= 2);
    }
}
