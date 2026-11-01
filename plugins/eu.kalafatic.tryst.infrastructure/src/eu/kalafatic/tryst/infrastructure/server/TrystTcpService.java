package eu.kalafatic.tryst.infrastructure.server;

/**
 * Service contract for the TRYST TCP communication server.
 * Manages socket lifecycle and client connections.
 */
public interface TrystTcpService {

    /**
     * Starts the TCP server listening on the configured host and port.
     */
    void start();

    /**
     * Stops the TCP server and closes all active client connections.
     */
    void stop();

    /**
     * Checks if the TCP server is currently running.
     *
     * @return true if the server is listening and operational, false otherwise.
     */
    boolean isRunning();

    /**
     * Gets the actual port on which the TCP server is listening.
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
