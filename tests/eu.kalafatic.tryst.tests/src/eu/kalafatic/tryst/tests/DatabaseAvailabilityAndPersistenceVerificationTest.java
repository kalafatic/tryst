package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.ApplicationWorkbenchAdvisor;
import eu.kalafatic.tryst.application.TrystApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of SQLite database lifecycle, migrations V1-V14,
 * repository CRUD and rollback semantics, coordinator resolution, and data retention across restarts.
 */
class DatabaseAvailabilityAndPersistenceVerificationTest {

    private ApplicationLifecycleCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            try {
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
                coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
            } catch (Exception ignored) {
            }
            coordinator = null;
        }
        TrystApplication.setCoordinator(null);
    }

    @Test
    void testCoordinatorResolutionAndDatabaseLifecycle(@TempDir Path tempDir) throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        assertNotNull(context, "OSGi BundleContext must be accessible");

        coordinator = new ApplicationLifecycleCoordinator(context);
        TrystApplication.setCoordinator(coordinator);

        // Verify coordinator resolution
        assertSame(coordinator, ApplicationWorkbenchAdvisor.resolveCoordinator(),
                "ApplicationWorkbenchAdvisor.resolveCoordinator() must return active TrystApplication coordinator");

        // Drive lifecycle
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        SqliteDatabaseManager dbManager = coordinator.getDatabaseManager();
        assertNotNull(dbManager, "SqliteDatabaseManager must be initialized");
        assertTrue(Files.exists(dbManager.getDatabasePath()), "Database file must exist");
        assertTrue(dbManager.verifyDatabaseConsistency(), "PRAGMA consistency check must pass");

        // Check all required tables exist
        Set<String> expectedTables = Set.of(
                "schema_version", "projects", "workspaces", "sessions", "tasks",
                "conversation_messages", "execution_results", "agents", "code_reviews",
                "review_file_edits", "users", "user_sessions", "audit_events",
                "telemetry_snapshots", "notifications", "managed_processes",
                "ai_providers", "ai_models", "task_events", "github_credentials",
                "review_revisions", "review_comments", "task_recovery_checkpoints",
                "notification_preferences", "git_commits", "task_instructions",
                "evo_configurations", "evo_executions", "evo_execution_events",
                "workflow_definitions", "workflow_executions", "task_schedules",
                "repository_mirrors"
        );

        List<String> actualTables = new ArrayList<>();
        Connection conn = dbManager.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")) {
            while (rs.next()) {
                actualTables.add(rs.getString(1));
            }
        }

        for (String table : expectedTables) {
            assertTrue(actualTables.contains(table), "Missing expected table: " + table);
        }

        // Verify schema_version is 14
        SchemaMigrationService migrationService = new SchemaMigrationService(dbManager);
        assertEquals(14, migrationService.getCurrentVersion(), "Schema version must be 14");
    }

    @Test
    void testTransactionalRollbackAndDataRetentionAcrossRestart(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("persistence_test.db");

        // Initial setup and population
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            new SchemaMigrationService(dbManager).migrate();

            SqliteProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
            SqliteTaskRepository taskRepo = new SqliteTaskRepository(dbManager);

            Project project = new Project("P1", Path.of("/tmp/p1"));
            projectRepo.save(project);

            AgentTask task = new AgentTask("Initial Task", "Test task");
            taskRepo.save(task);

            // Verify transactional rollback
            assertThrows(RuntimeException.class, () -> {
                dbManager.runInTransaction(() -> {
                    AgentTask rolledBackTask = new AgentTask("RolledBack", "Should fail");
                    taskRepo.save(rolledBackTask);
                    throw new RuntimeException("Simulated failure to force rollback");
                });
            });

            assertEquals(1, taskRepo.findAll().size(), "Rolled back task must not be persisted");
        }

        // Second startup reopening the same DB
        try (SqliteDatabaseManager reopenedDb = new SqliteDatabaseManager(dbPath)) {
            reopenedDb.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(reopenedDb);
            migration.migrate();
            assertEquals(14, migration.getCurrentVersion());

            SqliteProjectRepository projectRepo = new SqliteProjectRepository(reopenedDb);
            SqliteTaskRepository taskRepo = new SqliteTaskRepository(reopenedDb);

            assertEquals(1, projectRepo.findAll().size(), "Projects must be preserved across restart");
            assertEquals(1, taskRepo.findAll().size(), "Tasks must be preserved across restart");
            assertEquals("Initial Task", taskRepo.findAll().get(0).getTitle());
        }
    }
}
