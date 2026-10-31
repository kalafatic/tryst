package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;

/**
 * Domain-level listener for model mutations and state changes.
 */
public interface WorkspaceChangeListener {

    default void onWorkspaceChanged(Workspace workspace) {}

    default void onProjectAdded(Project project) {}

    default void onProjectSelected(Project project) {}

    default void onTaskAdded(AgentTask task) {}

    default void onTaskUpdated(AgentTask task) {}

    default void onMessageLogged(ConsoleMessage message) {}
}
