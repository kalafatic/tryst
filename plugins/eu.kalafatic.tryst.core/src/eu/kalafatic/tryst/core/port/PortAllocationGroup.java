package eu.kalafatic.tryst.core.port;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Coherent port group managing TRYST service allocations.
 * <p>
 * Standard Convention:
 * - Base offset: +30000
 * - Original port series: 8080, 8180, 8280, 8380, 8480, 8580, 8680...
 * - Default TRYST ports:
 *   HTTP: 38080
 *   API: 38180
 *   TCP: 38280
 *   Management: 38380
 *   WebSocket: 38480
 * - Increment between services: 100 ports.
 * - Alternative group allocations preserve the 100-port spacing across all services.
 * - TRYST ports are strictly segregated from EVO ports.
 */
public record PortAllocationGroup(
        int baseOffset,
        int groupOffset,
        String bindingAddress,
        int configurationVersion,
        Instant allocationTimestamp,
        Map<String, PortConfiguration> configurations
) {
    public static final int TRYST_BASE_OFFSET = ServicePortIdentifier.BASE_OFFSET;
    public static final int SERVICE_PORT_SPACING = ServicePortIdentifier.SERVICE_PORT_SPACING;

    public static final int DEFAULT_BASE_HTTP_PORT = ServicePortIdentifier.DEFAULT_BASE_HTTP_PORT;
    public static final int DEFAULT_BASE_API_PORT = ServicePortIdentifier.DEFAULT_BASE_API_PORT;
    public static final int DEFAULT_BASE_TCP_PORT = ServicePortIdentifier.DEFAULT_BASE_TCP_PORT;
    public static final int DEFAULT_BASE_MANAGEMENT_PORT = ServicePortIdentifier.DEFAULT_BASE_MANAGEMENT_PORT;
    public static final int DEFAULT_BASE_WEBSOCKET_PORT = ServicePortIdentifier.DEFAULT_BASE_WEBSOCKET_PORT;
    public static final int DEFAULT_BASE_AUXILIARY_PORT = ServicePortIdentifier.DEFAULT_BASE_AUXILIARY_PORT;
    public static final int DEFAULT_BASE_DEBUG_PORT = ServicePortIdentifier.DEFAULT_BASE_DEBUG_PORT;

    public static final int DEFAULT_TRYST_HTTP_PORT = ServicePortIdentifier.DEFAULT_HTTP_PORT; // 38080
    public static final int DEFAULT_TRYST_API_PORT = ServicePortIdentifier.DEFAULT_API_PORT;   // 38180
    public static final int DEFAULT_TRYST_TCP_PORT = ServicePortIdentifier.DEFAULT_TCP_PORT;   // 38280
    public static final int DEFAULT_TRYST_MGMT_PORT = ServicePortIdentifier.DEFAULT_MANAGEMENT_PORT; // 38380
    public static final int DEFAULT_TRYST_WS_PORT = ServicePortIdentifier.DEFAULT_WEBSOCKET_PORT;   // 38480
    public static final int DEFAULT_TRYST_AUX_PORT = ServicePortIdentifier.DEFAULT_AUXILIARY_PORT; // 38580
    public static final int DEFAULT_TRYST_DEBUG_PORT = ServicePortIdentifier.DEFAULT_DEBUG_PORT;   // 38680

    public PortAllocationGroup {
        Objects.requireNonNull(bindingAddress, "bindingAddress must not be null");
        Objects.requireNonNull(allocationTimestamp, "allocationTimestamp must not be null");
        if (configurations != null) {
            java.util.HashSet<Integer> seenPorts = new java.util.HashSet<>();
            for (Map.Entry<String, PortConfiguration> entry : configurations.entrySet()) {
                int assignedPort = entry.getValue().assignedPort();
                if (assignedPort == 8080) {
                    throw new IllegalArgumentException("Port 8080 is strictly reserved and forbidden for TRYST service: " + entry.getKey());
                }
                if (assignedPort >= 8080 && assignedPort <= 8500) {
                    throw new IllegalArgumentException("Ports in EVO range (8080-8500) are forbidden for TRYST service: " + entry.getKey() + " (port: " + assignedPort + ")");
                }
                if (!seenPorts.add(assignedPort)) {
                    throw new IllegalArgumentException("Duplicate port allocation detected in PortAllocationGroup: port " + assignedPort + " is assigned to multiple services.");
                }
            }
        }
        configurations = Collections.unmodifiableMap(new LinkedHashMap<>(configurations != null ? configurations : Map.of()));
    }

    /**
     * Creates the canonical default PortAllocationGroup (+30000 base offset, 0 group offset).
     */
    public static PortAllocationGroup createDefault(String bindingAddress) {
        String host = (bindingAddress == null || bindingAddress.isBlank()) ? "127.0.0.1" : bindingAddress;
        Instant now = Instant.now();
        Map<String, PortConfiguration> map = new LinkedHashMap<>();

        map.put(ServicePortIdentifier.HTTP_SERVICE, new PortConfiguration(
                ServicePortIdentifier.HTTP_SERVICE,
                DEFAULT_TRYST_HTTP_PORT,
                DEFAULT_TRYST_HTTP_PORT,
                0,
                PortProtocol.HTTP,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.API_SERVICE, new PortConfiguration(
                ServicePortIdentifier.API_SERVICE,
                DEFAULT_TRYST_API_PORT,
                DEFAULT_TRYST_API_PORT,
                0,
                PortProtocol.API,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.TCP_SERVICE, new PortConfiguration(
                ServicePortIdentifier.TCP_SERVICE,
                DEFAULT_TRYST_TCP_PORT,
                DEFAULT_TRYST_TCP_PORT,
                0,
                PortProtocol.TCP,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.MANAGEMENT_SERVICE, new PortConfiguration(
                ServicePortIdentifier.MANAGEMENT_SERVICE,
                DEFAULT_TRYST_MGMT_PORT,
                DEFAULT_TRYST_MGMT_PORT,
                0,
                PortProtocol.MANAGEMENT,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.WEBSOCKET_SERVICE, new PortConfiguration(
                ServicePortIdentifier.WEBSOCKET_SERVICE,
                DEFAULT_TRYST_WS_PORT,
                DEFAULT_TRYST_WS_PORT,
                0,
                PortProtocol.WEBSOCKET,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.AUXILIARY_SERVICE, new PortConfiguration(
                ServicePortIdentifier.AUXILIARY_SERVICE,
                DEFAULT_TRYST_AUX_PORT,
                DEFAULT_TRYST_AUX_PORT,
                0,
                PortProtocol.AUXILIARY,
                host,
                true,
                1,
                now
        ));
        map.put(ServicePortIdentifier.DEBUG_SERVICE, new PortConfiguration(
                ServicePortIdentifier.DEBUG_SERVICE,
                DEFAULT_TRYST_DEBUG_PORT,
                DEFAULT_TRYST_DEBUG_PORT,
                0,
                PortProtocol.DEBUG,
                host,
                true,
                1,
                now
        ));

        return new PortAllocationGroup(TRYST_BASE_OFFSET, 0, host, 1, now, map);
    }

    /**
     * Shifts the entire group coherently by applying the specified group offset (e.g. +100).
     * Preserves strict 100-port spacing across all services.
     */
    public PortAllocationGroup withGroupOffset(int newGroupOffset) {
        Instant now = Instant.now();
        Map<String, PortConfiguration> shifted = new LinkedHashMap<>();

        int httpAssigned = DEFAULT_TRYST_HTTP_PORT + newGroupOffset;
        int apiAssigned = DEFAULT_TRYST_API_PORT + newGroupOffset;
        int tcpAssigned = DEFAULT_TRYST_TCP_PORT + newGroupOffset;
        int mgmtAssigned = DEFAULT_TRYST_MGMT_PORT + newGroupOffset;
        int wsAssigned = DEFAULT_TRYST_WS_PORT + newGroupOffset;
        int auxAssigned = DEFAULT_TRYST_AUX_PORT + newGroupOffset;
        int debugAssigned = DEFAULT_TRYST_DEBUG_PORT + newGroupOffset;

        shifted.put(ServicePortIdentifier.HTTP_SERVICE, new PortConfiguration(
                ServicePortIdentifier.HTTP_SERVICE, DEFAULT_TRYST_HTTP_PORT, httpAssigned, newGroupOffset, PortProtocol.HTTP, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.API_SERVICE, new PortConfiguration(
                ServicePortIdentifier.API_SERVICE, DEFAULT_TRYST_API_PORT, apiAssigned, newGroupOffset, PortProtocol.API, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.TCP_SERVICE, new PortConfiguration(
                ServicePortIdentifier.TCP_SERVICE, DEFAULT_TRYST_TCP_PORT, tcpAssigned, newGroupOffset, PortProtocol.TCP, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.MANAGEMENT_SERVICE, new PortConfiguration(
                ServicePortIdentifier.MANAGEMENT_SERVICE, DEFAULT_TRYST_MGMT_PORT, mgmtAssigned, newGroupOffset, PortProtocol.MANAGEMENT, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.WEBSOCKET_SERVICE, new PortConfiguration(
                ServicePortIdentifier.WEBSOCKET_SERVICE, DEFAULT_TRYST_WS_PORT, wsAssigned, newGroupOffset, PortProtocol.WEBSOCKET, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.AUXILIARY_SERVICE, new PortConfiguration(
                ServicePortIdentifier.AUXILIARY_SERVICE, DEFAULT_TRYST_AUX_PORT, auxAssigned, newGroupOffset, PortProtocol.AUXILIARY, bindingAddress, true, configurationVersion + 1, now
        ));
        shifted.put(ServicePortIdentifier.DEBUG_SERVICE, new PortConfiguration(
                ServicePortIdentifier.DEBUG_SERVICE, DEFAULT_TRYST_DEBUG_PORT, debugAssigned, newGroupOffset, PortProtocol.DEBUG, bindingAddress, true, configurationVersion + 1, now
        ));

        return new PortAllocationGroup(baseOffset, newGroupOffset, bindingAddress, configurationVersion + 1, now, shifted);
    }

    public int getPort(String serviceId) {
        PortConfiguration cfg = configurations.get(serviceId);
        if (cfg == null) {
            throw new IllegalArgumentException("Unknown service port identifier: " + serviceId);
        }
        return cfg.assignedPort();
    }

    public Optional<PortConfiguration> findConfiguration(String serviceId) {
        return Optional.ofNullable(configurations.get(serviceId));
    }
}
