package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates the execution context of an active autonomous task.
 * Guarantees task, workspace, and tenant isolation:
 * no shared mutable currentTask, no global currentWorkspace, and explicit per-task cancellation state.
 */
public final class TaskExecutionContext {

    private final Task task;
    private final Project project;
    private final Workspace workspace;
    private final String userId;
    private final String workerId;
    private final String modelName;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public TaskExecutionContext(Task task, Project project, Workspace workspace, String userId, String workerId, String modelName) {
        this.task = Objects.requireNonNull(task, "task must not be null");
        this.project = project;
        this.workspace = workspace;
        this.userId = userId != null ? userId : "default-user";
        this.workerId = Objects.requireNonNull(workerId, "workerId must not be null");
        this.modelName = modelName != null ? modelName : "deepseek-coder:6.7b";
    }

    public Task getTask() {
        return task;
    }

    public String getTaskId() {
        return task.getId();
    }

    public Project getProject() {
        return project;
    }

    public String getProjectId() {
        return task.getProjectId();
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Path getWorkspaceRoot() {
        return workspace != null ? workspace.getRootPath() : null;
    }

    public String getUserId() {
        return userId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getModelName() {
        return modelName;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public String toString() {
        return "TaskExecutionContext{" +
                "taskId='" + task.getId() + '\'' +
                ", projectId='" + getProjectId() + '\'' +
                ", workerId='" + workerId + '\'' +
                ", userId='" + userId + '\'' +
                ", cancelled=" + isCancelled() +
                '}';
    }
}
