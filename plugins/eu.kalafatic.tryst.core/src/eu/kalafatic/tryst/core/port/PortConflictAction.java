package eu.kalafatic.tryst.core.port;

/**
 * User decision when handling a detected port collision.
 */
public enum PortConflictAction {
    /**
     * Terminate the conflicting process (requires explicit user confirmation and permissions).
     */
    FREE_PORT,

    /**
     * Allocate the next coherent alternative port group.
     */
    ALLOCATE_ALTERNATIVE,

    /**
     * Abort startup.
     */
    ABORT
}
