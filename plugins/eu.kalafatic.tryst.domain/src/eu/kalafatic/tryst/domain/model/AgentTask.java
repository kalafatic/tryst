package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a unit of work requested by the user or generated during planning.
 * Encapsulates lifecycle transitions, priority, execution result reference, and agent assignment.
 */
public class AgentTask {

    private final String id;
    private final String projectId;
    private final String sessionId;
    private final String title;
    private final String description;
    private TaskStatus status;
    private TaskPriority priority;
    private String agentId;
    private String executionResultId;
    private final Instant createdAt;
    private Instant completedAt;

    public AgentTask(String title, String description) {
        this(UUID.randomUUID().toString(), null, null, title, description, TaskStatus.CREATED, TaskPriority.NORMAL, null, null, Instant.now(), null);
    }

    public AgentTask(String id, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, null, null, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String executionResultId,
                     Instant createdAt, Instant completedAt) {
        if (title == null || title.trim().isEmpty()) {
            throw new TrystDomainException("Task title must not be null or blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = projectId != null && !projectId.trim().isEmpty() ? projectId.trim() : null;
        this.sessionId = sessionId != null && !sessionId.trim().isEmpty() ? sessionId.trim() : null;
        this.title = title.trim();
        this.description = description != null ? description.trim() : "";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.priority = priority != null ? priority : TaskPriority.NORMAL;
        this.agentId = agentId != null && !agentId.trim().isEmpty() ? agentId.trim() : null;
        this.executionResultId = executionResultId != null && !executionResultId.trim().isEmpty() ? executionResultId.trim() : null;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = completedAt;
    }

    private static TaskStatus fromExecutionStatus(ExecutionStatus es) {
        if (es == null) {
            return TaskStatus.CREATED;
        }
        return switch (es) {
            case IDLE -> TaskStatus.CREATED;
            case RUNNING -> TaskStatus.RUNNING;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
        };
    }

    private static ExecutionStatus toExecutionStatus(TaskStatus ts) {
        return switch (ts) {
            case CREATED, PLANNED, READY, WAITING_FOR_APPROVAL -> ExecutionStatus.IDLE;
            case RUNNING -> ExecutionStatus.RUNNING;
            case COMPLETED -> ExecutionStatus.COMPLETED;
            case FAILED -> ExecutionStatus.FAILED;
            case CANCELLED -> ExecutionStatus.CANCELLED;
        };
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

    public TaskStatus getTaskStatus() {
        return status;
    }

    public ExecutionStatus getStatus() {
        return toExecutionStatus(status);
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot change priority of terminal task in status: " + this.status);
        }
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
    }

    public String getAgentId() {
        return agentId;
    }

    public void assignAgent(String agentId) {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot assign agent to terminal task in status: " + this.status);
        }
        this.agentId = agentId;
    }

    public String getExecutionResultId() {
        return executionResultId;
    }

    public void setExecutionResultId(String executionResultId) {
        this.executionResultId = executionResultId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void plan() {
        if (this.status != TaskStatus.CREATED) {
            throw new TrystDomainException("Cannot plan task from status: " + this.status);
        }
        this.status = TaskStatus.PLANNED;
    }

    public void markReady() {
        if (this.status != TaskStatus.CREATED && this.status != TaskStatus.PLANNED) {
            throw new TrystDomainException("Cannot mark task ready from status: " + this.status);
        }
        this.status = TaskStatus.READY;
    }

    public void start() {
        if (this.status != TaskStatus.CREATED && this.status != TaskStatus.PLANNED && this.status != TaskStatus.READY) {
            throw new TrystDomainException("Cannot start task in status: " + this.status);
        }
        this.status = TaskStatus.RUNNING;
    }

    public void requestApproval() {
        if (this.status != TaskStatus.RUNNING) {
            throw new TrystDomainException("Cannot request approval for task not running: " + this.status);
        }
        this.status = TaskStatus.WAITING_FOR_APPROVAL;
    }

    public void approve() {
        if (this.status != TaskStatus.WAITING_FOR_APPROVAL) {
            throw new TrystDomainException("Cannot approve task not waiting for approval: " + this.status);
        }
        this.status = TaskStatus.RUNNING;
    }

    public void complete() {
        if (this.status != TaskStatus.RUNNING) {
            throw new TrystDomainException("Cannot complete task that is not running: " + this.status);
        }
        this.status = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        if (this.status != TaskStatus.RUNNING && this.status != TaskStatus.WAITING_FOR_APPROVAL) {
            throw new TrystDomainException("Cannot fail task that is not running: " + this.status);
        }
        this.status = TaskStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot cancel terminal task: " + this.status);
        }
        this.status = TaskStatus.CANCELLED;
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
                ", title='" + title + '\'' +
                ", status=" + status +
                ", priority=" + priority +
                ", agentId='" + agentId + '\'' +
                '}';
    }
}
