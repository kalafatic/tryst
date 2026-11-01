package eu.kalafatic.tryst.domain.model;

/**
 * High-level execution status classification.
 */
public enum ExecutionStatus {
    IDLE,
    QUEUED,
    CLAIMED,
    RUNNING,
    WAITING_FOR_REVIEW,
    APPROVED,
    REJECTED,
    COMMITTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED
}
