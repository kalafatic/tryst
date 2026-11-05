package eu.kalafatic.tryst.domain.build;

/**
 * Canonical execution status for builds and test suites.
 */
public enum BuildExecutionStatus {
    NOT_EXECUTED,
    SUCCESSFUL,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminalFailure() {
        return this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
