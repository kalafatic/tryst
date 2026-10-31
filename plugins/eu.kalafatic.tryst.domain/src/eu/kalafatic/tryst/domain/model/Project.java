package eu.kalafatic.tryst.domain.model;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a software project loaded into TRYST.
 */
public class Project {

    private final String id;
    private final String name;
    private final Path location;
    private boolean active;

    public Project(String name, Path location) {
        this(UUID.randomUUID().toString(), name, location, false);
    }

    public Project(String id, String name, Path location, boolean active) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Path getLocation() {
        return location;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
                ", name='" + name + '\'' +
                ", location=" + location +
                ", active=" + active +
                '}';
    }
}
