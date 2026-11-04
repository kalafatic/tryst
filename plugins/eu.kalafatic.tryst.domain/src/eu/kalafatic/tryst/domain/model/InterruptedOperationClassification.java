package eu.kalafatic.tryst.domain.model;

/**
 * Granular classification of interrupted operations across TRYST subsystems.
 */
public enum InterruptedOperationClassification {
    /** Interrupted while executing remote or local Git commands (clone, fetch, checkout, branch, commit) */
    INTERRUPTED_GIT_OPERATION,

    /** Interrupted while awaiting streaming or synchronous AI inference response */
    INTERRUPTED_AI_REQUEST,

    /** Interrupted while running compiler, build tool (Maven/Gradle/npm), or test suite */
    INTERRUPTED_BUILD,

    /** Interrupted due to unexpected operating system termination, crash, or SIGKILL */
    OS_TERMINATION,

    /** Interrupted due to database connection timeout or transactional conflict */
    INTERRUPTED_DATABASE_TX,

    /** Interrupted due to client browser disconnection (task continues or is preserved) */
    CLIENT_DISCONNECTION,

    /** Clean unclassified or idle interruption */
    UNCLASSIFIED
}
