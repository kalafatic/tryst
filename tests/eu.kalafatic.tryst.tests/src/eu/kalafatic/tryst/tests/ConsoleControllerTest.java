package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.log.LogEntry;
import eu.kalafatic.tryst.core.log.LogLevel;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.controller.ConsoleOutputController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ConsoleOutputController logic:
 * - Live streaming subscription
 * - Severity level filtering
 * - Search substring filtering
 * - Pause and resume streaming
 * - Concurrent high volume log dispatch
 */
class ConsoleControllerTest {

    @TempDir
    Path tempDir;

    private WorkspaceService workspaceService;
    private ConsoleOutputController controller;

    @BeforeEach
    void setUp() {
        TrystLog.clearInMemoryBuffer();
        workspaceService = new WorkspaceService(tempDir.resolve("ws"));
        controller = new ConsoleOutputController(workspaceService);
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.dispose();
        }
        TrystLog.clearInMemoryBuffer();
    }

    @Test
    void testStreamingAndFiltering() {
        List<LogEntry> received = new ArrayList<>();
        controller.setLogEntryCallback(received::add);

        controller.setFilterLevel(LogLevel.WARN);

        TrystLog.debug(ConsoleControllerTest.class, "Ignored debug");
        TrystLog.info(ConsoleControllerTest.class, "Ignored info");
        TrystLog.warn(ConsoleControllerTest.class, "Captured warning");
        TrystLog.error(ConsoleControllerTest.class, "Captured error");

        assertEquals(2, received.size());
        assertEquals(LogLevel.WARN, received.get(0).level());
        assertEquals(LogLevel.ERROR, received.get(1).level());

        // Test clear visible
        controller.clearVisible();
        assertEquals(0, controller.getFilteredEntries().size());
    }

    @Test
    void testSearchSubstring() {
        LogEntry e1 = LogEntry.create(1, java.time.Instant.now(), LogLevel.INFO, "Cat1", "bundle", "main", "Database connected", null, null, null);
        LogEntry e2 = LogEntry.create(2, java.time.Instant.now(), LogLevel.INFO, "Cat2", "bundle", "main", "HTTP Server listening", null, "task-42", null);

        controller.setSearchText("task-42");
        assertFalse(controller.matchesFilter(e1));
        assertTrue(controller.matchesFilter(e2));

        controller.setSearchText("Database");
        assertTrue(controller.matchesFilter(e1));
        assertFalse(controller.matchesFilter(e2));

        controller.setSearchText(null);
        assertTrue(controller.matchesFilter(e1));
        assertTrue(controller.matchesFilter(e2));
    }

    @Test
    void testPauseAndResume() {
        List<LogEntry> received = new ArrayList<>();
        controller.setLogEntryCallback(received::add);

        controller.setPaused(true);
        assertTrue(controller.isPaused());

        TrystLog.info(ConsoleControllerTest.class, "Dropped while paused");
        assertEquals(0, received.size());

        controller.setPaused(false);
        assertFalse(controller.isPaused());

        TrystLog.info(ConsoleControllerTest.class, "Emitted after resume");
        assertEquals(1, received.size());
    }

    @Test
    void testHighVolumeConcurrency() throws InterruptedException {
        int threads = 4;
        int countPerThread = 250;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger receivedCount = new AtomicInteger(0);

        controller.setLogEntryCallback(e -> {
            if (e.message() != null && e.message().startsWith("High volume log ")) {
                receivedCount.incrementAndGet();
            }
        });

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < countPerThread; i++) {
                    TrystLog.info(ConsoleControllerTest.class, "High volume log " + i);
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(threads * countPerThread, receivedCount.get());
    }
}
