package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Authoritative registry holding the active runtime endpoint and notifying listeners
 * on dynamic address/port updates.
 */
public class RuntimeEndpointRegistry {

    private volatile RuntimeEndpoint currentEndpoint;
    private final CopyOnWriteArrayList<Consumer<RuntimeEndpoint>> listeners = new CopyOnWriteArrayList<>();

    public RuntimeEndpointRegistry(RuntimeEndpoint initialEndpoint) {
        this.currentEndpoint = Objects.requireNonNull(initialEndpoint, "initialEndpoint must not be null");
    }

    public RuntimeEndpoint getCurrentEndpoint() {
        return currentEndpoint;
    }

    public synchronized void updateEndpoint(RuntimeEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.currentEndpoint = endpoint;
        TrystLog.info(RuntimeEndpointRegistry.class, "Runtime endpoint updated: " + endpoint.internalUrl() + " (external: " + endpoint.externalUrl() + ")");
        for (Consumer<RuntimeEndpoint> listener : listeners) {
            try {
                listener.accept(endpoint);
            } catch (Throwable t) {
                TrystLog.error(RuntimeEndpointRegistry.class, "Endpoint listener threw exception: " + t.getMessage(), t);
            }
        }
    }

    public void addEndpointListener(Consumer<RuntimeEndpoint> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeEndpointListener(Consumer<RuntimeEndpoint> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }
}
