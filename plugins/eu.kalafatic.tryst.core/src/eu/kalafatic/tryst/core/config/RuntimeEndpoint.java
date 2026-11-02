package eu.kalafatic.tryst.core.config;

import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative runtime endpoint information published dynamically upon server listener activation.
 */
public record RuntimeEndpoint(
        String applicationId,
        String bindAddress,
        int httpPort,
        int httpsPort,
        String internalUrl,
        String externalUrl,
        String canonicalUrl,
        String contextPath,
        String listenerState,
        Instant startupTime
) {
    public RuntimeEndpoint {
        Objects.requireNonNull(applicationId, "applicationId must not be null");
        Objects.requireNonNull(bindAddress, "bindAddress must not be null");
        Objects.requireNonNull(internalUrl, "internalUrl must not be null");
        Objects.requireNonNull(externalUrl, "externalUrl must not be null");
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        Objects.requireNonNull(contextPath, "contextPath must not be null");
        Objects.requireNonNull(listenerState, "listenerState must not be null");
        Objects.requireNonNull(startupTime, "startupTime must not be null");
    }

    public RuntimeEndpoint(
            String applicationId,
            String bindAddress,
            int httpPort,
            int httpsPort,
            String internalUrl,
            String externalUrl,
            String listenerState,
            Instant startupTime
    ) {
        this(
                applicationId,
                bindAddress,
                httpPort,
                httpsPort,
                internalUrl,
                externalUrl,
                "http://localhost/tryst",
                "/tryst",
                listenerState,
                startupTime
        );
    }

    public static RuntimeEndpoint uninitialized(String applicationId, String host, int port) {
        String internal = "http://" + host + ":" + port + "/tryst";
        return new RuntimeEndpoint(
                applicationId, host, port, -1, internal, internal, "http://localhost/tryst", "/tryst", "STOPPED", Instant.now()
        );
    }
}
