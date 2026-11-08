package eu.kalafatic.tryst.core.server;

/**
 * Authoritative lifecycle management contract for the TRYST server runtime.
 * Exposing this interface in the core bundle enables decoupled consumers (such as
 * the Eclipse RCP UI) to trigger, query, and coordinate the authoritative HTTP server
 * lifecycle through the OSGi Service Registry without directly coupling to or reflecting
 * application-tier coordinator implementation classes.
 */
public interface TrystServerLifecycleService {

    /**
     * Starts or ensures running state of the authoritative embedded HTTP server.
     * Guaranteed to be thread-safe, idempotent, and serialized.
     *
     * @return the running {@link TrystHttpService} instance.
     * @throws Exception if server startup fails or port allocation cannot be resolved.
     */
    TrystHttpService startHttpServer() throws Exception;

    /**
     * Stops the authoritative embedded HTTP server safely.
     *
     * @throws Exception if an error occurs during shutdown.
     */
    void stopHttpServer() throws Exception;

    /**
     * Restarts the authoritative embedded HTTP server safely.
     *
     * @return the restarted {@link TrystHttpService} instance.
     * @throws Exception if restart fails.
     */
    TrystHttpService restartHttpServer() throws Exception;

    /**
     * Retrieves the authoritative {@link TrystHttpService} if initialized, or {@code null}.
     *
     * @return current HTTP service instance or {@code null}.
     */
    TrystHttpService getHttpService();

    /**
     * Checks whether the authoritative HTTP server is actively running and listening.
     *
     * @return {@code true} if running, {@code false} otherwise.
     */
    boolean isHttpServerRunning();
}
