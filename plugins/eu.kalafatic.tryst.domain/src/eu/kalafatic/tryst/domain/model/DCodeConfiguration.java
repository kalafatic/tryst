package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing configuration for connecting to the native DCode coding
 * harness (Phase 6). Non-secret provider configuration only; the DCode bearer token is
 * intentionally excluded from persistence (runtime-only: system property or environment).
 */
public class DCodeConfiguration {

    private final String id;
    private String endpoint;
    private String providerId;
    private String model;
    private boolean enabled;
    private int timeoutSeconds;
    private Instant updatedAt;

    public DCodeConfiguration(
            String id,
            String endpoint,
            String providerId,
            String model,
            boolean enabled,
            int timeoutSeconds,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.endpoint = endpoint != null && !endpoint.isBlank() ? endpoint.trim()
                : "http://localhost:48080";
        this.providerId = providerId != null ? providerId : "";
        this.model = model != null ? model : "";
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 1800;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static DCodeConfiguration createDefault() {
        return new DCodeConfiguration(
                "default",
                "http://localhost:48080",
                "",
                "",
                true,
                1800,
                Instant.now()
        );
    }

    public String getId() {
        return id;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint != null ? endpoint.trim() : "";
        this.updatedAt = Instant.now();
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId != null ? providerId : "";
        this.updatedAt = Instant.now();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model != null ? model : "";
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 1800;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
