package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.service.TaskStateTransitionValidator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an autonomous task execution unit.
 * Enforces explicit lifecycle transitions via TaskStateTransitionValidator.
 */
public class Task {

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

    public Task(String title, String description) {
        this(UUID.randomUUID().toString(), null, null, title, description, TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null, Instant.now(), null);
    }

    public Task(String id, String projectId, String sessionId, String title, String description,
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

    public synchronized TaskStatus getStatus() {
        return status;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getCompletedAt() {
        return completedAt;
    }

    public synchronized void transitionTo(TaskStatus targetStatus) {
        transitionTo(targetStatus, null);
    }

    public synchronized void transitionTo(TaskStatus targetStatus, String reason) {
        TaskStateTransitionValidator.validateTransition(this.status, targetStatus);
        this.status = targetStatus;
        if (targetStatus == TaskStatus.FAILED) {
            this.failureReason = reason;
            this.completedAt = Instant.now();
        } else if (targetStatus == TaskStatus.COMPLETED || targetStatus == TaskStatus.CANCELLED) {
            this.completedAt = Instant.now();
        }
    }

    public AgentTask toAgentTask() {
        return new AgentTask(id, projectId, sessionId, title, description,
                status, priority, agentId, workerId, executionResultId, reviewId, reworkCount, failureReason, createdAt, completedAt);
    }

    public static Task fromAgentTask(AgentTask at) {
        if (at == null) return null;
        return new Task(at.getId(), at.getProjectId(), at.getSessionId(), at.getTitle(), at.getDescription(),
                at.getTaskStatus(), at.getPriority(), at.getAgentId(), at.getWorkerId(), at.getExecutionResultId(),
                at.getReviewId(), at.getReworkCount(), at.getFailureReason(), at.getCreatedAt(), at.getCompletedAt());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Task task)) return false;
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", status=" + status +
                ", priority=" + priority +
                ", workerId='" + workerId + '\'' +
                '}';
    }
}
