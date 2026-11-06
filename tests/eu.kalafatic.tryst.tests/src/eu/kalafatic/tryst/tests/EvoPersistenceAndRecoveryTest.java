package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.EvoExecutionEvent;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoConfigurationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoExecutionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EvoPersistenceAndRecoveryTest {

    private SqliteDatabaseManager databaseManager;
    private SqliteEvoConfigurationRepository configRepo;
    private SqliteEvoExecutionRepository executionRepo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("evo_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        configRepo = new SqliteEvoConfigurationRepository(databaseManager);
        executionRepo = new SqliteEvoExecutionRepository(databaseManager);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testConfigurationPersistenceAndRetrieval() {
        EvoConfiguration config = new EvoConfiguration("default", "http://127.0.0.1:39080", "secret-key", true, 45, 5, Instant.now());
        configRepo.save(config);

        Optional<EvoConfiguration> retrieved = configRepo.getConfiguration();
        assertTrue(retrieved.isPresent());
        assertEquals("http://127.0.0.1:39080", retrieved.get().getEndpoint());
        assertEquals("secret-key", retrieved.get().getAuthToken());
        assertTrue(retrieved.get().isEnabled());
        assertEquals(45, retrieved.get().getTimeoutSeconds());
        assertEquals(5, retrieved.get().getMaxRetries());
    }

    @Test
    void testExecutionMappingAndEventDeduplication() {
        // Insert dummy project & task to satisfy foreign key constraint on tasks(id)
        insertDummyTask("task-100");

        EvoExecution exec = EvoExecution.create("task-100", "sess-1", "CODE_IMPLEMENTATION", "DEFAULT", "idem-100");
        exec.setEvoExecutionId("evo-exec-999");
        exec.setEvoSessionId("evo-sess-999");
        exec.updateStatus(EvoExecution.ExecutionStatus.RUNNING, "IN_PROGRESS", 0.5);
        executionRepo.saveExecution(exec);

        Optional<EvoExecution> byId = executionRepo.findExecutionById(exec.getId());
        assertTrue(byId.isPresent());
        assertEquals("task-100", byId.get().getTrystTaskId());
        assertEquals("evo-exec-999", byId.get().getEvoExecutionId());
        assertEquals(EvoExecution.ExecutionStatus.RUNNING, byId.get().getStatus());

        // Event persistence & deduplication
        EvoExecutionEvent evt1 = new EvoExecutionEvent("evt-1", exec.getId(), 1, "STARTED", "corr-1", Instant.now(), "{\"msg\":\"start\"}");
        boolean saved1 = executionRepo.saveEvent(evt1);
        assertTrue(saved1);

        // Duplicate event with same event_id should be ignored gracefully
        boolean savedDuplicate = executionRepo.saveEvent(evt1);
        assertFalse(savedDuplicate, "Duplicate event_id must not be inserted again");

        EvoExecutionEvent evt2 = new EvoExecutionEvent("evt-2", exec.getId(), 2, "PROGRESS", "corr-1", Instant.now(), "{\"msg\":\"halfway\"}");
        executionRepo.saveEvent(evt2);

        List<EvoExecutionEvent> events = executionRepo.findEventsByExecutionId(exec.getId(), 0, 10);
        assertEquals(2, events.size());
        assertEquals("evt-1", events.get(0).getEventId());
        assertEquals("evt-2", events.get(1).getEventId());

        long latestSeq = executionRepo.getLatestSequence(exec.getId());
        assertEquals(2, latestSeq);
    }

    private void insertDummyTask(String taskId) {
        synchronized (databaseManager.getLock()) {
            var conn = databaseManager.getConnection();
            try (var stmt = conn.createStatement()) {
                stmt.execute("INSERT OR IGNORE INTO projects (id, name, location, active, created_at, updated_at) " +
                        "VALUES ('proj-1', 'Test Project', '/tmp', 1, '2026-11-06T18:00:00Z', '2026-11-06T18:00:00Z');");
                stmt.execute("INSERT OR IGNORE INTO tasks (id, project_id, title, status, created_at) " +
                        "VALUES ('" + taskId + "', 'proj-1', 'Test Task', 'READY', '2026-11-06T18:00:00Z');");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
