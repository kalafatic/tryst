package eu.kalafatic.tryst.infrastructure.server;

/**
 * Service contract for the TRYST embedded HTTP communication server.
 * Delivers REST APIs, health checks, event streams, and static web assets.
 */
public interface TrystHttpService {

    /**
     * Starts the HTTP server on the configured host and port.
     */
    void start();

    /**
     * Stops the HTTP server and disposes server resources.
     */
    void stop();

    /**
     * Checks if the HTTP server is currently running.
     *
     * @return true if operational, false otherwise.
     */
    boolean isRunning();

    /**
     * Gets the actual port on which the HTTP server is listening.
     *
     * @return port number.
     */
    int getPort();

    /**
     * Gets the bind host address.
     *
     * @return host address string.
     */
    String getHost();
}
