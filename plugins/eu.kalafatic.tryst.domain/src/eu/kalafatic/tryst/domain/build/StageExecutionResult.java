package eu.kalafatic.tryst.domain.build;

import java.util.List;
import java.util.Objects;

/**
 * Result of an individual build, test, or verification stage execution.
 */
public record StageExecutionResult(
        String stageName,
        BuildExecutionStatus status,
        int exitCode,
        List<String> command,
        String stdout,
        String stderr,
        long durationMs,
        String failureReason
) {
    public StageExecutionResult {
        Objects.requireNonNull(stageName, "stageName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        command = command != null ? List.copyOf(command) : List.of();
        stdout = stdout != null ? stdout : "";
        stderr = stderr != null ? stderr : "";
        failureReason = failureReason != null ? failureReason : "";
    }

    public static StageExecutionResult notExecuted(String stageName, String reason) {
        return new StageExecutionResult(stageName, BuildExecutionStatus.NOT_EXECUTED, 0, List.of(), "", "", 0L, reason);
    }
}
