package eu.kalafatic.tryst.ui.controller;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.service.AutonomousDevelopmentService;
import eu.kalafatic.tryst.domain.service.SessionTaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Enhanced WorkspaceController coordinating project selection, tasks, sessions,
 * and autonomous development dispatch on background threads.
 */
public class WorkspaceController implements WorkspaceChangeListener {

    private final WorkspaceService workspaceService;
    private final SessionTaskOrchestrator sessionTaskOrchestrator;
    private final AutonomousDevelopmentService autonomousDevelopmentService;
    private final AiInferenceService aiInferenceService;
    private final AsyncExecutionService asyncExecutionService;
    private final EventBus eventBus;

    private Runnable refreshViewCallback;
    private Consumer<String> streamCallback;
    private Session activeSession;

    public WorkspaceController(
            WorkspaceService workspaceService,
            SessionTaskOrchestrator sessionTaskOrchestrator,
            AutonomousDevelopmentService autonomousDevelopmentService,
            AiInferenceService aiInferenceService,
            AsyncExecutionService asyncExecutionService,
            EventBus eventBus
    ) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.sessionTaskOrchestrator = sessionTaskOrchestrator;
        this.autonomousDevelopmentService = autonomousDevelopmentService;
        this.aiInferenceService = aiInferenceService;
        this.asyncExecutionService = asyncExecutionService;
        this.eventBus = eventBus;
        this.workspaceService.addChangeListener(this);
    }

    public WorkspaceController(WorkspaceService workspaceService) {
        this(workspaceService, null, null, null, null, null);
    }

    public void setViewCallback(Runnable refreshViewCallback) {
        this.refreshViewCallback = refreshViewCallback;
    }

    public void setStreamCallback(Consumer<String> streamCallback) {
        this.streamCallback = streamCallback;
    }

    public Optional<Project> getActiveProject() {
        var ws = workspaceService.getCurrentWorkspace();
        return ws != null ? ws.getActiveProject() : Optional.empty();
    }

    public List<AgentTask> getTasks() {
        var ws = workspaceService.getCurrentWorkspace();
        return ws != null ? ws.getTasks() : Collections.emptyList();
    }

    public List<Session> getSessions() {
        if (sessionTaskOrchestrator != null) {
            return sessionTaskOrchestrator.listSessions();
        }
        return Collections.emptyList();
    }

    public Session getActiveSession() {
        return activeSession;
    }

    public void setActiveSession(Session session) {
        this.activeSession = session;
        triggerRefresh();
    }

    public Session createSession(String name) {
        if (sessionTaskOrchestrator != null) {
            String projectId = getActiveProject().map(Project::getId).orElse("default-proj");
            Session session = sessionTaskOrchestrator.createSession(projectId, name);
            this.activeSession = session;
            triggerRefresh();
            return session;
        }
        return null;
    }

    public List<ConversationMessage> getActiveSessionMessages() {
        if (activeSession != null && sessionTaskOrchestrator != null) {
            return sessionTaskOrchestrator.getSessionMessages(activeSession.getId());
        }
        return Collections.emptyList();
    }

    public void handleCreateTask(String title, String description) {
        if (title != null && !title.trim().isEmpty()) {
            AgentTask task = workspaceService.createTask(title.trim(), description);
            if (activeSession != null && sessionTaskOrchestrator != null) {
                sessionTaskOrchestrator.addMessage(activeSession.getId(), "USER", "Created task: " + title);
            }
        }
    }

    public void runTaskWithAgent(AgentTask task) {
        if (task == null) return;
        if (autonomousDevelopmentService != null && aiInferenceService != null) {
            autonomousDevelopmentService.executeAutonomousDevelopment(
                    task,
                    aiInferenceService,
                    "codellama",
                    5,
                    chunk -> {
                        if (streamCallback != null) {
                            streamCallback.accept(chunk);
                        }
                    }
            ).whenComplete((summary, error) -> triggerRefresh());
        } else if (sessionTaskOrchestrator != null && aiInferenceService != null) {
            sessionTaskOrchestrator.executeTaskWithAgent(
                    task,
                    aiInferenceService,
                    "codellama",
                    5,
                    chunk -> {
                        if (streamCallback != null) {
                            streamCallback.accept(chunk);
                        }
                    }
            ).whenComplete((res, error) -> triggerRefresh());
        }
    }

    public void cancelCurrentExecution() {
        if (sessionTaskOrchestrator != null) {
            sessionTaskOrchestrator.cancelCurrentExecution();
        }
    }

    @Override
    public void onWorkspaceChanged(eu.kalafatic.tryst.domain.model.Workspace workspace) {
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
        this.streamCallback = null;
    }
}
