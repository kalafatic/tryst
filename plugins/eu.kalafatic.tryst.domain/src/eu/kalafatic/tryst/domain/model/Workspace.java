package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain entity representing the physical working environment associated with a project.
 * Provides a clear boundary for file operations, Git operations, builds, and agent execution.
 */
public class Workspace {

    private final String id;
    private final Path rootPath;
    private WorkspaceStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<Project> projects = new ArrayList<>();
    private final List<AgentTask> tasks = new ArrayList<>();

    public Workspace(Path rootPath) {
        this(UUID.randomUUID().toString(), rootPath);
    }

    public Workspace(String id, Path rootPath) {
        this(id, rootPath, WorkspaceStatus.ACTIVE, Instant.now(), Instant.now());
    }

    public Workspace(String id, Path rootPath, WorkspaceStatus status, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rootPath = Objects.requireNonNull(rootPath, "rootPath must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() {
        return id;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public WorkspaceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<Project> getProjects() {
        return Collections.unmodifiableList(projects);
    }

    public List<AgentTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public void activate() {
        if (this.status == WorkspaceStatus.CLOSED) {
            throw new TrystDomainException("Cannot activate a closed workspace");
        }
        this.status = WorkspaceStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        if (this.status == WorkspaceStatus.CLOSED) {
            throw new TrystDomainException("Cannot deactivate a closed workspace");
        }
        this.status = WorkspaceStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public void lock() {
        if (this.status == WorkspaceStatus.CLOSED) {
            throw new TrystDomainException("Cannot lock a closed workspace");
        }
        this.status = WorkspaceStatus.LOCKED;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = WorkspaceStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public void addProject(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED) {
            throw new TrystDomainException("Cannot add project to workspace in status: " + this.status);
        }
        if (!projects.contains(project)) {
            projects.add(project);
            this.updatedAt = Instant.now();
        }
    }

    public void removeProject(String projectId) {
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED) {
            throw new TrystDomainException("Cannot remove project from workspace in status: " + this.status);
        }
        boolean removed = projects.removeIf(p -> p.getId().equals(projectId));
        if (removed) {
            this.updatedAt = Instant.now();
        }
    }

    public Optional<Project> getActiveProject() {
        return projects.stream().filter(Project::isActive).findFirst();
    }

    public void setActiveProject(String projectId) {
        for (Project project : projects) {
            project.setActive(project.getId().equals(projectId));
        }
        this.updatedAt = Instant.now();
    }

    public void addTask(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED) {
            throw new TrystDomainException("Cannot add task to workspace in status: " + this.status);
        }
        tasks.add(task);
        this.updatedAt = Instant.now();
    }

    public Optional<AgentTask> findTaskById(String taskId) {
        return tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Workspace workspace)) return false;
        return Objects.equals(id, workspace.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Workspace{" +
                "id='" + id + '\'' +
                ", rootPath=" + rootPath +
                ", status=" + status +
                ", projectsCount=" + projects.size() +
                ", tasksCount=" + tasks.size() +
                '}';
    }
}
