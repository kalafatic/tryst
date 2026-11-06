package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing configuration for connecting to an external EVOLUTION (EVO) engine via TEIP 1.0.
 */
public class EvoConfiguration {

    private final String id;
    private String endpoint;
    private String authToken;
    private boolean enabled;
    private int timeoutSeconds;
    private int maxRetries;
    private Instant updatedAt;

    public EvoConfiguration(
            String id,
            String endpoint,
            String authToken,
            boolean enabled,
            int timeoutSeconds,
            int maxRetries,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.endpoint = endpoint != null ? endpoint.trim() : "http://localhost:39080";
        this.authToken = authToken != null ? authToken : "";
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
        this.maxRetries = Math.max(0, maxRetries);
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static EvoConfiguration createDefault() {
        return new EvoConfiguration(
                "default",
                "http://localhost:39080",
                "",
                false,
                60,
                3,
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

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken != null ? authToken : "";
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
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
        this.updatedAt = Instant.now();
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
