package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.service.TaskStateTransitionValidator;

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
    private String workerId;
    private String executionResultId;
    private String reviewId;
    private int reworkCount;
    private String failureReason;
    private final Instant createdAt;
    private Instant completedAt;

    public AgentTask(String title, String description) {
        this(UUID.randomUUID().toString(), null, null, title, description, TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null, Instant.now(), null);
    }

    public AgentTask(String id, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, null, null, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, null, null, 0, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description, ExecutionStatus status, Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, fromExecutionStatus(status), TaskPriority.NORMAL, null, null, null, null, 0, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String executionResultId,
                     Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, status, priority, agentId, null, executionResultId, null, 0, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String executionResultId,
                     String reviewId, Instant createdAt, Instant completedAt) {
        this(id, projectId, sessionId, title, description, status, priority, agentId, null, executionResultId, reviewId, 0, null, createdAt, completedAt);
    }

    public AgentTask(String id, String projectId, String sessionId, String title, String description,
                     TaskStatus status, TaskPriority priority, String agentId, String workerId,
                     String executionResultId, String reviewId, int reworkCount, String failureReason,
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
        this.workerId = workerId != null && !workerId.trim().isEmpty() ? workerId.trim() : null;
        this.executionResultId = executionResultId != null && !executionResultId.trim().isEmpty() ? executionResultId.trim() : null;
        this.reviewId = reviewId != null && !reviewId.trim().isEmpty() ? reviewId.trim() : null;
        this.reworkCount = reworkCount;
        this.failureReason = failureReason;
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
            case PAUSED -> TaskStatus.PAUSED;
            case ARCHIVED -> TaskStatus.ARCHIVED;
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
            case RUNNING, CLONING, ANALYZING, PLANNING, IMPLEMENTING, BUILDING, TESTING, REWORKING -> ExecutionStatus.RUNNING;
            case WAITING_FOR_REVIEW, REWORK_REQUESTED -> ExecutionStatus.WAITING_FOR_REVIEW;
            case APPROVED -> ExecutionStatus.APPROVED;
            case REJECTED -> ExecutionStatus.REJECTED;
            case COMMITTING -> ExecutionStatus.COMMITTING;
            case PAUSED -> ExecutionStatus.PAUSED;
            case ARCHIVED -> ExecutionStatus.ARCHIVED;
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

    public synchronized TaskStatus getTaskStatus() {
        return status;
    }

    public synchronized ExecutionStatus getStatus() {
        return toExecutionStatus(status);
    }

    public synchronized TaskPriority getPriority() {
        return priority;
    }

    public synchronized void setPriority(TaskPriority priority) {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot change priority of terminal task in status: " + this.status);
        }
        this.priority = Objects.requireNonNull(priority, "priority must not be null");
    }

    public synchronized String getAgentId() {
        return agentId;
    }

    public synchronized void assignAgent(String agentId) {
        if (this.status.isTerminal()) {
            throw new TrystDomainException("Cannot assign agent to terminal task in status: " + this.status);
        }
        this.agentId = agentId;
    }

    public synchronized String getWorkerId() {
        return workerId;
    }

    public synchronized void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public synchronized String getExecutionResultId() {
        return executionResultId;
    }

    public synchronized void setExecutionResultId(String executionResultId) {
        this.executionResultId = executionResultId;
    }

    public synchronized String getReviewId() {
        return reviewId;
    }

    public synchronized void setReviewId(String reviewId) {
        this.reviewId = reviewId;
    }

    public synchronized int getReworkCount() {
        return reworkCount;
    }

    public synchronized void incrementReworkCount() {
        this.reworkCount++;
    }

    public synchronized String getFailureReason() {
        return failureReason;
    }

    public synchronized void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getCompletedAt() {
        return completedAt;
    }

    public synchronized void transitionTo(TaskStatus target) {
        transitionTo(target, null);
    }

    public synchronized void transitionTo(TaskStatus target, String reason) {
        TaskStateTransitionValidator.validateTransition(this.status, target);
        this.status = target;
        if (target == TaskStatus.FAILED) {
            this.failureReason = reason;
            this.completedAt = Instant.now();
        } else if (target == TaskStatus.COMPLETED || target == TaskStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    public synchronized void plan() {
        transitionTo(TaskStatus.PLANNED);
    }

    public synchronized void markReady() {
        transitionTo(TaskStatus.READY);
    }

    public synchronized void queue() {
        transitionTo(TaskStatus.QUEUED);
    }

    public synchronized void claim(String agentOrWorkerId) {
        transitionTo(TaskStatus.CLAIMED);
        this.workerId = agentOrWorkerId;
        this.agentId = agentOrWorkerId;
    }

    public synchronized void start() {
        transitionTo(TaskStatus.RUNNING);
    }

    public synchronized void requestApproval() {
        transitionTo(TaskStatus.WAITING_FOR_APPROVAL);
    }

    public synchronized void waitForReview(String reviewId) {
        transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        this.reviewId = reviewId;
    }

    public synchronized void requestRework(String reason) {
        transitionTo(TaskStatus.REWORK_REQUESTED, reason);
        incrementReworkCount();
    }

    public synchronized void approve() {
        if (this.status == TaskStatus.WAITING_FOR_APPROVAL) {
            transitionTo(TaskStatus.RUNNING);
        } else {
            transitionTo(TaskStatus.APPROVED);
        }
    }

    public synchronized void reject() {
        transitionTo(TaskStatus.REJECTED);
    }

    public synchronized void startCommitting() {
        transitionTo(TaskStatus.COMMITTING);
    }

    public synchronized void complete() {
        transitionTo(TaskStatus.COMPLETED);
    }

    public synchronized void fail() {
        fail(null);
    }

    public synchronized void fail(String reason) {
        transitionTo(TaskStatus.FAILED, reason);
    }

    public synchronized void cancel() {
        transitionTo(TaskStatus.CANCELLED);
    }

    public synchronized void markRecoveryRequired() {
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
                ", workerId='" + workerId + '\'' +
                '}';
    }
}
