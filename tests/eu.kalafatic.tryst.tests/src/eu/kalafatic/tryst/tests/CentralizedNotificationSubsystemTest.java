package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.model.NotificationPreference;
import eu.kalafatic.tryst.domain.model.NotificationType;
import eu.kalafatic.tryst.domain.service.NotificationService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationPreferenceRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class CentralizedNotificationSubsystemTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteNotificationRepository notificationRepository;
    private SqliteNotificationPreferenceRepository preferenceRepository;
    private EventBus eventBus;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("notif_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository userRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository(databaseManager);
        for (String uid : List.of("user-alice", "user-bob", "user-charlie", "user-reconnect", "user-realtime", "user-pref-test", "user-task-filter", "user-full-lifecycle")) {
            userRepo.save(new eu.kalafatic.tryst.domain.model.User(uid, uid, "hash", "salt", eu.kalafatic.tryst.domain.model.UserRole.USER, true, false, 0, java.time.Instant.now(), java.time.Instant.now()));
        }

        notificationRepository = new SqliteNotificationRepository(databaseManager);
        preferenceRepository = new SqliteNotificationPreferenceRepository(databaseManager);
        eventBus = new EventBus();
        notificationService = new NotificationService(notificationRepository, preferenceRepository, eventBus);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testMultiUserNotificationIsolation() {
        String userA = "user-alice";
        String userB = "user-bob";

        notificationService.notifyUser(userA, NotificationType.TASK_ACCEPTED.name(), "Task Accepted", "Alice task started", "task", "t-1");
        notificationService.notifyUser(userA, NotificationType.BUILD_COMPLETED.name(), "Build Passed", "Alice build passed", "task", "t-1");
        notificationService.notifyUser(userB, NotificationType.TASK_FAILED.name(), "Task Failed", "Bob task failed", "task", "t-2");

        List<Notification> aliceNotifs = notificationService.getUserNotifications(userA, false, 50);
        List<Notification> bobNotifs = notificationService.getUserNotifications(userB, false, 50);

        assertEquals(2, aliceNotifs.size());
        assertEquals(1, bobNotifs.size());

        for (Notification n : aliceNotifs) {
            assertEquals(userA, n.getUserId());
            assertNotEquals(userB, n.getUserId());
        }

        for (Notification n : bobNotifs) {
            assertEquals(userB, n.getUserId());
            assertNotEquals(userA, n.getUserId());
        }

        assertEquals(2, notificationService.getUnreadCount(userA));
        assertEquals(1, notificationService.getUnreadCount(userB));
    }

    @Test
    void testDuplicateNotificationPrevention() {
        String userId = "user-charlie";
        String taskId = "t-duplicate-test";
        String dedupKey = userId + ":TASK_ACCEPTED:" + taskId;

        Optional<Notification> first = notificationService.notifyUser(
                userId,
                NotificationType.TASK_ACCEPTED.name(),
                "Task Accepted",
                "Task queued",
                "task",
                taskId,
                dedupKey
        );
        assertTrue(first.isPresent(), "First notification should be recorded");

        Optional<Notification> duplicate = notificationService.notifyUser(
                userId,
                NotificationType.TASK_ACCEPTED.name(),
                "Task Accepted",
                "Task queued duplicate call",
                "task",
                taskId,
                dedupKey
        );
        assertTrue(duplicate.isEmpty(), "Duplicate notification should be suppressed");

        List<Notification> notifs = notificationService.getUserNotifications(userId, false, 10);
        assertEquals(1, notifs.size(), "Only one persistent notification should exist");
        assertEquals(dedupKey, notifs.get(0).getDedupKey());
    }

    @Test
    void testReconnectionAndSequentialReplaySupport() {
        String userId = "user-reconnect";

        notificationService.notifyUser(userId, NotificationType.TASK_ACCEPTED.name(), "1. Accepted", "M1", "task", "t-1", "k1");
        notificationService.notifyUser(userId, NotificationType.REPOSITORY_CLONED.name(), "2. Cloned", "M2", "task", "t-1", "k2");
        notificationService.notifyUser(userId, NotificationType.AI_ANALYSIS_COMPLETED.name(), "3. Analyzed", "M3", "task", "t-1", "k3");

        List<Notification> allNotifs = notificationService.getUserNotifications(userId, false, 10);
        assertEquals(3, allNotifs.size());
        long lastKnownSeq = allNotifs.get(allNotifs.size() - 2).getSequenceNumber();

        notificationService.notifyUser(userId, NotificationType.BUILD_COMPLETED.name(), "4. Built", "M4", "task", "t-1", "k4");
        notificationService.notifyUser(userId, NotificationType.REVIEW_READY.name(), "5. Review Ready", "M5", "task", "t-1", "k5");

        List<Notification> replayed = notificationService.getNotificationsSinceSequence(userId, lastKnownSeq, 50);
        assertFalse(replayed.isEmpty());
        for (Notification n : replayed) {
            assertTrue(n.getSequenceNumber() > lastKnownSeq, "Sequence should be strictly greater than lastKnownSeq");
        }
    }

    @Test
    void testRealTimeListenerBroadcast() throws Exception {
        String userId = "user-realtime";
        List<Notification> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (AutoCloseable reg = notificationService.registerListener(n -> {
            if (userId.equals(n.getUserId())) {
                received.add(n);
                latch.countDown();
            }
        })) {
            notificationService.notifyUser(userId, NotificationType.APPROVAL_REQUIRED.name(), "Approval Required", "Human review required", "review", "rev-1");
            boolean ok = latch.await(3, TimeUnit.SECONDS);
            assertTrue(ok, "Real-time listener should receive broadcast");
            assertEquals(1, received.size());
            assertEquals("Approval Required", received.get(0).getTitle());
        }
    }

    @Test
    void testNotificationPreferencesFiltering() {
        String userId = "user-pref-test";

        Set<NotificationType> enabled = new HashSet<>(Arrays.asList(
                NotificationType.TASK_ACCEPTED,
                NotificationType.REVIEW_READY,
                NotificationType.TASK_FAILED
        ));
        NotificationPreference pref = new NotificationPreference(userId, true, true, enabled, java.time.Instant.now());
        notificationService.updatePreferences(pref);

        Optional<Notification> n1 = notificationService.notifyUser(userId, NotificationType.TASK_ACCEPTED.name(), "Accepted", "msg", "task", "t-1");
        Optional<Notification> n2 = notificationService.notifyUser(userId, NotificationType.BUILD_COMPLETED.name(), "Built", "msg", "task", "t-1");
        Optional<Notification> n3 = notificationService.notifyUser(userId, NotificationType.REVIEW_READY.name(), "Ready", "msg", "task", "t-1");

        assertTrue(n1.isPresent(), "TASK_ACCEPTED is enabled and should be persisted");
        assertTrue(n2.isEmpty(), "BUILD_COMPLETED is disabled in preferences and should be suppressed");
        assertTrue(n3.isPresent(), "REVIEW_READY is enabled and should be persisted");

        List<Notification> userList = notificationService.getUserNotifications(userId, false, 10);
        assertEquals(2, userList.size());
    }

    @Test
    void testTaskSpecificNotificationQuerying() {
        String userId = "user-task-filter";
        String task1 = "task-alpha";
        String task2 = "task-beta";

        notificationService.notifyUser(userId, NotificationType.TASK_ACCEPTED.name(), "Accepted Alpha", "msg", "task", task1);
        notificationService.notifyUser(userId, NotificationType.BUILD_COMPLETED.name(), "Built Alpha", "msg", "task", task1);
        notificationService.notifyUser(userId, NotificationType.TASK_ACCEPTED.name(), "Accepted Beta", "msg", "task", task2);

        List<Notification> alphaNotifs = notificationService.getTaskNotifications(task1, userId);
        List<Notification> betaNotifs = notificationService.getTaskNotifications(task2, userId);

        assertEquals(2, alphaNotifs.size());
        assertEquals(1, betaNotifs.size());
    }

    @Test
    void testAllTwelveLifecycleEventsProducedCorrectly() {
        String userId = "user-full-lifecycle";
        String taskId = "task-full-lifecycle";

        for (NotificationType type : NotificationType.values()) {
            Optional<Notification> notif = notificationService.notifyUser(
                    userId,
                    type.name(),
                    "Event: " + type.name(),
                    "Message for " + type.name(),
                    "task",
                    taskId,
                    userId + ":" + type.name() + ":" + taskId
            );
            assertTrue(notif.isPresent(), "Notification for " + type.name() + " should be successfully created");
        }

        List<Notification> all = notificationService.getUserNotifications(userId, false, 50);
        assertEquals(12, all.size(), "All 12 distinct events should be persisted for user");

        Set<String> types = new HashSet<>();
        for (Notification n : all) {
            types.add(n.getType());
        }
        assertEquals(12, types.size());
    }
}
