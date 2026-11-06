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

    private ServicePortIdentifier() {
    }
}
