package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Production-grade Domain service managing the lifecycle, allocation, isolation,
 * cleanup, and recovery of workspaces and sandboxes.
 * Supports multiple users and concurrent development tasks without shared mutable workspace state.
 * Prevents accidental deletion of active or recoverable work.
 */
public class WorkspaceService {

    private final Path defaultWorkspacePath;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final PermissionManager permissionManager;

    // Cache of active workspaces mapped by workspace ID
    private final Map<String, Workspace> workspacesById = new ConcurrentHashMap<>();
    // Cache of active workspaces mapped by project ID
    private final Map<String, Workspace> workspacesByProjectId = new ConcurrentHashMap<>();
    // Cache of active workspaces mapped by task ID
    private final Map<String, Workspace> workspacesByTaskId = new ConcurrentHashMap<>();

    private Workspace currentWorkspace;

    private final List<WorkspaceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ConsoleMessage> consoleMessages = new CopyOnWriteArrayList<>();

    public WorkspaceService() {
        this(Path.of(System.getProperty("user.home"), ".tryst", "workspace"), null, null, null, new PermissionManager());
    }

    public WorkspaceService(Path initialWorkspacePath) {
        this(initialWorkspacePath, null, null, null, new PermissionManager());
    }

    public WorkspaceService(Path initialWorkspacePath,
                            WorkspaceRepository workspaceRepository,
                            ProjectRepository projectRepository,
                            TaskRepository taskRepository) {
        this(initialWorkspacePath, workspaceRepository, projectRepository, taskRepository, new PermissionManager());
    }

    public WorkspaceService(Path initialWorkspacePath,
                            WorkspaceRepository workspaceRepository,
                            ProjectRepository projectRepository,
                            TaskRepository taskRepository,
                            PermissionManager permissionManager) {
        this.defaultWorkspacePath = Objects.requireNonNull(initialWorkspacePath, "initialWorkspacePath must not be null");
        this.workspaceRepository = workspaceRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        initializeDefaultWorkspace(initialWorkspacePath);
    }

    private void initializeDefaultWorkspace(Path initialWorkspacePath) {
        try {
            Files.createDirectories(initialWorkspacePath);
        } catch (IOException ignored) {}

        if (workspaceRepository != null) {
            currentWorkspace = workspaceRepository.findActive()
                    .orElseGet(() -> {
                        Workspace ws = new Workspace(initialWorkspacePath);
                        workspaceRepository.save(ws);
                        return ws;
                    });
            workspacesById.put(currentWorkspace.getId(), currentWorkspace);
            logMessage(ConsoleMessage.info("WorkspaceService", "Default workspace active at " + currentWorkspace.getRootPath()));
            notifyWorkspaceChanged(this.currentWorkspace);
        } else {
            openWorkspace(initialWorkspacePath);
        }
    }

    public synchronized void openWorkspace(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {}
        this.currentWorkspace = new Workspace(path);
        workspacesById.put(currentWorkspace.getId(), currentWorkspace);
        if (workspaceRepository != null) {
            workspaceRepository.save(this.currentWorkspace);
        }
        logMessage(ConsoleMessage.info("WorkspaceService", "Workspace opened at " + path));
        notifyWorkspaceChanged(this.currentWorkspace);
    }

    public synchronized Workspace getCurrentWorkspace() {
        return currentWorkspace;
    }

    /**
     * Allocates an isolated workspace specifically for a task, owner, and repository branch.
     * Prevents cross-task and cross-user workspace collision.
     */
    public synchronized Workspace allocateTaskWorkspace(String ownerId,
                                                         String taskId,
                                                         String projectId,
                                                         String repositoryUrl,
                                                         String branch) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");

        Path baseTasksDir = defaultWorkspacePath.resolve("sandboxes").resolve(ownerId).resolve(taskId);
        try {
            Files.createDirectories(baseTasksDir);
        } catch (IOException e) {
            throw new TrystDomainException("Failed to allocate workspace directory: " + baseTasksDir, e);
        }

        String wsId = UUID.randomUUID().toString();
        Workspace workspace = new Workspace(
                wsId,
                baseTasksDir,
                ownerId,
                taskId,
                repositoryUrl != null ? repositoryUrl : "",
                branch != null ? branch : "main",
                WorkspaceStatus.ALLOCATED,
                Instant.now(),
                Instant.now(),
                Map.of("allocated_at", Instant.now().toString(), "project_id", projectId != null ? projectId : "")
        );

        if (projectId != null && projectRepository != null) {
            projectRepository.findById(projectId).ifPresent(workspace::addProject);
        }

        workspacesById.put(workspace.getId(), workspace);
        workspacesByTaskId.put(taskId, workspace);

        if (workspaceRepository != null) {
            workspaceRepository.save(workspace);
        }

        TrystLog.info(WorkspaceService.class, "Allocated isolated workspace " + wsId + " for task " + taskId + " at " + baseTasksDir);
        return workspace;
    }

    /**
     * Transitions an allocated workspace to INITIALIZING and then READY after preparing working directory.
     */
    public synchronized Workspace initializeWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Workspace ws = getWorkspace(workspaceId);
        if (ws == null) {
            throw new TrystDomainException("Workspace not found: " + workspaceId);
        }

        ws.initialize();
        if (workspaceRepository != null) {
            workspaceRepository.save(ws);
        }

        ReentrantLock lock = permissionManager.getWorkspaceLock(ws.getRootPath());
        lock.lock();
        try {
            Files.createDirectories(ws.getRootPath());
            ws.markReady();
            if (workspaceRepository != null) {
                workspaceRepository.save(ws);
            }
            TrystLog.info(WorkspaceService.class, "Workspace " + workspaceId + " initialized and READY at " + ws.getRootPath());
            return ws;
        } catch (IOException e) {
            ws.fail();
            if (workspaceRepository != null) {
                workspaceRepository.save(ws);
            }
            throw new TrystDomainException("Failed to initialize workspace root: " + ws.getRootPath(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves the isolated workspace for a given task ID.
     */
    public synchronized Workspace getWorkspaceForTask(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Workspace ws = workspacesByTaskId.get(taskId);
        if (ws != null) {
            return ws;
        }
        if (workspaceRepository != null) {
            Optional<Workspace> persistent = workspaceRepository.findByTaskId(taskId);
            if (persistent.isPresent()) {
                ws = persistent.get();
                workspacesByTaskId.put(taskId, ws);
                workspacesById.put(ws.getId(), ws);
                return ws;
            }
        }
        return null;
    }

    public synchronized List<Workspace> getAllWorkspaces() {
        if (workspaceRepository != null) {
            return workspaceRepository.findAll();
        }
        return new ArrayList<>(workspacesById.values());
    }

    public synchronized void saveWorkspace(Workspace ws) {
        if (ws == null) return;
        workspacesById.put(ws.getId(), ws);
        if (ws.getTaskId() != null) {
            workspacesByTaskId.put(ws.getTaskId(), ws);
        }
        if (workspaceRepository != null) {
            workspaceRepository.save(ws);
        }
    }

    /**
     * Resolves the isolated workspace for a given project.
     * Guarantees that file operations for this project are scoped to the project's own workspace root.
     */
    public synchronized Workspace getWorkspaceForProject(String projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        return workspacesByProjectId.computeIfAbsent(projectId, id -> {
            if (projectRepository != null) {
                Optional<Project> optProj = projectRepository.findById(id);
                if (optProj.isPresent()) {
                    Project p = optProj.get();
                    Workspace ws = new Workspace(UUID.randomUUID().toString(), p.getLocation(), p.getOwnerId(), null,
                            WorkspaceStatus.ACTIVE, Instant.now(), Instant.now());
                    ws.addProject(p);
                    if (workspaceRepository != null) {
                        workspaceRepository.save(ws);
                    }
                    workspacesById.put(ws.getId(), ws);
                    return ws;
                }
            }
            if (currentWorkspace != null) {
                for (Project p : currentWorkspace.getProjects()) {
                    if (p.getId().equals(id)) {
                        Workspace ws = new Workspace(UUID.randomUUID().toString(), p.getLocation(), p.getOwnerId(), null,
                                WorkspaceStatus.ACTIVE, Instant.now(), Instant.now());
                        ws.addProject(p);
                        if (workspaceRepository != null) {
                            workspaceRepository.save(ws);
                        }
                        workspacesById.put(ws.getId(), ws);
                        return ws;
                    }
                }
            }
            return currentWorkspace;
        });
    }

    public synchronized Workspace getWorkspace(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Workspace ws = workspacesById.get(workspaceId);
        if (ws != null) {
            return ws;
        }
        if (workspaceRepository != null) {
            Optional<Workspace> persistent = workspaceRepository.findById(workspaceId);
            if (persistent.isPresent()) {
                ws = persistent.get();
                workspacesById.put(workspaceId, ws);
                return ws;
            }
        }
        return null;
    }

    /**
     * Safely marks a workspace for cleanup and performs disk cleanup.
     * Enforces the invariant: Workspace cleanup must NEVER delete active or recoverable work
     * (ACTIVE, INITIALIZING, WAITING_FOR_REVIEW, REWORKING).
     */
    public synchronized boolean cleanupWorkspace(String workspaceId, boolean deleteDirectory) {
        Workspace ws = getWorkspace(workspaceId);
        if (ws == null) {
            return false;
        }

        WorkspaceStatus status = ws.getStatus();
        if (!status.isSafeToCleanup()) {
            throw new TrystDomainException("Refusing to clean up workspace " + workspaceId +
                    " because it contains active or recoverable work in state: " + status);
        }

        ReentrantLock lock = permissionManager.getWorkspaceLock(ws.getRootPath());
        lock.lock();
        try {
            ws.markCleanupPending();
            if (workspaceRepository != null) {
                workspaceRepository.save(ws);
            }

            if (deleteDirectory && Files.exists(ws.getRootPath())) {
                deleteDirectoryRecursively(ws.getRootPath());
            }

            ws.archive();
            if (workspaceRepository != null) {
                workspaceRepository.save(ws);
            }

            workspacesById.remove(workspaceId);
            if (ws.getTaskId() != null) {
                workspacesByTaskId.remove(ws.getTaskId());
            }

            TrystLog.info(WorkspaceService.class, "Safely cleaned up workspace " + workspaceId);
            return true;
        } catch (IOException e) {
            TrystLog.error(WorkspaceService.class, "Error deleting workspace files: " + e.getMessage(), e);
            ws.fail();
            if (workspaceRepository != null) {
                workspaceRepository.save(ws);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void deleteDirectoryRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    public synchronized Project addProject(String name, Path location) {
        return addProject(Project.DEFAULT_OWNER_ID, name, "", location);
    }

    public synchronized Project addProject(String ownerId, String name, String description, Path location) {
        if (currentWorkspace == null) {
            throw new TrystException("No active workspace available.");
        }
        Objects.requireNonNull(location, "location must not be null");
        try {
            Files.createDirectories(location);
        } catch (IOException ignored) {}

        Project project = new Project(java.util.UUID.randomUUID().toString(), ownerId, name, description, location,
                eu.kalafatic.tryst.domain.model.ProjectStatus.ACTIVE, false, java.time.Instant.now(), java.time.Instant.now());

        currentWorkspace.addProject(project);
        Workspace scopedWs = new Workspace(UUID.randomUUID().toString(), project.getLocation(), ownerId, null,
                WorkspaceStatus.ACTIVE, Instant.now(), Instant.now());
        scopedWs.addProject(project);
        workspacesByProjectId.put(project.getId(), scopedWs);
        workspacesById.put(scopedWs.getId(), scopedWs);

        if (projectRepository != null) {
            projectRepository.save(project);
        }
        if (workspaceRepository != null) {
            workspaceRepository.save(currentWorkspace);
            workspaceRepository.save(scopedWs);
        }
        logMessage(ConsoleMessage.info("WorkspaceService", "Project added: " + name + " (Owner: " + ownerId + ")"));
        notifyProjectAdded(project);
        return project;
    }

    public synchronized void selectProject(String projectId) {
        if (currentWorkspace == null) {
            return;
        }
        currentWorkspace.setActiveProject(projectId);
        if (projectRepository != null) {
            for (Project project : currentWorkspace.getProjects()) {
                projectRepository.save(project);
            }
        }
        if (workspaceRepository != null) {
            workspaceRepository.save(currentWorkspace);
        }
        currentWorkspace.getActiveProject().ifPresent(project -> {
            logMessage(ConsoleMessage.info("WorkspaceService", "Active project changed to: " + project.getName()));
            notifyProjectSelected(project);
        });
    }

    public synchronized AgentTask createTask(String title, String description) {
        if (currentWorkspace == null) {
            throw new TrystException("No active workspace available.");
        }
        String projectId = currentWorkspace.getActiveProject().map(Project::getId).orElse(null);
        return createTask(projectId, title, description);
    }

    public synchronized AgentTask createTask(String projectId, String title, String description) {
        if (currentWorkspace == null) {
            throw new TrystException("No active workspace available.");
        }
        AgentTask task = new AgentTask(java.util.UUID.randomUUID().toString(), projectId, null, title, description,
                eu.kalafatic.tryst.domain.model.TaskStatus.CREATED, eu.kalafatic.tryst.domain.model.TaskPriority.NORMAL,
                null, null, java.time.Instant.now(), null);
        currentWorkspace.addTask(task);
        if (taskRepository != null) {
            taskRepository.save(task);
        }
        logMessage(ConsoleMessage.info("WorkspaceService", "Task created: " + title));
        notifyTaskAdded(task);
        return task;
    }

    public synchronized void updateTask(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (taskRepository != null) {
            taskRepository.save(task);
        }
    }

    public synchronized void logMessage(ConsoleMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        consoleMessages.add(message);
        notifyMessageLogged(message);

        // Forward to central TrystLog facility
        try {
            eu.kalafatic.tryst.core.log.LogLevel lvl = eu.kalafatic.tryst.core.log.LogLevel.fromString(message.level());
            eu.kalafatic.tryst.core.log.TrystLog.log(lvl, WorkspaceService.class, "[" + message.source() + "] " + message.message(), null);
        } catch (Throwable ignored) {}
    }

    public List<ConsoleMessage> getConsoleMessages() {
        return Collections.unmodifiableList(new ArrayList<>(consoleMessages));
    }

    public void addChangeListener(WorkspaceChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeChangeListener(WorkspaceChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyWorkspaceChanged(Workspace workspace) {
        for (WorkspaceChangeListener listener : listeners) {
            listener.onWorkspaceChanged(workspace);
        }
    }

    private void notifyProjectAdded(Project project) {
        for (WorkspaceChangeListener listener : listeners) {
            listener.onProjectAdded(project);
        }
    }

    private void notifyProjectSelected(Project project) {
        for (WorkspaceChangeListener listener : listeners) {
            listener.onProjectSelected(project);
        }
    }

    private void notifyTaskAdded(AgentTask task) {
        for (WorkspaceChangeListener listener : listeners) {
            listener.onTaskAdded(task);
        }
    }

    private void notifyMessageLogged(ConsoleMessage message) {
        for (WorkspaceChangeListener listener : listeners) {
            listener.onMessageLogged(message);
        }
    }
}
