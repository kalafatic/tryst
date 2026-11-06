package eu.kalafatic.tryst.core.port;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Authoritative runtime port and endpoint registry.
 * Published across OSGi registry and consulted by all network listeners,
 * internal clients, UI views, and observability components.
 */
public class PortRegistry {

    private volatile PortAllocationGroup currentGroup;
    private final Map<String, Integer> runtimeBoundPorts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BiConsumer<String, Integer>> portChangeListeners = new CopyOnWriteArrayList<>();

    public PortRegistry() {
        this(PortAllocationGroup.createDefault("127.0.0.1"));
    }

    public PortRegistry(PortAllocationGroup initialGroup) {
        this.currentGroup = Objects.requireNonNull(initialGroup, "initialGroup must not be null");
    }

    public synchronized void setAllocationGroup(PortAllocationGroup group) {
        this.currentGroup = Objects.requireNonNull(group, "group must not be null");
        TrystLog.info(PortRegistry.class, "Port allocation group updated: groupOffset=" + group.groupOffset()
                + ", HTTP=" + group.getPort(ServicePortIdentifier.HTTP_SERVICE)
                + ", TCP=" + group.getPort(ServicePortIdentifier.TCP_SERVICE));
    }

    public PortAllocationGroup getAllocationGroup() {
        return currentGroup;
    }

    /**
     * Returns the assigned port for the service according to the active coherent group.
     */
    public int getAssignedPort(String serviceId) {
        return currentGroup.getPort(serviceId);
    }

    /**
     * Registers actual runtime bound socket port (published strictly after successful bind).
     */
    public void registerBoundPort(String serviceId, int actualPort) {
        runtimeBoundPorts.put(serviceId, actualPort);
        TrystLog.info(PortRegistry.class, "Runtime listener bound: service=" + serviceId + " on port " + actualPort);
        notifyListeners(serviceId, actualPort);
    }

    public int getEffectivePort(String serviceId) {
        Integer bound = runtimeBoundPorts.get(serviceId);
        if (bound != null && bound > 0) {
            return bound;
        }
        return getAssignedPort(serviceId);
    }

    public Map<String, Integer> getRuntimeBoundPorts() {
        return Collections.unmodifiableMap(runtimeBoundPorts);
    }

    public void addPortChangeListener(BiConsumer<String, Integer> listener) {
        if (listener != null) {
            portChangeListeners.add(listener);
        }
    }

    public void removePortChangeListener(BiConsumer<String, Integer> listener) {
        if (listener != null) {
            portChangeListeners.remove(listener);
        }
    }

    private void notifyListeners(String serviceId, int port) {
        for (BiConsumer<String, Integer> l : portChangeListeners) {
            try {
                l.accept(serviceId, port);
            } catch (Throwable t) {
                TrystLog.error(PortRegistry.class, "Port change listener threw exception: " + t.getMessage(), t);
            }
        }
    }
}
