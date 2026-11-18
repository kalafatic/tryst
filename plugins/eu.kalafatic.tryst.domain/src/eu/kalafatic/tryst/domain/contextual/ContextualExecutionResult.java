package eu.kalafatic.tryst.domain.contextual;

import java.util.List;
import java.util.Optional;

/**
 * Structured execution outcome (Darwin Family Contextual Action Contract v1.0, §4).
 */
public record ContextualExecutionResult(String actionId, boolean ok, String message,
        String errorCode) {

    public static final String ERROR_TARGET_NOT_FOUND = "TARGET_NOT_FOUND";
    public static final String ERROR_ACTION_DISABLED = "ACTION_DISABLED";
    public static final String ERROR_PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String ERROR_NO_HANDLER = "NO_HANDLER";
    public static final String ERROR_EXECUTION_FAILED = "EXECUTION_FAILED";

    public static ContextualExecutionResult ok(String actionId, String message) {
        return new ContextualExecutionResult(actionId, true,
                message == null ? "" : message, "");
    }

    public static ContextualExecutionResult failure(String actionId, String code,
            String message) {
        return new ContextualExecutionResult(actionId, false,
                message == null ? code : message,
                code == null ? ERROR_EXECUTION_FAILED : code);
    }
}
