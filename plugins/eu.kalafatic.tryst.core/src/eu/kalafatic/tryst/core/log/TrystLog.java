package eu.kalafatic.tryst.core.log;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized, authoritative logging facility for all TRYST plug-ins.
 * Provides:
 * - Uniform logging API supporting TRACE, DEBUG, INFO, WARN, ERROR.
 * - Automatic correlation ID and task ID context tracking from {@link CorrelationContext}.
 * - Sensitive credential and token redaction via {@link LogSanitizer}.
 * - Bounded in-memory circular buffer (retaining recent structured entries).
 * - Real-time subscription model via {@link LogListener}.
 * - Non-blocking asynchronous file logging with size rotation and retention via {@link AsyncFileLogAppender}.
 * - Standard Eclipse/Equinox Platform log integration with graceful fallback.
 */
public final class TrystLog {

    public static final int DEFAULT_BUFFER_CAPACITY = 2000;

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);
    private static final ConcurrentLinkedDeque<LogEntry> IN_MEMORY_BUFFER = new ConcurrentLinkedDeque<>();
    private static final List<LogListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile LogLevel configuredLevel = LogLevel.DEBUG;
    private static volatile AsyncFileLogAppender fileAppender;
    private static volatile int bufferCapacity = DEFAULT_BUFFER_CAPACITY;

    static {
        // Initialize persistent file logging in user home .tryst/logs by default
        try {
            String logDirProp = System.getProperty("tryst.log.dir");
            Path logDir = logDirProp != null ? Path.of(logDirProp) : Path.of(System.getProperty("user.home"), ".tryst", "logs");
            fileAppender = new AsyncFileLogAppender(logDir);
            fileAppender.start();
        } catch (Throwable t) {
            System.err.println("[TrystLog] Failed to initialize default file appender: " + t.getMessage());
        }

        // Configure log level from system property if present
        String levelProp = System.getProperty("tryst.log.level");
        if (levelProp != null && !levelProp.isBlank()) {
            configuredLevel = LogLevel.fromString(levelProp);
        }
    }

    private TrystLog() {}

    public static LogLevel getLogLevel() {
        return configuredLevel;
    }

    public static void setLogLevel(LogLevel level) {
        if (level != null) {
            configuredLevel = level;
        }
    }

    public static void setBufferCapacity(int capacity) {
        bufferCapacity = Math.max(1, capacity);
        trimBuffer();
    }

    public static void configureFileLogging(Path logDirectory, long maxFileSizeBytes, int maxBackupFiles) {
        synchronized (TrystLog.class) {
            if (fileAppender != null) {
                fileAppender.stop();
            }
            if (logDirectory != null) {
                fileAppender = new AsyncFileLogAppender(logDirectory, maxFileSizeBytes, maxBackupFiles);
                fileAppender.start();
            } else {
                fileAppender = null;
            }
        }
    }

    public static void addListener(LogListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(LogListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static List<LogEntry> getRecentEntries() {
        return new ArrayList<>(IN_MEMORY_BUFFER);
    }

    public static void clearInMemoryBuffer() {
        IN_MEMORY_BUFFER.clear();
    }

    // --- Logging Methods ---

    public static void trace(Class<?> caller, String message) {
        log(LogLevel.TRACE, caller, message, null);
    }

    public static void debug(Class<?> caller, String message) {
        log(LogLevel.DEBUG, caller, message, null);
    }

    public static void info(Class<?> caller, String message) {
        log(LogLevel.INFO, caller, message, null);
    }

    public static void warn(Class<?> caller, String message) {
        log(LogLevel.WARN, caller, message, null);
    }

    public static void warn(Class<?> caller, String message, Throwable throwable) {
        log(LogLevel.WARN, caller, message, throwable);
    }

    public static void error(Class<?> caller, String message) {
        log(LogLevel.ERROR, caller, message, null);
    }

    public static void error(Class<?> caller, String message, Throwable throwable) {
        log(LogLevel.ERROR, caller, message, throwable);
    }

    public static void log(LogLevel level, Class<?> caller, String rawMessage, Throwable throwable) {
        if (level == null || !level.isEnabled(configuredLevel)) {
            return;
        }

        try {
            long seq = SEQUENCE_GENERATOR.incrementAndGet();
            Instant now = Instant.now();
            String category = caller != null ? caller.getSimpleName() : "TRYST";
            String bundleId = resolvePluginId(caller);
            String threadName = Thread.currentThread().getName();

            // Sanitize message to prevent credential leakage
            String sanitizedMessage = LogSanitizer.sanitize(rawMessage);

            String correlationId = CorrelationContext.getCorrelationId();
            String taskId = CorrelationContext.getTaskId();

            LogEntry entry = LogEntry.create(
                    seq,
                    now,
                    level,
                    category,
                    bundleId,
                    threadName,
                    sanitizedMessage,
                    correlationId,
                    taskId,
                    throwable
            );

            // 1. Buffer in memory
            IN_MEMORY_BUFFER.add(entry);
            trimBuffer();

            // 2. Dispatch to async file logger
            AsyncFileLogAppender appender = fileAppender;
            if (appender != null) {
                appender.append(entry);
            }

            // 3. Dispatch to live stream listeners (Console view)
            for (LogListener listener : LISTENERS) {
                try {
                    listener.onLogEntry(entry);
                } catch (Throwable t) {
                    // Prevent faulty listener from crashing logging
                }
            }

            // 4. Dispatch to Eclipse Platform log
            dispatchToEclipseLog(level, caller, bundleId, category, sanitizedMessage, correlationId, taskId, throwable);

        } catch (Throwable t) {
            // Logging itself must never crash application
            fallbackLog(level, resolvePluginId(caller), rawMessage, throwable);
        }
    }

    private static void trimBuffer() {
        while (IN_MEMORY_BUFFER.size() > bufferCapacity) {
            IN_MEMORY_BUFFER.pollFirst();
        }
    }

    private static String resolvePluginId(Class<?> caller) {
        if (caller != null) {
            Bundle bundle = FrameworkUtil.getBundle(caller);
            if (bundle != null) {
                return bundle.getSymbolicName();
            }
        }
        return "eu.kalafatic.tryst.core";
    }

    private static void dispatchToEclipseLog(LogLevel level, Class<?> caller, String pluginId, String category,
                                             String message, String correlationId, String taskId, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(category).append("]");
        if (correlationId != null) {
            sb.append(" [corr:").append(correlationId).append("]");
        }
        if (taskId != null) {
            sb.append(" [task:").append(taskId).append("]");
        }
        sb.append(" ").append(Objects.requireNonNullElse(message, ""));
        String formatted = sb.toString();

        int severity = switch (level) {
            case ERROR -> IStatus.ERROR;
            case WARN -> IStatus.WARNING;
            case INFO -> IStatus.INFO;
            default -> IStatus.OK;
        };

        try {
            ILog log = Platform.getLog(caller);
            if (log != null) {
                Status status = new Status(severity, pluginId, formatted, throwable);
                log.log(status);
                return;
            }
        } catch (Throwable ignored) {
            // Outside Eclipse / PDE runtime
        }

        fallbackLog(level, pluginId, formatted, throwable);
    }

    private static void fallbackLog(LogLevel level, String pluginId, String formatted, Throwable throwable) {
        String line = String.format("%s [%s] (%s) %s", Instant.now(), level, pluginId, formatted);
        if (level == LogLevel.ERROR) {
            System.err.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        } else {
            System.out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }

    /**
     * Flushes buffers and stops file appender cleanly.
     */
    public static synchronized void shutdown() {
        if (fileAppender != null) {
            fileAppender.stop();
        }
        LISTENERS.clear();
    }
}
