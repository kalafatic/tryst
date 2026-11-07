package eu.kalafatic.tryst.ui.context;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable typed value object representing the active selection context across the TRYST workbench.
 */
public record WorkbenchContext(
        String projectId,
        String projectName,
        Project project,
        String repositoryPath,
        String branchName,
        String taskId,
        String taskTitle,
        AgentTask task,
        String sessionId,
        Session session,
        String workspaceId,
        Workspace workspace,
        Map<String, String> properties
) {

    public static final WorkbenchContext EMPTY = new WorkbenchContext(
            null, null, null, null, null, null, null, null, null, null, null, null, Map.of()
    );

    public WorkbenchContext {
        properties = properties != null ? Collections.unmodifiableMap(Map.copyOf(properties)) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public Optional<Project> getProject() {
        return Optional.ofNullable(project);
    }

    public Optional<AgentTask> getTask() {
        return Optional.ofNullable(task);
    }

    public Optional<Session> getSession() {
        return Optional.ofNullable(session);
    }

    public Optional<Workspace> getWorkspace() {
        return Optional.ofNullable(workspace);
    }

    public boolean hasProject() {
        return projectId != null && !projectId.isBlank();
    }

    public boolean hasTask() {
        return taskId != null && !taskId.isBlank();
    }

    public boolean hasSession() {
        return sessionId != null && !sessionId.isBlank();
    }

    public static class Builder {
        private String projectId;
        private String projectName;
        private Project project;
        private String repositoryPath;
        private String branchName;
        private String taskId;
        private String taskTitle;
        private AgentTask task;
        private String sessionId;
        private Session session;
        private String workspaceId;
        private Workspace workspace;
        private Map<String, String> properties;

        public Builder() {
        }

        public Builder(WorkbenchContext source) {
            if (source != null) {
                this.projectId = source.projectId();
                this.projectName = source.projectName();
                this.project = source.project();
                this.repositoryPath = source.repositoryPath();
                this.branchName = source.branchName();
                this.taskId = source.taskId();
                this.taskTitle = source.taskTitle();
                this.task = source.task();
                this.sessionId = source.sessionId();
                this.session = source.session();
                this.workspaceId = source.workspaceId();
                this.workspace = source.workspace();
                this.properties = source.properties();
            }
        }

        public Builder project(Project project) {
            this.project = project;
            if (project != null) {
                this.projectId = project.getId();
                this.projectName = project.getName();
                if (project.getLocation() != null) {
                    this.repositoryPath = project.getLocation().toString();
                }
            }
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public Builder repositoryPath(String repositoryPath) {
            this.repositoryPath = repositoryPath;
            return this;
        }

        public Builder branchName(String branchName) {
            this.branchName = branchName;
            return this;
        }

        public Builder task(AgentTask task) {
            this.task = task;
            if (task != null) {
                this.taskId = task.getId();
                this.taskTitle = task.getTitle();
            }
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskTitle(String taskTitle) {
            this.taskTitle = taskTitle;
            return this;
        }

        public Builder session(Session session) {
            this.session = session;
            if (session != null) {
                this.sessionId = session.getId();
            }
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            if (workspace != null) {
                this.workspaceId = workspace.getId();
            }
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder properties(Map<String, String> properties) {
            this.properties = properties;
            return this;
        }

        public WorkbenchContext build() {
            return new WorkbenchContext(
                    projectId,
                    projectName,
                    project,
                    repositoryPath,
                    branchName,
                    taskId,
                    taskTitle,
                    task,
                    sessionId,
                    session,
                    workspaceId,
                    workspace,
                    properties != null ? properties : Map.of()
            );
        }
    }
}
