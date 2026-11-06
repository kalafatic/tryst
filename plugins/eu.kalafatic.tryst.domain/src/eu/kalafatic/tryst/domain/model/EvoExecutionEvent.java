package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing an event received from EVO during an execution,
 * persisted for durable audit, deduplication, and cursor-based recovery.
 */
public class EvoExecutionEvent {

    private final String eventId;
    private final String executionId; // Foreign key to evo_executions(id)
    private final long sequence;
    private final String eventType;
    private final String correlationId;
    private final Instant timestamp;
    private final String dataJson;

    public EvoExecutionEvent(
            String eventId,
            String executionId,
            long sequence,
            String eventType,
            String correlationId,
            Instant timestamp,
            String dataJson
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.sequence = sequence;
        this.eventType = eventType != null ? eventType : "UNKNOWN";
        this.correlationId = correlationId != null ? correlationId : "";
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.dataJson = dataJson != null ? dataJson : "{}";
    }

    public String getEventId() {
        return eventId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getEventType() {
        return eventType;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getDataJson() {
        return dataJson;
    }
}
