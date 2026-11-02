package eu.kalafatic.tryst.core.lifecycle;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative component-level health and readiness registry.
 * Distinguishes mandatory vs optional components and calculates overall server readiness.
 */
public class ServerReadinessService {

    public enum ComponentState {
        UNKNOWN,
        INITIALIZING,
        HEALTHY,
        DEGRADED,
        FAILED,
        STOPPED
    }

    public record ComponentHealth(
            String componentId,
            boolean mandatory,
            ComponentState state,
            Instant initializedAt,
            Instant lastCheckedAt,
            String lastError,
            Map<String, String> details
    ) {
        public ComponentHealth {
            Objects.requireNonNull(componentId, "componentId must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (details == null) {
                details = Map.of();
            }
        }
    }

    public record ReadinessReport(
            ApplicationLifecycleStage lifecycleStage,
            boolean ready,
            boolean degraded,
            Instant timestamp,
            Map<String, ComponentHealth> components
    ) {}

    private final Map<String, ComponentHealth> components = new ConcurrentHashMap<>();

    public void registerComponent(String componentId, boolean mandatory) {
        Objects.requireNonNull(componentId, "componentId must not be null");
        Instant now = Instant.now();
        components.put(componentId, new ComponentHealth(
                componentId, mandatory, ComponentState.INITIALIZING, now, now, null, Map.of()
        ));
    }

    public void updateHealth(String componentId, ComponentState state, String lastError, Map<String, String> details) {
        Objects.requireNonNull(componentId, "componentId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        components.compute(componentId, (id, existing) -> {
            boolean mandatory = existing != null && existing.mandatory();
            Instant initAt = existing != null ? existing.initializedAt() : Instant.now();
            return new ComponentHealth(
                    componentId,
                    mandatory,
                    state,
                    initAt,
                    Instant.now(),
                    lastError,
                    details != null ? Map.copyOf(details) : Map.of()
            );
        });
    }

    public void markHealthy(String componentId, Map<String, String> details) {
        updateHealth(componentId, ComponentState.HEALTHY, null, details);
    }

    public void markDegraded(String componentId, String reason, Map<String, String> details) {
        updateHealth(componentId, ComponentState.DEGRADED, reason, details);
    }

    public void markFailed(String componentId, String error, Map<String, String> details) {
        updateHealth(componentId, ComponentState.FAILED, error, details);
    }

    public Map<String, ComponentHealth> getComponents() {
        return Collections.unmodifiableMap(components);
    }

    public ComponentHealth getComponent(String componentId) {
        return components.get(componentId);
    }

    public ReadinessReport evaluateReadiness(ApplicationLifecycleStage currentStage) {
        boolean allMandatoryHealthy = true;
        boolean anyDegraded = false;

        for (ComponentHealth ch : components.values()) {
            if (ch.mandatory()) {
                if (ch.state() != ComponentState.HEALTHY) {
                    allMandatoryHealthy = false;
                }
            } else {
                if (ch.state() == ComponentState.DEGRADED || ch.state() == ComponentState.FAILED) {
                    anyDegraded = true;
                }
            }
        }

        boolean isReady = allMandatoryHealthy &&
                (currentStage == ApplicationLifecycleStage.APPLICATION_READY ||
                 currentStage == ApplicationLifecycleStage.WORKBENCH_RUNNING);

        return new ReadinessReport(
                currentStage,
                isReady,
                anyDegraded,
                Instant.now(),
                Collections.unmodifiableMap(components)
        );
    }
}
