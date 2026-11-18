package eu.kalafatic.tryst.domain.contextual;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import eu.kalafatic.tryst.domain.contextual.ContextualActionDescriptor.Confirmation;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.SessionStatus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;

/**
 * TRYST-local contextual actions (Darwin Family Contextual Action Contract v1.0).
 * TRYST owns task/workflow/repository/sandbox/session lifecycle, so this provider
 * exposes the real TaskOrchestrator/Session/Project operations with state-aware
 * availability. Unsupported lifecycle verbs are contributed disabled with an honest
 * reason instead of being hidden.
 */
public final class TrystContextualActions {

    private TrystContextualActions() {
    }

    public static void install(ContextualActionRegistry registry,
            TaskOrchestrator orchestrator, TaskRepository taskRepository,
            SessionRepository sessionRepository, ProjectRepository projectRepository) {
        registry.registerProvider(new TaskContextProvider(taskRepository));
        registry.registerProvider(new SessionContextProvider(sessionRepository));
        registry.registerProvider(new ProjectContextProvider(projectRepository));

        registry.registerHandler(handler(ContextualActionIds.SESSION_PAUSE, (sel, fb) -> {
            if ("task".equals(sel.objectType())) {
                if (orchestrator.pauseTask(sel.objectId(), "dev-user")) {
                    fb.info("Task paused");
                } else {
                    fb.error("Task could not be paused in its current state", null);
                }
                return;
            }
            Optional<Session> session = sessionRepository.findById(sel.objectId());
            if (session.isEmpty()) {
                fb.error("Session not found", null);
                return;
            }
            session.get().pause();
            sessionRepository.save(session.get());
            fb.info("Session paused");
        }));
        registry.registerHandler(handler(ContextualActionIds.SESSION_RESUME, (sel, fb) -> {
            if ("task".equals(sel.objectType())) {
                if (orchestrator.resumeTask(sel.objectId(), "dev-user")) {
                    fb.info("Task resumed");
                } else {
                    fb.error("Task could not be resumed in its current state", null);
                }
                return;
            }
            Optional<Session> session = sessionRepository.findById(sel.objectId());
            if (session.isEmpty()) {
                fb.error("Session not found", null);
                return;
            }
            session.get().resume();
            sessionRepository.save(session.get());
            fb.info("Session resumed");
        }));
        registry.registerHandler(handler(ContextualActionIds.SESSION_CANCEL, (sel, fb) -> {
            if (orchestrator.cancelTask(sel.objectId(), "cancelled by user")) {
                fb.info("Task cancelled");
            } else {
                fb.error("Task could not be cancelled in its current state", null);
            }
        }));
        registry.registerHandler(handler(ContextualActionIds.SESSION_ARCHIVE, (sel, fb) -> {
            if (orchestrator.archiveTask(sel.objectId(), "dev-user")) {
                fb.info("Task archived");
            } else {
                fb.error("Task could not be archived in its current state", null);
            }
        }));
        registry.registerHandler(handler(ContextualActionIds.SESSION_DELETE, (sel, fb) -> {
            if (orchestrator.deleteTask(sel.objectId(), false, "dev-user")) {
                fb.info("Task deleted");
            } else {
                fb.error("Task could not be deleted", null);
            }
        }));
        registry.registerHandler(handler(ContextualActionIds.TASK_MOVE_UP, (sel, fb) -> {
            orchestrator.moveTaskUp(sel.objectId());
            fb.info("Task moved up");
        }));
        registry.registerHandler(handler(ContextualActionIds.TASK_MOVE_DOWN, (sel, fb) -> {
            orchestrator.moveTaskDown(sel.objectId());
            fb.info("Task moved down");
        }));
        registry.registerHandler(handler(ContextualActionIds.SESSION_DETAILS, (sel, fb) -> {
            Optional<Task> task = taskRepository.findTaskById(sel.objectId());
            if (task.isEmpty()) {
                fb.error("Task not found", null);
                return;
            }
            Task t = task.get();
            fb.info("Id: " + t.getId() + " | Status: " + t.getStatus()
                    + " | Priority: " + t.getPriority() + " | Project: "
                    + t.getProjectId() + " | Session: " + t.getSessionId()
                    + " | Agent: " + t.getAgentType() + " | Title: " + t.getTitle());
        }));
        registry.registerHandler(handler(ContextualActionIds.PROJECT_CLOSE, (sel, fb) -> {
            Optional<Project> project = projectRepository.findById(sel.objectId());
            if (project.isEmpty()) {
                fb.error("Project not found", null);
                return;
            }
            project.get().close();
            projectRepository.save(project.get());
            fb.info("Project closed");
        }));
    }

    private static ContextualActionHandler handler(String actionId, Body body) {
        return new ContextualActionHandler() {
            @Override
            public String actionId() {
                return actionId;
            }

            @Override
            public void execute(ContextualSelection selection, ActionFeedback feedback)
                    throws Exception {
                body.run(selection, feedback);
            }
        };
    }

    private interface Body {
        void run(ContextualSelection selection, ActionFeedback feedback) throws Exception;
    }

    // ------------------------------------------------------------- providers

    private static final class TaskContextProvider
            implements ContextualActionProvider {

        private final TaskRepository taskRepository;

        TaskContextProvider(TaskRepository taskRepository) {
            this.taskRepository = taskRepository;
        }

        @Override
        public List<String> contextTypes() {
            return List.of("task");
        }

        @Override
        public List<ContextualAction> actionsFor(ContextualSelection selection) {
            List<ContextualAction> actions = new ArrayList<>();
            Optional<Task> taskOpt = taskRepository.findTaskById(selection.objectId());
            if (taskOpt.isEmpty()) {
                return actions;
            }
            TaskStatus status = taskOpt.get().getStatus();
            boolean single = !selection.multiSelect();

            ContextualActionDescriptor pause = descriptor(ContextualActionIds.SESSION_PAUSE,
                    "Pause", ContextualActionDescriptor.GROUP_LIFECYCLE, "task");
            actions.add(single && status.isActive() ? ContextualAction.enabled(pause)
                    : ContextualAction.disabled(pause, single
                            ? "Task is not active" : "Not available for multiple selection"));

            ContextualActionDescriptor resume = descriptor(ContextualActionIds.SESSION_RESUME,
                    "Resume", ContextualActionDescriptor.GROUP_LIFECYCLE, "task");
            actions.add(single && status == TaskStatus.PAUSED
                    ? ContextualAction.enabled(resume)
                    : ContextualAction.disabled(resume, single
                            ? "Task is not paused" : "Not available for multiple selection"));

            ContextualActionDescriptor cancel = descriptor(ContextualActionIds.SESSION_CANCEL,
                    "Cancel", ContextualActionDescriptor.GROUP_LIFECYCLE, "task");
            actions.add(single && !status.isTerminal()
                    ? ContextualAction.enabled(cancel)
                    : ContextualAction.disabled(cancel, single
                            ? "Task already finished" : "Not available for multiple "
                                    + "selection"));

            ContextualActionDescriptor archive = descriptor(
                    ContextualActionIds.SESSION_ARCHIVE, "Archive",
                    ContextualActionDescriptor.GROUP_LIFECYCLE, "task");
            actions.add(single && status.isTerminal() && status != TaskStatus.ARCHIVED
                    ? ContextualAction.enabled(archive)
                    : ContextualAction.disabled(archive, single
                            ? "Task is not finished or already archived"
                            : "Not available for multiple selection"));

            ContextualActionDescriptor delete = descriptor(ContextualActionIds.SESSION_DELETE,
                    "Delete", ContextualActionDescriptor.GROUP_DANGER, "task")
                    .withConfirmation(Confirmation.DANGEROUS);
            actions.add(single ? ContextualAction.enabled(delete)
                    : ContextualAction.disabled(delete, "Not available for multiple "
                            + "selection"));

            ContextualActionDescriptor moveUp = descriptor(ContextualActionIds.TASK_MOVE_UP,
                    "Move Up", ContextualActionDescriptor.GROUP_SELECTION, "task");
            ContextualActionDescriptor moveDown = descriptor(
                    ContextualActionIds.TASK_MOVE_DOWN, "Move Down",
                    ContextualActionDescriptor.GROUP_SELECTION, "task");
            actions.add(single ? ContextualAction.enabled(moveUp)
                    : ContextualAction.disabled(moveUp, "Not available for multiple "
                            + "selection"));
            actions.add(single ? ContextualAction.enabled(moveDown)
                    : ContextualAction.disabled(moveDown, "Not available for multiple "
                            + "selection"));

            ContextualActionDescriptor details = descriptor(
                    ContextualActionIds.SESSION_DETAILS, "View Execution Details",
                    ContextualActionDescriptor.GROUP_INSPECTION, "task");
            actions.add(single ? ContextualAction.enabled(details)
                    : ContextualAction.disabled(details, "Not available for multiple "
                            + "selection"));
            return actions;
        }
    }

    private static final class SessionContextProvider
            implements ContextualActionProvider {

        private final SessionRepository sessionRepository;

        SessionContextProvider(SessionRepository sessionRepository) {
            this.sessionRepository = sessionRepository;
        }

        @Override
        public List<String> contextTypes() {
            return List.of("session");
        }

        @Override
        public List<ContextualAction> actionsFor(ContextualSelection selection) {
            List<ContextualAction> actions = new ArrayList<>();
            Optional<Session> sessionOpt = sessionRepository.findById(selection.objectId());
            if (sessionOpt.isEmpty()) {
                return actions;
            }
            SessionStatus status = sessionOpt.get().getSessionStatus();
            boolean single = !selection.multiSelect();

            ContextualActionDescriptor pause = descriptor(ContextualActionIds.SESSION_PAUSE,
                    "Pause", ContextualActionDescriptor.GROUP_LIFECYCLE, "session");
            actions.add(single && status == SessionStatus.ACTIVE
                    ? ContextualAction.enabled(pause)
                    : ContextualAction.disabled(pause, single
                            ? "Session is not active" : "Not available for multiple "
                                    + "selection"));

            ContextualActionDescriptor resume = descriptor(
                    ContextualActionIds.SESSION_RESUME, "Resume",
                    ContextualActionDescriptor.GROUP_LIFECYCLE, "session");
            actions.add(single && status == SessionStatus.PAUSED
                    ? ContextualAction.enabled(resume)
                    : ContextualAction.disabled(resume, single
                            ? "Session is not paused" : "Not available for multiple "
                                    + "selection"));

            ContextualActionDescriptor details = descriptor(
                    ContextualActionIds.SESSION_DETAILS, "View Session Details",
                    ContextualActionDescriptor.GROUP_INSPECTION, "session");
            actions.add(single ? ContextualAction.enabled(details)
                    : ContextualAction.disabled(details, "Not available for multiple "
                            + "selection"));
            return actions;
        }
    }

    private static final class ProjectContextProvider
            implements ContextualActionProvider {

        private final ProjectRepository projectRepository;

        ProjectContextProvider(ProjectRepository projectRepository) {
            this.projectRepository = projectRepository;
        }

        @Override
        public List<String> contextTypes() {
            return List.of("project");
        }

        @Override
        public List<ContextualAction> actionsFor(ContextualSelection selection) {
            List<ContextualAction> actions = new ArrayList<>();
            Optional<Project> projectOpt = projectRepository.findById(selection.objectId());
            if (projectOpt.isEmpty()) {
                return actions;
            }
            Project project = projectOpt.get();
            boolean single = !selection.multiSelect();

            ContextualActionDescriptor close = descriptor(ContextualActionIds.PROJECT_CLOSE,
                    "Close Project", ContextualActionDescriptor.GROUP_DANGER, "project")
                    .withConfirmation(Confirmation.CONFIRM);
            boolean closed = project.getStatus() != null
                    && "CLOSED".equals(project.getStatus().name());
            actions.add(single && !closed ? ContextualAction.enabled(close)
                    : ContextualAction.disabled(close, single ? "Project is already closed"
                            : "Not available for multiple selection"));

            // Build/test/analyze belong to other family products; never shown here.
            return actions;
        }
    }

    private static ContextualActionDescriptor descriptor(String actionId, String label,
            String group, String... contextTypes) {
        return ContextualActionDescriptor.of(actionId, label, group, contextTypes);
    }
}
