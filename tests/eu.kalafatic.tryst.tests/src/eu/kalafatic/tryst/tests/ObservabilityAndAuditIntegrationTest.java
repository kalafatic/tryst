package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AuditEvent;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.model.TelemetrySnapshot;
import eu.kalafatic.tryst.domain.repository.AuditEventRepository;
import eu.kalafatic.tryst.domain.repository.NotificationRepository;
import eu.kalafatic.tryst.domain.repository.TelemetryRepository;
import eu.kalafatic.tryst.domain.service.AiMonitoringService;
import eu.kalafatic.tryst.domain.service.LogDiagnosticService;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAuditEventRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTelemetryRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityAndAuditIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("obs_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testPersistentAuditEventsSurviveRestart() {
        AuditEventRepository auditRepo = new SqliteAuditEventRepository(databaseManager);

        AuditEvent ev = AuditEvent.create("alice", "PROJECT_CREATE", "project", "p-1", "p-1", null, "SUCCESS", "{\"desc\":\"New proj\"}");
        auditRepo.save(ev);
        assertEquals(1, auditRepo.count());

        // Simulate application restart
        databaseManager.close();
        databaseManager.initialize();

        AuditEventRepository reopenedRepo = new SqliteAuditEventRepository(databaseManager);
        List<AuditEvent> recent = reopenedRepo.findRecent(10);
        assertEquals(1, recent.size());
        assertEquals("PROJECT_CREATE", recent.get(0).action());
        assertEquals("alice", recent.get(0).actorId());
    }

    @Test
    void testTelemetryCollectionAndRetention() {
        TelemetryRepository telemRepo = new SqliteTelemetryRepository(databaseManager);
        ServerMonitoringService mon = new ServerMonitoringService(tempDir);

        ServerMonitoringService.SystemMetrics metrics = mon.collectMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.availableProcessors() > 0);
        assertTrue(metrics.heapUsedBytes() > 0);

        TelemetrySnapshot snap = TelemetrySnapshot.create(
                metrics.systemCpuLoad(), metrics.processCpuLoad(),
                metrics.heapUsedBytes(), metrics.heapMaxBytes(), 100000L,
                1000L, 2000L, 1, 0, 0, 15.5
        );
        telemRepo.save(snap);

        List<TelemetrySnapshot> list = telemRepo.findRecent(10);
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).activeSessions());
    }

    @Test
    void testUserNotificationsWorkflow() {
        SqliteUserRepository userRepo = new SqliteUserRepository(databaseManager);
        userRepo.save(new eu.kalafatic.tryst.domain.model.User("user-1", "user1", "hash", "salt", eu.kalafatic.tryst.domain.model.UserRole.USER, true, false, 0, null, null));
        userRepo.save(new eu.kalafatic.tryst.domain.model.User("user-2", "user2", "hash", "salt", eu.kalafatic.tryst.domain.model.UserRole.USER, true, false, 0, null, null));

        NotificationRepository notifRepo = new SqliteNotificationRepository(databaseManager);

        Notification n1 = new Notification("user-1", "TASK_COMPLETED", "Task Done", "Task #123 completed successfully", "task", "123");
        notifRepo.save(n1);

        assertEquals(1, notifRepo.countUnread("user-1"));
        assertEquals(0, notifRepo.countUnread("user-2"), "Notifications must be strictly user-scoped");

        notifRepo.markAsRead(n1.getId());
        assertEquals(0, notifRepo.countUnread("user-1"));
    }

    @Test
    void testLogDiagnosticsAndRedaction() {
        LogDiagnosticService logService = new LogDiagnosticService();
        logService.log("INFO", "Security", "User authenticated with bearer token: Bearer abc123def456 and password=Secret1234", null);

        List<LogDiagnosticService.LogEntry> entries = logService.query("INFO", "Security", null, 10);
        assertEquals(1, entries.size());
        String msg = entries.get(0).message();
        assertFalse(msg.contains("Secret1234"), "Password must be redacted");
        assertTrue(msg.contains("[REDACTED]"));
    }

    @Test
    void testProcessExecutionManagerTermination() throws Exception {
        ProcessExecutionManager procMgr = new ProcessExecutionManager();
        ProcessBuilder pb = new ProcessBuilder("sleep", "10");
        Process p = pb.start();

        var record = procMgr.registerProcess("task-p1", p, "sleep", tempDir.toString());
        assertNotNull(record);
        assertTrue(p.isAlive());

        boolean killed = procMgr.terminateProcess(record.getId());
        assertTrue(killed);
        assertFalse(p.isAlive(), "Managed child process must be terminated");
    }
}
