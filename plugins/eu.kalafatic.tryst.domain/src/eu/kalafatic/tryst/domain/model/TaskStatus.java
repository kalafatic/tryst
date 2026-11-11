package eu.kalafatic.tryst.domain.model;

/**
 * Task execution lifecycle status transitions.
 * Explicit states:
 * CREATED, QUEUED, CLAIMED, CLONING, ANALYZING, PLANNING, IMPLEMENTING,
 * BUILDING, TESTING, WAITING_FOR_REVIEW, REWORK_REQUESTED, REWORKING,
 * APPROVED, REJECTED, COMMITTING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED.
 * (Also maintains PLANNED, READY, WAITING_FOR_APPROVAL, RUNNING for backward compatibility).
 */
public enum TaskStatus {
    CREATED,
    PLANNED,
    READY,
    QUEUED,
    CLAIMED,
    RUNNING,
    CLONING,
    ANALYZING,
    PLANNING,
    IMPLEMENTING,
    BUILDING,
    TESTING,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_REVIEW,
    REWORK_REQUESTED,
    REWORKING,
    APPROVED,
    REJECTED,
    COMMITTING,
    PAUSED,
    ARCHIVED,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED;

    /**
     * Checks if this is a terminal state from which no further normal progression can be made.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == ARCHIVED;
    }

    /**
     * Checks if this state represents an active execution in progress.
     */
    public boolean isActive() {
        return this == RUNNING || this == CLAIMED || this == CLONING || this == ANALYZING || this == PLANNING
                || this == IMPLEMENTING || this == BUILDING || this == TESTING
                || this == REWORKING || this == COMMITTING;
    }

    /**
     * Checks if this state requires restart reconciliation if detected on startup.
     */
    public boolean requiresRecoveryOnRestart() {
        return isActive() || this == QUEUED;
    }
}
