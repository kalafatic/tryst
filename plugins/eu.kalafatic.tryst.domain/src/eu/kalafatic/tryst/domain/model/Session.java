package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an interactive or autonomous agent session bound to a project.
 */
public class Session {

    private final String id;
    private final String projectId;
    private final String name;
    private String status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Session(String projectId, String name) {
        this(UUID.randomUUID().toString(), projectId, name, "ACTIVE", Instant.now(), Instant.now());
    }

    public Session(String id, String projectId, String name, String status, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Session session)) return false;
        return Objects.equals(id, session.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Session{" +
                "id='" + id + '\'' +
                ", projectId='" + projectId + '\'' +
                ", name='" + name + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
