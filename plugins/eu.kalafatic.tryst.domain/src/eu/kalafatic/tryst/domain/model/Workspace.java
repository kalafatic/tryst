package eu.kalafatic.tryst.domain.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain entity representing the active workspace environment.
 */
public class Workspace {

    private final Path rootPath;
    private final List<Project> projects = new ArrayList<>();
    private final List<AgentTask> tasks = new ArrayList<>();

    public Workspace(Path rootPath) {
        this.rootPath = Objects.requireNonNull(rootPath, "rootPath must not be null");
    }

    public Path getRootPath() {
        return rootPath;
    }

    public List<Project> getProjects() {
        return Collections.unmodifiableList(projects);
    }

    public List<AgentTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public void addProject(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        if (!projects.contains(project)) {
            projects.add(project);
        }
    }

    public void removeProject(String projectId) {
        projects.removeIf(p -> p.getId().equals(projectId));
    }

    public Optional<Project> getActiveProject() {
        return projects.stream().filter(Project::isActive).findFirst();
    }

    public void setActiveProject(String projectId) {
        for (Project project : projects) {
            project.setActive(project.getId().equals(projectId));
        }
    }

    public void addTask(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        tasks.add(task);
    }

    public Optional<AgentTask> findTaskById(String taskId) {
        return tasks.stream().filter(t -> t.getId().equals(taskId)).findFirst();
    }
}
