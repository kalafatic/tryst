package eu.kalafatic.tryst.domain.model;

/**
 * Execution and persistence lifecycle status of a session.
 */
public enum SessionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CLOSED,
    TERMINATED
}
