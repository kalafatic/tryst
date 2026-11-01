package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing the outcome of an executed task or operation.
 */
public class ExecutionResult {

    private final String id;
    private final String taskId;
    private final int exitCode;
    private final String summary;
    private final String output;
    private final String errorOutput;
    private final String diagnosticInfo;
    private final long durationMs;
    private final Instant completedAt;

    public ExecutionResult(String taskId, int exitCode, String output, String errorOutput, long durationMs) {
        this(UUID.randomUUID().toString(), taskId, exitCode, output, errorOutput, durationMs, Instant.now());
    }

    public ExecutionResult(String id, String taskId, int exitCode, String output, String errorOutput, long durationMs, Instant completedAt) {
        this(id, taskId, exitCode, exitCode == 0 ? "Task completed successfully" : "Task execution failed with code " + exitCode,
                output, errorOutput, "", durationMs, completedAt);
    }

    public ExecutionResult(String id, String taskId, int exitCode, String summary, String output, String errorOutput,
                           String diagnosticInfo, long durationMs, Instant completedAt) {
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new TrystDomainException("ExecutionResult taskId must not be null or blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.taskId = taskId.trim();
        this.exitCode = exitCode;
        this.summary = summary != null ? summary.trim() : "";
        this.output = output != null ? output : "";
        this.errorOutput = errorOutput != null ? errorOutput : "";
        this.diagnosticInfo = diagnosticInfo != null ? diagnosticInfo.trim() : "";
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

    public String getSummary() {
        return summary;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorOutput() {
        return errorOutput;
    }

    public String getDiagnosticInfo() {
        return diagnosticInfo;
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
                ", summary='" + summary + '\'' +
                ", durationMs=" + durationMs +
                '}';
    }
}
