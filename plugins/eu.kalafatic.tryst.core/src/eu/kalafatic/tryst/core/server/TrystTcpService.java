package eu.kalafatic.tryst.core.server;

/**
 * Public service contract for the TRYST TCP communication server.
 */
public interface TrystTcpService {

    /**
     * Starts the TCP server socket listener on the configured host and port.
     */
    void start();

    /**
     * Stops the TCP server listener and disposes connection resources.
     */
    void stop();

    /**
     * Checks if the TCP server is currently running.
     *
     * @return true if operational, false otherwise.
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
