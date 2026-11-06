package eu.kalafatic.tryst.evo.integration.mcp;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.evo.integration.client.TeipClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standard Model Context Protocol (MCP) server dispatcher over JSON-RPC 2.0.
 * Handles tools/list and tools/call requests according to standard MCP spec.
 */
public class McpServer {

    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();
    private final PermissionManager permissionManager;

    public McpServer(PermissionManager permissionManager) {
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
    }

    public void registerTool(McpTool tool) {
        if (tool != null) {
            tools.put(tool.getName(), tool);
        }
    }

    public Collection<McpTool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public String handleJsonRpcRequest(String jsonBody, String callerUserId, String callerProjectId, String callerTaskId, Set<String> grantedScopes) {
        String id = extractJsonId(jsonBody);
        String method = TeipClient.extractJsonString(jsonBody, "method");

        if (method == null) {
            return errorResponse(id, -32600, "Invalid Request: missing method");
        }

        switch (method) {
            case "tools/list" -> {
                return handleToolsList(id);
            }
            case "tools/call" -> {
                return handleToolsCall(id, jsonBody, callerUserId, callerProjectId, callerTaskId, grantedScopes);
            }
            default -> {
                return errorResponse(id, -32601, "Method not found: " + method);
            }
        }
    }

    private String handleToolsList(String id) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id).append(",\"result\":{\"tools\":[");
        int count = 0;
        for (McpTool t : tools.values()) {
            if (count++ > 0) sb.append(",");
            sb.append("{\"name\":\"").append(TeipClient.escapeJson(t.getName())).append("\",");
            sb.append("\"description\":\"").append(TeipClient.escapeJson(t.getDescription())).append("\",");
            sb.append("\"inputSchema\":").append(t.getInputSchemaJson()).append("}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    private String handleToolsCall(String id, String jsonBody, String callerUserId, String callerProjectId, String callerTaskId, Set<String> grantedScopes) {
        String toolName = extractToolNameFromParams(jsonBody);
        if (toolName == null || !tools.containsKey(toolName)) {
            return errorResponse(id, -32602, "Tool not found: " + toolName);
        }

        McpTool tool = tools.get(toolName);
        // Verify tool scope
        String requiredScope = tool.getRequiredScope();
        if (requiredScope != null && !requiredScope.isBlank() && grantedScopes != null && !grantedScopes.contains(requiredScope)) {
            return errorResponse(id, -32001, "Permission denied: caller lacks required scope '" + requiredScope + "'");
        }

        Map<String, String> arguments = extractArgumentsFromParams(jsonBody);

        try {
            McpTool.McpToolResult result = tool.execute(arguments, callerUserId, callerProjectId, callerTaskId);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id).append(",\"result\":{");
            sb.append("\"content\":[{\"type\":\"text\",\"text\":\"").append(TeipClient.escapeJson(result.output())).append("\"}],");
            sb.append("\"isError\":").append(result.isError());
            sb.append("}}");
            return sb.toString();
        } catch (Exception e) {
            TrystLog.error(McpServer.class, "Tool execution error: " + e.getMessage(), e);
            return errorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private String errorResponse(String id, int code, String message) {
        return String.format("{\"jsonrpc\":\"2.0\",\"id\":%s,\"error\":{\"code\":%d,\"message\":\"%s\"}}",
                id != null ? id : "null", code, TeipClient.escapeJson(message));
    }

    private String extractJsonId(String json) {
        int idx = json.indexOf("\"id\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 4);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        if (json.charAt(start) == '"') {
            end = json.indexOf('"', start + 1);
            return end > 0 ? json.substring(start, end + 1) : null;
        }
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return start < end ? json.substring(start, end) : null;
    }

    private String extractToolNameFromParams(String json) {
        int paramsIdx = json.indexOf("\"params\"");
        if (paramsIdx < 0) return null;
        String sub = json.substring(paramsIdx);
        return TeipClient.extractJsonString(sub, "name");
    }

    private Map<String, String> extractArgumentsFromParams(String json) {
        Map<String, String> map = new HashMap<>();
        int argsIdx = json.indexOf("\"arguments\"");
        if (argsIdx < 0) return map;
        int open = json.indexOf('{', argsIdx);
        if (open < 0) return map;
        int close = json.indexOf('}', open);
        if (close < 0) return map;
        String sub = json.substring(open + 1, close);
        String[] tokens = sub.split(",");
        for (String tok : tokens) {
            int colon = tok.indexOf(':');
            if (colon > 0) {
                String k = tok.substring(0, colon).trim().replace("\"", "");
                String v = tok.substring(colon + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                map.put(k, v);
            }
        }
        return map;
    }
}
