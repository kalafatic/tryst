package eu.kalafatic.tryst.core.config;

/**
 * Defines the network access and interface binding modes for the TRYST platform.
 */
public enum NetworkMode {
    /**
     * Local loopback only (127.0.0.1). Secure local workstation deployment.
     */
    LOOPBACK,

    /**
     * Bound to a specific detected or explicitly configured LAN interface IP address.
     * Accessible to other devices on the local area network.
     */
    LAN,

    /**
     * Explicitly bound to all available network interfaces (0.0.0.0).
     */
    ALL_INTERFACES;

    public static NetworkMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOOPBACK;
        }
        String clean = value.trim().toUpperCase();
        return switch (clean) {
            case "LAN", "LOCAL_NETWORK" -> LAN;
            case "ALL", "ALL_INTERFACES", "0.0.0.0", "ANY" -> ALL_INTERFACES;
            default -> LOOPBACK;
        };
    }
}
