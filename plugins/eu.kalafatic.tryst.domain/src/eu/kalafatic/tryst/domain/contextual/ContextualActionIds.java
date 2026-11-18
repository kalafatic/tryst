package eu.kalafatic.tryst.domain.contextual;

/**
 * Canonical contextual action ids — Darwin Family Contextual Action Contract v1.0
 * (owned by DStudio; TRYST implements the task/workflow/repository/session subset
 * locally). TRYST owns task/workflow/repository/sandbox/session lifecycle.
 */
public final class ContextualActionIds {

    public static final String CONTRACT_VERSION = "1.0";

    // session / task / execution
    public static final String SESSION_OPEN = "eu.kalafatic.contextual.session.open";
    public static final String SESSION_SELECT = "eu.kalafatic.contextual.session.select";
    public static final String SESSION_DUPLICATE = "eu.kalafatic.contextual.session.duplicate";
    public static final String SESSION_RESUME = "eu.kalafatic.contextual.session.resume";
    public static final String SESSION_PAUSE = "eu.kalafatic.contextual.session.pause";
    public static final String SESSION_CANCEL = "eu.kalafatic.contextual.session.cancel";
    public static final String SESSION_ARCHIVE = "eu.kalafatic.contextual.session.archive";
    public static final String SESSION_DELETE = "eu.kalafatic.contextual.session.delete";
    public static final String SESSION_HISTORY = "eu.kalafatic.contextual.session.history";
    public static final String SESSION_DETAILS = "eu.kalafatic.contextual.session.details";
    public static final String SESSION_ARTIFACTS = "eu.kalafatic.contextual.session.artifacts";

    // task ordering (TRYST-specific verbs under the same catalog prefix)
    public static final String TASK_MOVE_UP = "eu.kalafatic.contextual.task.move-up";
    public static final String TASK_MOVE_DOWN = "eu.kalafatic.contextual.task.move-down";

    // project / repository
    public static final String PROJECT_OPEN = "eu.kalafatic.contextual.project.open";
    public static final String PROJECT_SELECT = "eu.kalafatic.contextual.project.select";
    public static final String PROJECT_CLOSE = "eu.kalafatic.contextual.project.close";
    public static final String PROJECT_REFRESH = "eu.kalafatic.contextual.project.refresh";
    public static final String PROJECT_GIT_STATUS = "eu.kalafatic.contextual.project.git-status";
    public static final String PROJECT_COMMIT = "eu.kalafatic.contextual.project.commit";
    public static final String PROJECT_PUSH = "eu.kalafatic.contextual.project.push";
    public static final String PROJECT_DIAGNOSTICS = "eu.kalafatic.contextual.project.diagnostics";

    private ContextualActionIds() {
    }
}
