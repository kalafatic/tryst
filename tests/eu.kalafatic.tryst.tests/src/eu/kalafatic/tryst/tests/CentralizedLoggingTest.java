package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.log.AsyncFileLogAppender;
import eu.kalafatic.tryst.core.log.CorrelationContext;
import eu.kalafatic.tryst.core.log.LogEntry;
import eu.kalafatic.tryst.core.log.LogLevel;
import eu.kalafatic.tryst.core.log.LogSanitizer;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the centralized logging facility:
 * - Severity level filtering
 * - Correlation ID and Task ID context tracking
 * - Sensitive information redaction
 * - Bounded buffer capacity
 * - Asynchronous rotating file logger
 * - Concurrent multi-threaded logging
 */
class CentralizedLoggingTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TrystLog.clearInMemoryBuffer();
        TrystLog.setLogLevel(LogLevel.TRACE);
        CorrelationContext.clear();
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
        TrystLog.clearInMemoryBuffer();
    }

    @Test
    void testSeverityLevelFiltering() {
        TrystLog.setLogLevel(LogLevel.WARN);

        List<LogEntry> received = new ArrayList<>();
        TrystLog.addListener(received::add);

        try {
            TrystLog.trace(CentralizedLoggingTest.class, "Trace message");
            TrystLog.debug(CentralizedLoggingTest.class, "Debug message");
            TrystLog.info(CentralizedLoggingTest.class, "Info message");
            TrystLog.warn(CentralizedLoggingTest.class, "Warn message");
            TrystLog.error(CentralizedLoggingTest.class, "Error message");

            assertEquals(2, received.size());
            assertEquals(LogLevel.WARN, received.get(0).level());
            assertEquals(LogLevel.ERROR, received.get(1).level());
        } finally {
            TrystLog.removeListener(received::add);
            TrystLog.setLogLevel(LogLevel.TRACE);
        }
    }

    @Test
    void testCorrelationAndTaskIdContext() {
        List<LogEntry> received = new ArrayList<>();
        TrystLog.addListener(received::add);

        try (var scope = CorrelationContext.open("corr-12345", "task-9999")) {
            TrystLog.info(CentralizedLoggingTest.class, "Processing task within correlation scope");

            assertEquals(1, received.size());
            LogEntry entry = received.get(0);
            assertEquals("corr-12345", entry.correlationId());
            assertEquals("task-9999", entry.taskId());
            assertEquals("CentralizedLoggingTest", entry.category());
        } finally {
            TrystLog.removeListener(received::add);
        }
    }

    @Test
    void testSensitiveInformationRedaction() {
        String secretRaw = "Connecting with token=ghp_ABC1234567890abcdef and password: 'superSecretPassword123'";
        String sanitized = LogSanitizer.sanitize(secretRaw);

        assertFalse(sanitized.contains("superSecretPassword123"));
        assertFalse(sanitized.contains("ghp_ABC1234567890abcdef"));
        assertTrue(sanitized.contains("[REDACTED]"));

        List<LogEntry> received = new ArrayList<>();
        TrystLog.addListener(received::add);
        try {
            TrystLog.info(CentralizedLoggingTest.class, "Auth payload: Authorization: Bearer mySecretJwtToken");
            assertEquals(1, received.size());
            assertFalse(received.get(0).message().contains("mySecretJwtToken"));
            assertTrue(received.get(0).message().contains("[REDACTED]"));
        } finally {
            TrystLog.removeListener(received::add);
        }
    }

    @Test
    void testBoundedBufferCapacity() {
        TrystLog.setBufferCapacity(50);
        for (int i = 0; i < 100; i++) {
            TrystLog.info(CentralizedLoggingTest.class, "Msg " + i);
        }

        List<LogEntry> entries = TrystLog.getRecentEntries();
        assertEquals(50, entries.size());
        assertTrue(entries.get(entries.size() - 1).message().contains("Msg 99"));

        TrystLog.setBufferCapacity(TrystLog.DEFAULT_BUFFER_CAPACITY);
    }

    @Test
    void testExceptionAndStackTraceHandling() {
        Exception testEx = new IllegalArgumentException("Test parameter error");
        List<LogEntry> received = new ArrayList<>();
        TrystLog.addListener(received::add);

        try {
            TrystLog.error(CentralizedLoggingTest.class, "Failure occurred", testEx);

            assertEquals(1, received.size());
            LogEntry entry = received.get(0);
            assertEquals(LogLevel.ERROR, entry.level());
            assertNotNull(entry.throwable());
            assertNotNull(entry.formattedStackTrace());
            assertTrue(entry.formattedStackTrace().contains("IllegalArgumentException: Test parameter error"));
        } finally {
            TrystLog.removeListener(received::add);
        }
    }

    @Test
    void testAsyncFileLogAppenderAndRotation() throws Exception {
        Path logDir = tempDir.resolve("test-logs");
        // small 10 KB file size for testing rotation
        AsyncFileLogAppender appender = new AsyncFileLogAppender(logDir, 10 * 1024, 3);
        appender.start();
        assertTrue(appender.isRunning());

        try {
            for (int i = 0; i < 500; i++) {
                LogEntry entry = LogEntry.create(
                        i,
                        java.time.Instant.now(),
                        LogLevel.INFO,
                        "RotationTest",
                        "eu.kalafatic.tryst.core",
                        Thread.currentThread().getName(),
                        "Padding message content for testing rotation size threshold: " + i + " " + "x".repeat(100),
                        null,
                        null,
                        null
                );
                appender.append(entry);
            }

            // Allow worker to flush
            Thread.sleep(500);
            appender.stop();

            assertTrue(Files.exists(appender.getCurrentLogFile()));
            assertTrue(Files.size(appender.getCurrentLogFile()) > 0);
        } finally {
            appender.stop();
        }
    }

    @Test
    void testConcurrentLogging() throws InterruptedException {
        int threads = 8;
        int logsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger count = new AtomicInteger(0);

        eu.kalafatic.tryst.core.log.LogListener listener = entry -> {
            if (entry.message() != null && entry.message().startsWith("Thread ")) {
                count.incrementAndGet();
            }
        };
        TrystLog.addListener(listener);

        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                new Thread(() -> {
                    for (int i = 0; i < logsPerThread; i++) {
                        TrystLog.info(CentralizedLoggingTest.class, "Thread " + threadId + " log " + i);
                    }
                    latch.countDown();
                }).start();
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(threads * logsPerThread, count.get());
        } finally {
            TrystLog.removeListener(listener);
        }
    }
}
