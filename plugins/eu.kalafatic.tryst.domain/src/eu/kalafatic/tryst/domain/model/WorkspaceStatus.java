package eu.kalafatic.tryst.domain.model;

/**
 * Working state and explicit lifecycle status of an isolated workspace or sandbox.
 * Defined workspace states:
 * ALLOCATED, INITIALIZING, READY, ACTIVE, WAITING_FOR_REVIEW, REWORKING, COMPLETED, ARCHIVED, CLEANUP_PENDING, FAILED
 */
public enum WorkspaceStatus {
    ALLOCATED,
    INITIALIZING,
    READY,
    ACTIVE,
    WAITING_FOR_REVIEW,
    REWORKING,
    COMPLETED,
    ARCHIVED,
    CLEANUP_PENDING,
    FAILED,

    // Backward-compatibility aliases for legacy tests/views if referenced
    INACTIVE,
    LOCKED,
    CLOSED;

    public boolean isActive() {
        return this == ACTIVE || this == INITIALIZING || this == READY || this == REWORKING;
    }

    public boolean isSafeToCleanup() {
        return this == COMPLETED || this == ARCHIVED || this == CLEANUP_PENDING || this == FAILED || this == CLOSED;
    }

    public boolean isTerminal() {
        return this == ARCHIVED || this == CLEANUP_PENDING || this == CLOSED;
    }
}
