package eu.kalafatic.tryst.domain.ai;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Persisted AI provider (inference engine) descriptor for the unified multi-provider
 * dimension: stable identity, type, endpoint, enabled state, default model and
 * configuration. Configuration maps never expose secrets — credentials are stored
 * encrypted at rest and returned masked.
 */
public record AiProviderDescriptor(
        String id,
        String name,
        String type,
        String endpoint,
        boolean enabled,
        String defaultModel,
        Map<String, String> configuration,
        Instant createdAt,
        Instant updatedAt
) {
    public AiProviderDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        endpoint = endpoint == null ? "" : endpoint;
        defaultModel = defaultModel == null ? "" : defaultModel;
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
