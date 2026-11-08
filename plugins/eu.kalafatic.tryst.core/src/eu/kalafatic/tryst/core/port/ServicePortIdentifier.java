package eu.kalafatic.tryst.core.port;

/**
 * Authoritative identifiers for canonical TRYST network services.
 */
public final class ServicePortIdentifier {

    public static final String HTTP_SERVICE = "http";
    public static final String API_SERVICE = "api";
    public static final String TCP_SERVICE = "tcp";
    public static final String MANAGEMENT_SERVICE = "management";
    public static final String WEBSOCKET_SERVICE = "websocket";
    public static final String AUXILIARY_SERVICE = "auxiliary";
    public static final String DEBUG_SERVICE = "debug";

    public static final int BASE_OFFSET = 30000;
    public static final int SERVICE_PORT_SPACING = 100;

    public static final int DEFAULT_BASE_HTTP_PORT = 8080;
    public static final int DEFAULT_BASE_API_PORT = 8180;
    public static final int DEFAULT_BASE_TCP_PORT = 8280;
    public static final int DEFAULT_BASE_MANAGEMENT_PORT = 8380;
    public static final int DEFAULT_BASE_WEBSOCKET_PORT = 8480;
    public static final int DEFAULT_BASE_AUXILIARY_PORT = 8580;
    public static final int DEFAULT_BASE_DEBUG_PORT = 8680;

    public static final int DEFAULT_HTTP_PORT = BASE_OFFSET + DEFAULT_BASE_HTTP_PORT; // 38080
    public static final int DEFAULT_API_PORT = BASE_OFFSET + DEFAULT_BASE_API_PORT;   // 38180
    public static final int DEFAULT_TCP_PORT = BASE_OFFSET + DEFAULT_BASE_TCP_PORT;   // 38280
    public static final int DEFAULT_MANAGEMENT_PORT = BASE_OFFSET + DEFAULT_BASE_MANAGEMENT_PORT; // 38380
    public static final int DEFAULT_WEBSOCKET_PORT = BASE_OFFSET + DEFAULT_BASE_WEBSOCKET_PORT;   // 38480
    public static final int DEFAULT_AUXILIARY_PORT = BASE_OFFSET + DEFAULT_BASE_AUXILIARY_PORT; // 38580
    public static final int DEFAULT_DEBUG_PORT = BASE_OFFSET + DEFAULT_BASE_DEBUG_PORT;   // 38680

    public static int getDefaultBasePort(String serviceId) {
        return switch (serviceId) {
            case HTTP_SERVICE -> DEFAULT_BASE_HTTP_PORT;
            case API_SERVICE -> DEFAULT_BASE_API_PORT;
            case TCP_SERVICE -> DEFAULT_BASE_TCP_PORT;
            case MANAGEMENT_SERVICE -> DEFAULT_BASE_MANAGEMENT_PORT;
            case WEBSOCKET_SERVICE -> DEFAULT_BASE_WEBSOCKET_PORT;
            case AUXILIARY_SERVICE -> DEFAULT_BASE_AUXILIARY_PORT;
            case DEBUG_SERVICE -> DEFAULT_BASE_DEBUG_PORT;
            default -> throw new IllegalArgumentException("Unknown service port identifier: " + serviceId);
        };
    }

    public static int getDefaultTrystPort(String serviceId) {
        return BASE_OFFSET + getDefaultBasePort(serviceId);
    }

    public static PortProtocol getDefaultProtocol(String serviceId) {
        return switch (serviceId) {
            case HTTP_SERVICE -> PortProtocol.HTTP;
            case API_SERVICE -> PortProtocol.API;
            case TCP_SERVICE -> PortProtocol.TCP;
            case MANAGEMENT_SERVICE -> PortProtocol.MANAGEMENT;
            case WEBSOCKET_SERVICE -> PortProtocol.WEBSOCKET;
            case AUXILIARY_SERVICE -> PortProtocol.AUXILIARY;
            case DEBUG_SERVICE -> PortProtocol.DEBUG;
            default -> throw new IllegalArgumentException("Unknown service port identifier: " + serviceId);
        };
    }

    private ServicePortIdentifier() {
    }
}
