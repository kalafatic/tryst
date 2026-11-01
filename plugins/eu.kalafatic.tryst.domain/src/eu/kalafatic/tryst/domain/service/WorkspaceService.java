package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain service managing the lifecycle, projects, tasks, and console messages of the active workspace.
 * Integrated with repository contracts for state persistence across application restarts.
 * Completely independent of UI/SWT/Workbench/JDBC/SQLite.
 */
public class WorkspaceService {

    private Workspace currentWorkspace;
    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

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

    public synchronized Project addProject(String name, Path location) {
        if (currentWorkspace == null) {
            throw new TrystException("No active workspace available.");
        }
        Project project = new Project(name, location);
        currentWorkspace.addProject(project);
        if (projectRepository != null) {
            projectRepository.save(project);
        }
        if (workspaceRepository != null) {
            workspaceRepository.save(currentWorkspace);
        }
        logMessage(ConsoleMessage.info("WorkspaceService", "Project added: " + name));
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
        AgentTask task = new AgentTask(java.util.UUID.randomUUID().toString(), projectId, null, title, description,
                eu.kalafatic.tryst.domain.model.ExecutionStatus.IDLE, java.time.Instant.now(), null);
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
