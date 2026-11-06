package eu.kalafatic.tryst.core.log;

/**
 * Listener interface for subscribing to centralized log events in real-time.
 */
@FunctionalInterface
public interface LogListener {

    /**
     * Invoked when a log entry is emitted.
     * Implementations should return quickly or dispatch asynchronously to avoid blocking log callers.
     *
     * @param entry the structured log entry
     */
    void onLogEntry(LogEntry entry);
}
