package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain entity representing the physical working environment associated with a project or task.
 * Provides a clear boundary for file operations, Git operations, builds, and agent execution.
 * Permanently associated with its task, project, and owner.
 */
public class Workspace {

    private final String id;
    private final Path rootPath;
    private final String ownerId;
    private final String taskId;
    private String repositoryUrl;
    private String branch;
    private WorkspaceStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final List<Project> projects = new ArrayList<>();
    private final List<AgentTask> tasks = new ArrayList<>();

    public Workspace(Path rootPath) {
        this(UUID.randomUUID().toString(), rootPath);
    }

    public Workspace(String id, Path rootPath) {
        this(id, rootPath, Project.DEFAULT_OWNER_ID, null, WorkspaceStatus.ACTIVE, Instant.now(), Instant.now());
    }

    public Workspace(String id, Path rootPath, WorkspaceStatus status, Instant createdAt, Instant updatedAt) {
        this(id, rootPath, Project.DEFAULT_OWNER_ID, null, status, createdAt, updatedAt);
    }

    public Workspace(String id,
                     Path rootPath,
                     String ownerId,
                     String taskId,
                     WorkspaceStatus status,
                     Instant createdAt,
                     Instant updatedAt) {
        this(id, rootPath, ownerId, taskId, null, "main", status, createdAt, updatedAt, Map.of());
    }

    public Workspace(String id,
                     Path rootPath,
                     String ownerId,
                     String taskId,
                     String repositoryUrl,
                     String branch,
                     WorkspaceStatus status,
                     Instant createdAt,
                     Instant updatedAt,
                     Map<String, String> metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rootPath = Objects.requireNonNull(rootPath, "rootPath must not be null");
        this.ownerId = ownerId != null && !ownerId.isBlank() ? ownerId : Project.DEFAULT_OWNER_ID;
        this.taskId = taskId;
        this.repositoryUrl = repositoryUrl != null ? repositoryUrl : "";
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
    }

    public String getId() {
        return id;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl != null ? repositoryUrl : "";
        this.updatedAt = Instant.now();
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.updatedAt = Instant.now();
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

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public void setMetadata(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            this.metadata.remove(key);
        } else {
            this.metadata.put(key, value);
        }
        this.updatedAt = Instant.now();
    }

    public List<Project> getProjects() {
        return Collections.unmodifiableList(projects);
    }

    public List<AgentTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    // Explicit Lifecycle State Transitions
    public void transitionTo(WorkspaceStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        WorkspaceStateTransitionValidator.validateTransition(this.status, newStatus);
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void allocate() {
        transitionTo(WorkspaceStatus.ALLOCATED);
    }

    public void initialize() {
        transitionTo(WorkspaceStatus.INITIALIZING);
    }

    public void markReady() {
        transitionTo(WorkspaceStatus.READY);
    }

    public void activate() {
        transitionTo(WorkspaceStatus.ACTIVE);
    }

    public void markWaitingForReview() {
        transitionTo(WorkspaceStatus.WAITING_FOR_REVIEW);
    }

    public void rework() {
        transitionTo(WorkspaceStatus.REWORKING);
    }

    public void complete() {
        transitionTo(WorkspaceStatus.COMPLETED);
    }

    public void archive() {
        transitionTo(WorkspaceStatus.ARCHIVED);
    }

    public void markCleanupPending() {
        if (!status.isSafeToCleanup()) {
            throw new TrystDomainException("Cannot mark workspace " + id + " for cleanup while in active/recoverable state: " + status);
        }
        transitionTo(WorkspaceStatus.CLEANUP_PENDING);
    }

    public void fail() {
        transitionTo(WorkspaceStatus.FAILED);
    }

    // Compatibility methods for previous tests/code
    public void deactivate() {
        transitionTo(WorkspaceStatus.INACTIVE);
    }

    public void lock() {
        transitionTo(WorkspaceStatus.LOCKED);
    }

    public void close() {
        transitionTo(WorkspaceStatus.CLOSED);
    }

    public void addProject(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED ||
            this.status == WorkspaceStatus.ARCHIVED || this.status == WorkspaceStatus.CLEANUP_PENDING) {
            throw new TrystDomainException("Cannot add project to workspace in status: " + this.status);
        }
        if (!projects.contains(project)) {
            projects.add(project);
            this.updatedAt = Instant.now();
        }
    }

    public void removeProject(String projectId) {
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED ||
            this.status == WorkspaceStatus.ARCHIVED || this.status == WorkspaceStatus.CLEANUP_PENDING) {
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
        if (this.status == WorkspaceStatus.CLOSED || this.status == WorkspaceStatus.LOCKED ||
            this.status == WorkspaceStatus.ARCHIVED || this.status == WorkspaceStatus.CLEANUP_PENDING) {
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
                ", ownerId='" + ownerId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", branch='" + branch + '\'' +
                ", status=" + status +
                ", projectsCount=" + projects.size() +
                ", tasksCount=" + tasks.size() +
                '}';
    }
}
