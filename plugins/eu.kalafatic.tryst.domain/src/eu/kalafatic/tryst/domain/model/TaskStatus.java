package eu.kalafatic.tryst.domain.model;

/**
 * Task execution lifecycle status transitions.
 */
public enum TaskStatus {
    CREATED,
    PLANNED,
    READY,
    RUNNING,
    WAITING_FOR_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * Checks if this is a terminal state from which no further transitions can be made.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
