package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a software development project managed by TRYST.
 * Distinct from physical workspaces. Associates with an authoritative owner/tenant.
 */
public class Project {

    public static final String DEFAULT_OWNER_ID = "default-user";

    private final String id;
    private final String ownerId;
    private final String name;
    private final String description;
    private final Path location;
    private ProjectStatus status;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    public Project(String name, Path location) {
        this(name, "", location);
    }

    public Project(String name, String description, Path location) {
        this(UUID.randomUUID().toString(), DEFAULT_OWNER_ID, name, description, location, ProjectStatus.ACTIVE, false, Instant.now(), Instant.now());
    }

    public Project(String id, String name, Path location, boolean active) {
        this(id, DEFAULT_OWNER_ID, name, "", location, ProjectStatus.ACTIVE, active, Instant.now(), Instant.now());
    }

    public Project(String id, String ownerId, String name, Path location, boolean active) {
        this(id, ownerId, name, "", location, ProjectStatus.ACTIVE, active, Instant.now(), Instant.now());
    }

    public Project(String id, String ownerId, String name, String description, Path location, ProjectStatus status, boolean active, Instant createdAt, Instant updatedAt) {
        if (name == null || name.trim().isEmpty()) {
            throw new TrystDomainException("Project name must not be null or blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerId = ownerId != null && !ownerId.trim().isEmpty() ? ownerId.trim() : DEFAULT_OWNER_ID;
        this.name = name.trim();
        this.description = description != null ? description.trim() : "";
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Path getLocation() {
        return location;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (active && this.status != ProjectStatus.ACTIVE) {
            throw new TrystDomainException("Cannot set non-active project (" + status + ") as active project");
        }
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        if (this.status == ProjectStatus.CLOSED) {
            throw new TrystDomainException("Cannot activate a closed project");
        }
        this.status = ProjectStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        if (this.status == ProjectStatus.CLOSED) {
            throw new TrystDomainException("Cannot archive a closed project");
        }
        this.status = ProjectStatus.ARCHIVED;
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = ProjectStatus.CLOSED;
        this.active = false;
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
        if (!(o instanceof Project project)) return false;
        return Objects.equals(id, project.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Project{" +
                "id='" + id + '\'' +
                ", ownerId='" + ownerId + '\'' +
                ", name='" + name + '\'' +
                ", location=" + location +
                ", status=" + status +
                ", active=" + active +
                '}';
    }
}
