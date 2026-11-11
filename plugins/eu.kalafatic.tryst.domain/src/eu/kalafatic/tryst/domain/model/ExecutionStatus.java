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
    PAUSED,
    ARCHIVED,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED
}
