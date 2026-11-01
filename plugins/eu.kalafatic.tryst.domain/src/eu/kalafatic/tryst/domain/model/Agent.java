package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Domain entity representing an AI execution identity with defined responsibilities.
 * Distinguishable from an LLM model / inference provider.
 */
public class Agent {

    private final String id;
    private final String name;
    private final String description;
    private final AgentType agentType;
    private AgentAvailability availability;
    private final Set<String> capabilities = new HashSet<>();
    private final Instant createdAt;
    private Instant updatedAt;

    public Agent(String name, String description, AgentType agentType) {
        this(UUID.randomUUID().toString(), name, description, agentType, AgentAvailability.AVAILABLE, null, Instant.now(), Instant.now());
    }

    public Agent(String id, String name, String description, AgentType agentType, AgentAvailability availability, Set<String> capabilities, Instant createdAt, Instant updatedAt) {
        if (name == null || name.trim().isEmpty()) {
            throw new TrystDomainException("Agent name must not be null or blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = name.trim();
        this.description = description != null ? description.trim() : "";
        this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        if (capabilities != null) {
            this.capabilities.addAll(capabilities);
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public AgentAvailability getAvailability() {
        return availability;
    }

    public Set<String> getCapabilities() {
        return Collections.unmodifiableSet(capabilities);
    }

    public boolean hasCapability(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    public void addCapability(String capability) {
        if (capability != null && !capability.trim().isEmpty()) {
            capabilities.add(capability.trim());
            this.updatedAt = Instant.now();
        }
    }

    public void markAvailable() {
        this.availability = AgentAvailability.AVAILABLE;
        this.updatedAt = Instant.now();
    }

    public void markBusy() {
        if (this.availability == AgentAvailability.OFFLINE) {
            throw new TrystDomainException("Cannot mark offline agent as busy");
        }
        this.availability = AgentAvailability.BUSY;
        this.updatedAt = Instant.now();
    }

    public void markOffline() {
        this.availability = AgentAvailability.OFFLINE;
        this.updatedAt = Instant.now();
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
        if (!(o instanceof Agent agent)) return false;
        return Objects.equals(id, agent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Agent{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", agentType=" + agentType +
                ", availability=" + availability +
                '}';
    }
}
