package eu.kalafatic.tryst.domain.build;

import java.util.List;
import java.util.Objects;

/**
 * Structured quality report capturing technology detection, build results,
 * test suite results, stdout/stderr, and failure classifications.
 */
public record QualityReport(
        BuildSystemType buildSystem,
        BuildExecutionStatus buildStatus,
        BuildExecutionStatus testStatus,
        boolean testsExecuted,
        List<StageExecutionResult> stageResults,
        String summary,
        String failureDetails
) {
    public QualityReport {
        Objects.requireNonNull(buildSystem, "buildSystem must not be null");
        Objects.requireNonNull(buildStatus, "buildStatus must not be null");
        Objects.requireNonNull(testStatus, "testStatus must not be null");
        stageResults = stageResults != null ? List.copyOf(stageResults) : List.of();
        summary = summary != null ? summary : "";
        failureDetails = failureDetails != null ? failureDetails : "";
    }

    public boolean isOverallSuccess() {
        return buildStatus == BuildExecutionStatus.SUCCESSFUL &&
                (testStatus == BuildExecutionStatus.SUCCESSFUL || testStatus == BuildExecutionStatus.NOT_EXECUTED);
    }
}
