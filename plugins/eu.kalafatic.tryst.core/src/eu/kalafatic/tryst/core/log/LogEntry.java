package eu.kalafatic.tryst.core.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * Immutable structured record representing an application log entry.
 */
public record LogEntry(
        long sequence,
        Instant timestamp,
        LogLevel level,
        String category,
        String bundleId,
        String threadName,
        String message,
        String correlationId,
        String taskId,
        Throwable throwable,
        String formattedStackTrace
) {
    public static LogEntry create(
            long sequence,
            Instant timestamp,
            LogLevel level,
            String category,
            String bundleId,
            String threadName,
            String message,
            String correlationId,
            String taskId,
            Throwable throwable
    ) {
        String stackTrace = null;
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            stackTrace = sw.toString();
        }
        return new LogEntry(
                sequence,
                timestamp != null ? timestamp : Instant.now(),
                level != null ? level : LogLevel.INFO,
                category != null ? category : "TRYST",
                bundleId != null ? bundleId : "eu.kalafatic.tryst.core",
                threadName != null ? threadName : Thread.currentThread().getName(),
                message != null ? message : "",
                correlationId,
                taskId,
                throwable,
                stackTrace
        );
    }
}
