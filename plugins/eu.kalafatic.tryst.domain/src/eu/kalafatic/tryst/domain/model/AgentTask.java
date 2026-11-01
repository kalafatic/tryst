package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.TrystException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an autonomous development task.
 */
public class AgentTask {

    private final String id;
    private final String projectId;
    private final String sessionId;
    private final String title;
    private final String description;
    private ExecutionStatus status;
    private final Instant createdAt;
    private Instant completedAt;

    public AgentTask(String title, String description) {
        this(UUID.randomUUID().toString(), null, null, title, description, ExecutionStatus.IDLE, Instant.now(), null);
    }

    public AgentTask(String id, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, null, null, title, description, status, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.description = description != null ? description : "";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void start() {
        if (this.status != ExecutionStatus.IDLE) {
            throw new TrystException("Cannot start task in status: " + this.status);
        }
        this.status = ExecutionStatus.RUNNING;
    }

    public void complete() {
        if (this.status != ExecutionStatus.RUNNING) {
            throw new TrystException("Cannot complete task that is not running: " + this.status);
        }
        this.status = ExecutionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        if (this.status != ExecutionStatus.RUNNING) {
            throw new TrystException("Cannot fail task that is not running: " + this.status);
        }
        this.status = ExecutionStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        if (this.status == ExecutionStatus.COMPLETED || this.status == ExecutionStatus.FAILED) {
            throw new TrystException("Cannot cancel terminal task: " + this.status);
        }
        this.status = ExecutionStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentTask agentTask)) return false;
        return Objects.equals(id, agentTask.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AgentTask{" +
                "id='" + id + '\'' +
                ", projectId='" + projectId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", title='" + title + '\'' +
                ", status=" + status +
                '}';
    }
}
