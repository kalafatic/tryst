package eu.kalafatic.tryst.domain.schedule;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a persistent scheduled or recurring task.
 */
public class TaskSchedule {

    public enum Status {
        ACTIVE,
        PAUSED,
        COMPLETED,
        CANCELLED
    }

    private final String id;
    private final String name;
    private final String projectId;
    private final String userId;
    private final ScheduleType scheduleType;
    private final String cronExpression;
    private Instant scheduledTime;
    private final String taskPayloadJson;
    private Status status;
    private Instant lastExecutedAt;
    private Instant nextExecutionAt;
    private int executionCount;
    private final Instant createdAt;

    public TaskSchedule(String name, String projectId, String userId, ScheduleType scheduleType,
                        String cronExpression, Instant scheduledTime, String taskPayloadJson) {
        this(UUID.randomUUID().toString(), name, projectId, userId, scheduleType, cronExpression,
                scheduledTime, taskPayloadJson, Status.ACTIVE, null, scheduledTime, 0, Instant.now());
    }

    public TaskSchedule(String id, String name, String projectId, String userId,
                        ScheduleType scheduleType, String cronExpression, Instant scheduledTime,
                        String taskPayloadJson, Status status, Instant lastExecutedAt,
                        Instant nextExecutionAt, int executionCount, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Scheduled Task";
        this.projectId = projectId;
        this.userId = userId;
        this.scheduleType = scheduleType != null ? scheduleType : ScheduleType.ONCE;
        this.cronExpression = cronExpression;
        this.scheduledTime = scheduledTime;
        this.taskPayloadJson = taskPayloadJson != null ? taskPayloadJson : "{}";
        this.status = status != null ? status : Status.ACTIVE;
        this.lastExecutedAt = lastExecutedAt;
        this.nextExecutionAt = nextExecutionAt != null ? nextExecutionAt : scheduledTime;
        this.executionCount = Math.max(0, executionCount);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getUserId() {
        return userId;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public Instant getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(Instant scheduledTime) {
        this.scheduledTime = scheduledTime;
        this.nextExecutionAt = scheduledTime;
    }

    public String getTaskPayloadJson() {
        return taskPayloadJson;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public Instant getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(Instant lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
    }

    public Instant getNextExecutionAt() {
        return nextExecutionAt;
    }

    public void setNextExecutionAt(Instant nextExecutionAt) {
        this.nextExecutionAt = nextExecutionAt;
    }

    public int getExecutionCount() {
        return executionCount;
    }

    public void incrementExecutionCount() {
        this.executionCount++;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskSchedule that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
