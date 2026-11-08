package eu.kalafatic.tryst.domain.workflow;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a visual workflow definition.
 */
public class WorkflowDefinition {

    private final String id;
    private final String name;
    private final String description;
    private final String projectId;
    private final String ownerId;
    private final int version;
    private final boolean isTemplate;
    private final List<WorkflowNode> nodes;
    private final List<WorkflowEdge> edges;
    private final String graphJson;
    private final Instant createdAt;
    private final Instant updatedAt;

    public WorkflowDefinition(String name, String description, String projectId, String ownerId,
                              List<WorkflowNode> nodes, List<WorkflowEdge> edges, String graphJson) {
        this(UUID.randomUUID().toString(), name, description, projectId, ownerId, 1, false,
                nodes, edges, graphJson, Instant.now(), Instant.now());
    }

    public WorkflowDefinition(String id, String name, String description, String projectId,
                              String ownerId, int version, boolean isTemplate,
                              List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                              String graphJson, Instant createdAt, Instant updatedAt) {
        if (name == null || name.trim().isEmpty()) {
            throw new TrystDomainException("Workflow name must not be blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = name.trim();
        this.description = description != null ? description.trim() : "";
        this.projectId = projectId != null && !projectId.trim().isEmpty() ? projectId.trim() : null;
        this.ownerId = ownerId != null && !ownerId.trim().isEmpty() ? ownerId.trim() : null;
        this.version = Math.max(1, version);
        this.isTemplate = isTemplate;
        this.nodes = nodes != null ? Collections.unmodifiableList(new ArrayList<>(nodes)) : Collections.emptyList();
        this.edges = edges != null ? Collections.unmodifiableList(new ArrayList<>(edges)) : Collections.emptyList();
        this.graphJson = graphJson != null ? graphJson : "{}";
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

    public String getProjectId() {
        return projectId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public int getVersion() {
        return version;
    }

    public boolean isTemplate() {
        return isTemplate;
    }

    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    public List<WorkflowEdge> getEdges() {
        return edges;
    }

    public String getGraphJson() {
        return graphJson;
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
        if (!(o instanceof WorkflowDefinition that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
