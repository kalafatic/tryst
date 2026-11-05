package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.CorrelationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory ring-buffer diagnostic logging service capturing application events,
 * logs, and providing bounded query, correlation tracking, and search capabilities.
 */
public class LogDiagnosticService {

    public record LogEntry(
            long sequence,
            Instant timestamp,
            String level,
            String component,
            String message,
            String details,
            String correlationId,
            String taskId
    ) {}

    private static final int MAX_BUFFER_SIZE = 1000;
    private final ConcurrentLinkedDeque<LogEntry> logBuffer = new ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);

    public void log(String level, String component, String message, String details) {
        log(level, component, message, details, CorrelationContext.getCorrelationId(), CorrelationContext.getTaskId());
    }

    public void log(String level, String component, String message, String details, String correlationId, String taskId) {
        String cleanMessage = redactSensitive(message);
        String cleanDetails = redactSensitive(details);
        LogEntry entry = new LogEntry(
                seq.incrementAndGet(),
                Instant.now(),
                level,
                component,
                cleanMessage,
                cleanDetails,
                correlationId,
                taskId
        );
        logBuffer.add(entry);
        while (logBuffer.size() > MAX_BUFFER_SIZE) {
            logBuffer.pollFirst();
        }
    }

    public List<LogEntry> query(String level, String component, String search, int limit) {
        return query(level, component, search, null, null, limit);
    }

    public List<LogEntry> query(String level, String component, String search, String correlationId, String taskId, int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = 0;
        int max = Math.min(MAX_BUFFER_SIZE, Math.max(1, limit));

        for (LogEntry entry : logBuffer) {
            if (level != null && !level.isBlank() && !entry.level().equalsIgnoreCase(level.trim())) {
                continue;
            }
            if (component != null && !component.isBlank() && !entry.component().toLowerCase().contains(component.trim().toLowerCase())) {
                continue;
            }
            if (correlationId != null && !correlationId.isBlank() && !correlationId.equals(entry.correlationId())) {
                continue;
            }
            if (taskId != null && !taskId.isBlank() && !taskId.equals(entry.taskId())) {
                continue;
            }
            if (search != null && !search.isBlank()) {
                String s = search.toLowerCase();
                boolean matches = (entry.message() != null && entry.message().toLowerCase().contains(s))
                        || (entry.details() != null && entry.details().toLowerCase().contains(s));
                if (!matches) continue;
            }
            result.add(entry);
            count++;
            if (count >= max) break;
        }
        return result;
    }

    private static String redactSensitive(String text) {
        if (text == null) return null;
        return text.replaceAll("(?i)(password|secret|token|bearer|authorization)\\s*[:=]\\s*['\"]?[^'\"\\s]+", "$1=[REDACTED]");
    }
}
