package eu.kalafatic.tryst.domain.tool;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.util.Map;
import java.util.Objects;

/**
 * Standard contract for tools executable by TRYST agents.
 */
public interface Tool {

    record ToolResult(boolean success, String output, String error) {
        public static ToolResult ok(String output) {
            return new ToolResult(true, output != null ? output : "", "");
        }

        public static ToolResult fail(String error) {
            return new ToolResult(false, "", error != null ? error : "Unknown error");
        }
    }

    String getName();

    String getDescription();

    PermissionManager.PermissionLevel getRequiredPermission();

    ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager);
}
