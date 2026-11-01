package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing the execution result of an AgentTask.
 */
public class ExecutionResult {

    private final String id;
    private final String taskId;
    private final int exitCode;
    private final String output;
    private final String errorOutput;
    private final long durationMs;
    private final Instant completedAt;

    public ExecutionResult(String taskId, int exitCode, String output, String errorOutput, long durationMs) {
        this(UUID.randomUUID().toString(), taskId, exitCode, output, errorOutput, durationMs, Instant.now());
    }

    public ExecutionResult(String id, String taskId, int exitCode, String output, String errorOutput, long durationMs, Instant completedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.exitCode = exitCode;
        this.output = output != null ? output : "";
        this.errorOutput = errorOutput != null ? errorOutput : "";
        this.durationMs = durationMs;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorOutput() {
        return errorOutput;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExecutionResult that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ExecutionResult{" +
                "id='" + id + '\'' +
                ", taskId='" + taskId + '\'' +
                ", exitCode=" + exitCode +
                ", durationMs=" + durationMs +
                '}';
    }
}
