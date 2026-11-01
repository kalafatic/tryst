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
    private String reviewId;
    private final Instant createdAt;
    private Instant completedAt;

    public AgentTask(String title, String description) {
        this(UUID.randomUUID().toString(), null, null, title, description, TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, Instant.now(), null);
    }

    public AgentTask(String id, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, null, null, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String executionResultId,
                     Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, status, priority, agentId, executionResultId, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String executionResultId,
                     String reviewId, Instant createdAt, Instant completedAt) {
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
        this.reviewId = reviewId != null && !reviewId.trim().isEmpty() ? reviewId.trim() : null;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.completedAt = completedAt;
    }

    private static TaskStatus fromExecutionStatus(ExecutionStatus es) {
        if (es == null) {
            return TaskStatus.CREATED;
        }
        return switch (es) {
            case IDLE -> TaskStatus.CREATED;
            case QUEUED -> TaskStatus.QUEUED;
            case CLAIMED -> TaskStatus.CLAIMED;
            case RUNNING -> TaskStatus.RUNNING;
            case WAITING_FOR_REVIEW -> TaskStatus.WAITING_FOR_REVIEW;
            case APPROVED -> TaskStatus.APPROVED;
            case REJECTED -> TaskStatus.REJECTED;
            case COMMITTING -> TaskStatus.COMMITTING;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case RECOVERY_REQUIRED -> TaskStatus.RECOVERY_REQUIRED;
        };
    }

    private static ExecutionStatus toExecutionStatus(TaskStatus ts) {
        return switch (ts) {
            case CREATED, PLANNED, READY, WAITING_FOR_APPROVAL -> ExecutionStatus.IDLE;
            case QUEUED -> ExecutionStatus.QUEUED;
            case CLAIMED -> ExecutionStatus.CLAIMED;
            case RUNNING -> ExecutionStatus.RUNNING;
            case WAITING_FOR_REVIEW -> ExecutionStatus.WAITING_FOR_REVIEW;
            case APPROVED -> ExecutionStatus.APPROVED;
            case REJECTED -> ExecutionStatus.REJECTED;
            case COMMITTING -> ExecutionStatus.COMMITTING;
            case COMPLETED -> ExecutionStatus.COMPLETED;
            case FAILED -> ExecutionStatus.FAILED;
            case CANCELLED -> ExecutionStatus.CANCELLED;
            case RECOVERY_REQUIRED -> ExecutionStatus.RECOVERY_REQUIRED;
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

    public String getReviewId() {
        return reviewId;
    }

    public void setReviewId(String reviewId) {
        this.reviewId = reviewId;
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

    public void queue() {
        if (this.status != TaskStatus.CREATED && this.status != TaskStatus.PLANNED && this.status != TaskStatus.READY && this.status != TaskStatus.REJECTED) {
            throw new TrystDomainException("Cannot queue task from status: " + this.status);
        }
        this.status = TaskStatus.QUEUED;
    }

    public void claim(String agentId) {
        if (this.status != TaskStatus.QUEUED && this.status != TaskStatus.READY && this.status != TaskStatus.CREATED) {
            throw new TrystDomainException("Cannot claim task from status: " + this.status);
        }
        this.status = TaskStatus.CLAIMED;
        this.agentId = agentId;
    }

    public void start() {
        if (this.status != TaskStatus.CREATED && this.status != TaskStatus.PLANNED && this.status != TaskStatus.READY
                && this.status != TaskStatus.QUEUED && this.status != TaskStatus.CLAIMED && this.status != TaskStatus.APPROVED) {
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

    public void waitForReview(String reviewId) {
        if (this.status != TaskStatus.RUNNING && this.status != TaskStatus.WAITING_FOR_APPROVAL) {
            throw new TrystDomainException("Cannot transition to WAITING_FOR_REVIEW from status: " + this.status);
        }
        this.status = TaskStatus.WAITING_FOR_REVIEW;
        this.reviewId = reviewId;
    }

    public void approve() {
        if (this.status != TaskStatus.WAITING_FOR_APPROVAL && this.status != TaskStatus.WAITING_FOR_REVIEW) {
            throw new TrystDomainException("Cannot approve task not waiting for review/approval: " + this.status);
        }
        if (this.status == TaskStatus.WAITING_FOR_APPROVAL) {
            this.status = TaskStatus.RUNNING;
        } else {
            this.status = TaskStatus.APPROVED;
        }
    }

    public void reject() {
        if (this.status != TaskStatus.WAITING_FOR_APPROVAL && this.status != TaskStatus.WAITING_FOR_REVIEW && this.status != TaskStatus.APPROVED) {
            throw new TrystDomainException("Cannot reject task from status: " + this.status);
        }
        this.status = TaskStatus.REJECTED;
    }

    public void startCommitting() {
        if (this.status != TaskStatus.APPROVED) {
            throw new TrystDomainException("Cannot commit task unless approved: " + this.status);
        }
        this.status = TaskStatus.COMMITTING;
    }

    public void complete() {
        if (this.status != TaskStatus.RUNNING && this.status != TaskStatus.COMMITTING && this.status != TaskStatus.APPROVED) {
            throw new TrystDomainException("Cannot complete task in status: " + this.status);
        }
        this.status = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot fail terminal task: " + this.status);
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

    public void markRecoveryRequired() {
        if (this.status.isTerminal()) {
            return;
        }
        this.status = TaskStatus.RECOVERY_REQUIRED;
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
