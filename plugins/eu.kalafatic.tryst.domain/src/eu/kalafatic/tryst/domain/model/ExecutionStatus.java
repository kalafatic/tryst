package eu.kalafatic.tryst.domain.model;

/**
 * Task execution lifecycle status.
 */
public enum ExecutionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
