package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain event recording lifecycle stage transitions, progress, and diagnostic logs for a task.
 */
public final class TaskEvent {

    private final String id;
    private final String taskId;
    private final Instant timestamp;
    private final TaskStatus status;
    private final String message;
    private final String details;

    public TaskEvent(String taskId, TaskStatus status, String message) {
        this(UUID.randomUUID().toString(), taskId, Instant.now(), status, message, null);
    }

    public TaskEvent(String taskId, TaskStatus status, String message, String details) {
        this(UUID.randomUUID().toString(), taskId, Instant.now(), status, message, details);
    }

    public TaskEvent(String id, String taskId, Instant timestamp, TaskStatus status, String message, String details) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.message = message != null ? message : "";
        this.details = details;
    }

    public String getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskEvent taskEvent)) return false;
        return Objects.equals(id, taskEvent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskEvent{" +
                "id='" + id + '\'' +
                ", taskId='" + taskId + '\'' +
                ", timestamp=" + timestamp +
                ", status=" + status +
                ", message='" + message + '\'' +
                '}';
    }
}
