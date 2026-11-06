package eu.kalafatic.tryst.core.port;

/**
 * Interface for presenting port collision dialog or prompt to the user
 * or headless console.
 */
@FunctionalInterface
public interface PortConflictPrompt {

    /**
     * Solicits a resolution from the user regarding the reported port conflicts.
     *
     * @param report the collision diagnostics and candidate alternatives
     * @return the chosen action (FREE_PORT, ALLOCATE_ALTERNATIVE, or ABORT)
     */
    PortConflictAction promptResolution(PortConflictReport report);
}
