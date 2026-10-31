package eu.kalafatic.tryst.ui.controller;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Controller coordinating Project Explorer view actions with the domain model.
 */
public class ProjectExplorerController implements WorkspaceChangeListener {

    private final WorkspaceService workspaceService;
    private Runnable refreshViewCallback;

    public ProjectExplorerController(WorkspaceService workspaceService) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.workspaceService.addChangeListener(this);
    }

    public void setViewCallback(Runnable refreshViewCallback) {
        this.refreshViewCallback = refreshViewCallback;
    }

    public List<Project> getProjects() {
        Workspace ws = workspaceService.getCurrentWorkspace();
        return ws != null ? ws.getProjects() : Collections.emptyList();
    }

    public void handleProjectSelection(Project project) {
        if (project != null) {
            workspaceService.selectProject(project.getId());
        }
    }

    public void handleAddProject(String name, Path path) {
        if (name != null && !name.trim().isEmpty() && path != null) {
            workspaceService.addProject(name.trim(), path);
        }
    }

    @Override
    public void onWorkspaceChanged(Workspace workspace) {
        triggerRefresh();
    }

    @Override
    public void onProjectAdded(Project project) {
        triggerRefresh();
    }

    @Override
    public void onProjectSelected(Project project) {
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
