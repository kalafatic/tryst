package eu.kalafatic.tryst.core.log;

import java.util.Optional;
import java.util.UUID;

/**
 * Thread-local context tracking request correlation ID and active task ID.
 * Supports propagation across HTTP requests and asynchronous task worker threads.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> CURRENT_CORRELATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    public static String getCorrelationId() {
        String id = CURRENT_CORRELATION_ID.get();
        if (id == null || id.isBlank()) {
            return null;
        }
        return id;
    }

    public static String getOrCreateCorrelationId() {
        String id = CURRENT_CORRELATION_ID.get();
        if (id == null || id.isBlank()) {
            id = "corr-" + UUID.randomUUID().toString();
            CURRENT_CORRELATION_ID.set(id);
        }
        return id;
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            CURRENT_CORRELATION_ID.set(correlationId.trim());
        } else {
            CURRENT_CORRELATION_ID.remove();
        }
    }

    public static String getTaskId() {
        return CURRENT_TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            CURRENT_TASK_ID.set(taskId.trim());
        } else {
            CURRENT_TASK_ID.remove();
        }
    }

    public static void clear() {
        CURRENT_CORRELATION_ID.remove();
        CURRENT_TASK_ID.remove();
    }

    public static Scope open(String correlationId, String taskId) {
        String previousCorrelationId = CURRENT_CORRELATION_ID.get();
        String previousTaskId = CURRENT_TASK_ID.get();

        setCorrelationId(correlationId != null && !correlationId.isBlank() ? correlationId : "corr-" + UUID.randomUUID());
        setTaskId(taskId);

        return () -> {
            if (previousCorrelationId != null) {
                CURRENT_CORRELATION_ID.set(previousCorrelationId);
            } else {
                CURRENT_CORRELATION_ID.remove();
            }
            if (previousTaskId != null) {
                CURRENT_TASK_ID.set(previousTaskId);
            } else {
                CURRENT_TASK_ID.remove();
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
