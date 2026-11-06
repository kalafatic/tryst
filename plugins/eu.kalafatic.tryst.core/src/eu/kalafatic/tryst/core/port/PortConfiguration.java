package eu.kalafatic.tryst.core.port;

import java.time.Instant;
import java.util.Objects;

/**
 * Authoritative descriptor for a single managed service port configuration.
 */
public record PortConfiguration(
        String serviceId,
        int defaultPort,
        int assignedPort,
        int offset,
        PortProtocol protocol,
        String bindingAddress,
        boolean enabled,
        int configurationVersion,
        Instant allocationTimestamp
) {
    public PortConfiguration {
        Objects.requireNonNull(serviceId, "serviceId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(bindingAddress, "bindingAddress must not be null");
        Objects.requireNonNull(allocationTimestamp, "allocationTimestamp must not be null");
        if (defaultPort < 0 || defaultPort > 65535) {
            throw new IllegalArgumentException("defaultPort must be between 0 and 65535: " + defaultPort);
        }
        if (assignedPort < 0 || assignedPort > 65535) {
            throw new IllegalArgumentException("assignedPort must be between 0 and 65535: " + assignedPort);
        }
    }

    public PortConfiguration withAssignedPort(int newPort, int newOffset) {
        return new PortConfiguration(
                serviceId,
                defaultPort,
                newPort,
                newOffset,
                protocol,
                bindingAddress,
                enabled,
                configurationVersion + 1,
                Instant.now()
        );
    }
}
