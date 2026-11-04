package eu.kalafatic.tryst.core.event;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Standard typed domain and lifecycle events.
 */
public final class ApplicationEvents {

    private ApplicationEvents() {}

    public record ConfigurationChangedEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            ApplicationConfiguration configuration
    ) implements TrystTypedEvent {
        public ConfigurationChangedEvent(ApplicationConfiguration configuration) {
            this(UUID.randomUUID().toString(), Instant.now(), null, Objects.requireNonNull(configuration));
        }
    }

    public record SessionEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String sessionId,
            String eventType,
            String details
    ) implements TrystTypedEvent {
        public SessionEvent(String sessionId, String eventType, String details) {
            this(UUID.randomUUID().toString(), Instant.now(), null, sessionId, eventType, details);
        }
    }

    public record TaskStateChangedEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String taskId,
            String previousState,
            String newState,
            String message
    ) implements TrystTypedEvent {
        public TaskStateChangedEvent(String taskId, String previousState, String newState, String message) {
            this(UUID.randomUUID().toString(), Instant.now(), null, taskId, previousState, newState, message);
        }
    }

    public record AgentExecutionEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String taskId,
            String stage,
            String outputChunk,
            boolean isComplete
    ) implements TrystTypedEvent {
        public AgentExecutionEvent(String taskId, String correlationId, String stage, String outputChunk, boolean isComplete) {
            this(UUID.randomUUID().toString(), Instant.now(), correlationId, taskId, stage, outputChunk, isComplete);
        }
    }

    public record ToolExecutionEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String toolName,
            String parameters,
            boolean successful,
            String resultSummary
    ) implements TrystTypedEvent {
        public ToolExecutionEvent(String correlationId, String toolName, String parameters, boolean successful, String resultSummary) {
            this(UUID.randomUUID().toString(), Instant.now(), correlationId, toolName, parameters, successful, resultSummary);
        }
    }

    public record BuildAndTestEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String stage,
            int exitCode,
            String summary
    ) implements TrystTypedEvent {
        public BuildAndTestEvent(String correlationId, String stage, int exitCode, String summary) {
            this(UUID.randomUUID().toString(), Instant.now(), correlationId, stage, exitCode, summary);
        }
    }

    public record NotificationBroadcastEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String notificationId,
            String userId,
            String type,
            String title,
            String message,
            String referenceType,
            String referenceId,
            long sequenceNumber
    ) implements TrystTypedEvent {
        public NotificationBroadcastEvent(String notificationId, String userId, String type, String title,
                                          String message, String referenceType, String referenceId, long sequenceNumber) {
            this(UUID.randomUUID().toString(), Instant.now(), null, notificationId, userId, type, title, message, referenceType, referenceId, sequenceNumber);
        }
    }
}
