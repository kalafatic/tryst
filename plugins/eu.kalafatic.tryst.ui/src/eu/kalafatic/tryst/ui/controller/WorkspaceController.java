package eu.kalafatic.tryst.ui.controller;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Controller coordinating main workspace display, project focus, and task tracking.
 */
public class WorkspaceController implements WorkspaceChangeListener {

    private final WorkspaceService workspaceService;
    private Runnable refreshViewCallback;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.workspaceService.addChangeListener(this);
    }

    public void setViewCallback(Runnable refreshViewCallback) {
        this.refreshViewCallback = refreshViewCallback;
    }

    public Optional<Project> getActiveProject() {
        Workspace ws = workspaceService.getCurrentWorkspace();
        return ws != null ? ws.getActiveProject() : Optional.empty();
    }

    public List<AgentTask> getTasks() {
        Workspace ws = workspaceService.getCurrentWorkspace();
        return ws != null ? ws.getTasks() : Collections.emptyList();
    }

    public void handleCreateTask(String title, String description) {
        if (title != null && !title.trim().isEmpty()) {
            workspaceService.createTask(title.trim(), description);
        }
    }

    @Override
    public void onWorkspaceChanged(Workspace workspace) {
        triggerRefresh();
    }

    @Override
    public void onProjectSelected(Project project) {
        triggerRefresh();
    }

    @Override
    public void onTaskAdded(AgentTask task) {
        triggerRefresh();
    }

    @Override
    public void onTaskUpdated(AgentTask task) {
        triggerRefresh();
    }

    private void triggerRefresh() {
        if (refreshViewCallback != null) {
            refreshViewCallback.run();
        }
    }

    public void dispose() {
        workspaceService.removeChangeListener(this);
        this.refreshViewCallback = null;
    }
}
