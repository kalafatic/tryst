package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Domain service managing the lifecycle, projects, tasks, and console messages of the active workspace.
 * Completely independent of UI/SWT/Workbench.
 */
public class WorkspaceService {

    private Workspace currentWorkspace;
    private final List<WorkspaceChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ConsoleMessage> consoleMessages = new CopyOnWriteArrayList<>();

    public WorkspaceService() {
        this(Path.of(System.getProperty("user.home"), ".tryst", "workspace"));
    }

    public WorkspaceService(Path initialWorkspacePath) {
        openWorkspace(initialWorkspacePath);
    }

    public synchronized void openWorkspace(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        this.currentWorkspace = new Workspace(path);
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
        logMessage(ConsoleMessage.info("WorkspaceService", "Project added: " + name));
        notifyProjectAdded(project);
        return project;
    }

    public synchronized void selectProject(String projectId) {
        if (currentWorkspace == null) {
            return;
        }
        currentWorkspace.setActiveProject(projectId);
        currentWorkspace.getActiveProject().ifPresent(project -> {
            logMessage(ConsoleMessage.info("WorkspaceService", "Active project changed to: " + project.getName()));
            notifyProjectSelected(project);
        });
    }

    public synchronized AgentTask createTask(String title, String description) {
        if (currentWorkspace == null) {
            throw new TrystException("No active workspace available.");
        }
        AgentTask task = new AgentTask(title, description);
        currentWorkspace.addTask(task);
        logMessage(ConsoleMessage.info("WorkspaceService", "Task created: " + title));
        notifyTaskAdded(task);
        return task;
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
