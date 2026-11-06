package eu.kalafatic.tryst.evo.integration.mcp;

import java.util.Map;

/**
 * Standard contract for MCP tools exposed by TRYST to EVO.
 */
public interface McpTool {

    record McpToolResult(boolean isError, String output) {
        public static McpToolResult success(String output) {
            return new McpToolResult(false, output != null ? output : "");
        }

        public static McpToolResult error(String error) {
            return new McpToolResult(true, error != null ? error : "Unknown error");
        }
    }

    String getName();

    String getDescription();

    String getInputSchemaJson();

    String getRequiredScope();

    McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId);
}
