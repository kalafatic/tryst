package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing configuration for connecting to the DStudio Harness Platform
 * via TEIP 1.1 (Phase 4). Non-secret provider configuration only; the TEIP auth token is
 * intentionally excluded from persistence (runtime-only: system property or environment).
 */
public class DStudioConfiguration {

    private final String id;
    private String endpoint;
    private String harness;
    private String model;
    private boolean enabled;
    private int timeoutSeconds;
    private Instant updatedAt;

    public DStudioConfiguration(
            String id,
            String endpoint,
            String harness,
            String model,
            boolean enabled,
            int timeoutSeconds,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.endpoint = endpoint != null && !endpoint.isBlank() ? endpoint.trim()
                : "http://localhost:48080";
        this.harness = harness != null ? harness : "";
        this.model = model != null ? model : "";
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 1800;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static DStudioConfiguration createDefault() {
        return new DStudioConfiguration(
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

    public String getHarness() {
        return harness;
    }

    public void setHarness(String harness) {
        this.harness = harness != null ? harness : "";
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
