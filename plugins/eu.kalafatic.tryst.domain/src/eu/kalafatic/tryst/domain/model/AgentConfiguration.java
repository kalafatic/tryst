package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an AI agent configuration.
 */
public class AgentConfiguration {

    private final String id;
    private final String name;
    private final String modelName;
    private final String systemPrompt;
    private final String configurationJson;
    private final Instant createdAt;
    private Instant updatedAt;

    public AgentConfiguration(String name, String modelName, String systemPrompt, String configurationJson) {
        this(UUID.randomUUID().toString(), name, modelName, systemPrompt, configurationJson, Instant.now(), Instant.now());
    }

    public AgentConfiguration(String id, String name, String modelName, String systemPrompt, String configurationJson, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.configurationJson = configurationJson != null ? configurationJson : "{}";
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getModelName() {
        return modelName;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getConfigurationJson() {
        return configurationJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentConfiguration that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AgentConfiguration{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", modelName='" + modelName + '\'' +
                '}';
    }
}
