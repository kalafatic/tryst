package eu.kalafatic.tryst.domain.model;

/**
 * Task execution lifecycle status transitions.
 * Fully supports the complete autonomous development lifecycle:
 * QUEUED, CLAIMED, RUNNING, WAITING_FOR_REVIEW, APPROVED, REJECTED, COMMITTING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED.
 */
public enum TaskStatus {
    CREATED,
    PLANNED,
    READY,
    QUEUED,
    CLAIMED,
    RUNNING,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_REVIEW,
    APPROVED,
    REJECTED,
    COMMITTING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERY_REQUIRED;

    /**
     * Checks if this is a terminal state from which no further normal progression can be made.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
