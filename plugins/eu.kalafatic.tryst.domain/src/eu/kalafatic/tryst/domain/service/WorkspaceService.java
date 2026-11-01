package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain service managing the lifecycle, projects, tasks, and console messages of workspaces.
 * Supports multi-project and multi-user workspace isolation.
 * Completely independent of UI/SWT/Workbench/JDBC/SQLite.
 */
public class WorkspaceService {

    private final Path defaultWorkspacePath;
    private Workspace currentWorkspace;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    // Cache of active workspaces mapped by project ID / workspace ID
    private final Map<String, Workspace> workspacesByProjectId = new ConcurrentHashMap<>();

    private final List<WorkspaceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ConsoleMessage> consoleMessages = new CopyOnWriteArrayList<>();

    public WorkspaceService() {
        this(Path.of(System.getProperty("user.home"), ".tryst", "workspace"), null, null, null);
    }

    public WorkspaceService(Path initialWorkspacePath) {
        this(initialWorkspacePath, null, null, null);
    }

    public WorkspaceService(Path initialWorkspacePath,
                            WorkspaceRepository workspaceRepository,
                            ProjectRepository projectRepository,
                            TaskRepository taskRepository) {
        this.defaultWorkspacePath = Objects.requireNonNull(initialWorkspacePath, "initialWorkspacePath must not be null");
        this.workspaceRepository = workspaceRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        initializeWorkspace(initialWorkspacePath);
    }

    private void initializeWorkspace(Path initialWorkspacePath) {
        if (workspaceRepository != null) {
            currentWorkspace = workspaceRepository.findActive()
                    .orElseGet(() -> {
                        Workspace ws = new Workspace(initialWorkspacePath);
                        workspaceRepository.save(ws);
                        return ws;
                    });
            logMessage(ConsoleMessage.info("WorkspaceService", "Workspace active at " + currentWorkspace.getRootPath()));
            notifyWorkspaceChanged(this.currentWorkspace);
        } else {
            openWorkspace(initialWorkspacePath);
        }
    }

    public synchronized void openWorkspace(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        this.currentWorkspace = new Workspace(path);
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
                    Workspace ws = new Workspace(p.getLocation());
                    ws.addProject(p);
                    return ws;
                }
            }
            if (currentWorkspace != null) {
                for (Project p : currentWorkspace.getProjects()) {
                    if (p.getId().equals(id)) {
                        Workspace ws = new Workspace(p.getLocation());
                        ws.addProject(p);
                        return ws;
                    }
                }
            }
            return currentWorkspace;
        });
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
        Workspace scopedWs = new Workspace(project.getLocation());
        scopedWs.addProject(project);
        workspacesByProjectId.put(project.getId(), scopedWs);

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
