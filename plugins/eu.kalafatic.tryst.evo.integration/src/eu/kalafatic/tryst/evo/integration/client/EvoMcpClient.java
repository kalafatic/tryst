package eu.kalafatic.tryst.evo.integration.client;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client for EVO Cognitive Services (port 28280, JSON-RPC 2.0 over
 * HTTP POST /mcp, protocol 2024-11-05).
 *
 * <p>Wire-only integration: TRYST never imports EVO classes. Every call
 * carries the EVO-issued bearer token; identity parameters are sent as
 * hints and EVO validates them against the token. When EVO is unavailable
 * every method degrades gracefully (returns null/false); the existing
 * local pipeline remains unchanged.</p>
 *
 * <p>JSON handling follows the TeipClient convention: hand-rolled
 * serialization and minimal extraction helpers, no external JSON
 * dependency.</p>
 */
public class EvoMcpClient {

    private final HttpClient httpClient;
    private final Duration timeout;
    private final AtomicLong requestIds = new AtomicLong(0);

    public EvoMcpClient() {
        this(Duration.ofSeconds(30));
    }

    public EvoMcpClient(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /** Result of a context_get call: the raw package text and its token estimate. */
    public record ContextResult(String packageJson, int tokenEstimate) {}

    /** POSTs one JSON-RPC 2.0 tools/call request to the EVO MCP endpoint. */
    public String callTool(String endpoint, String authToken, String toolName,
                           Map<String, Object> arguments) throws IOException, InterruptedException {
        String request = buildToolCallJson(toolName, arguments);
        String base = normalizeEndpoint(endpoint);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(request));

        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("EVO MCP HTTP " + response.statusCode() + ": " + response.body());
        }
        if (response.body().contains("\"error\"")) {
            throw new IOException("EVO MCP error response: " + truncate(response.body(), 400));
        }
        return response.body();
    }

    /**
     * Reconstructs cognitive context from EVO. Returns null when EVO is
     * unreachable so callers degrade gracefully.
     */
    public ContextResult contextGet(String endpoint, String authToken, String project,
                                    String sessionId, String intent, int budgetTokens,
                                    List<String> categories) {
        try {
            StringBuilder args = new StringBuilder("{");
            if (project != null) args.append("\"project\":\"").append(escapeJson(project)).append("\",");
            if (sessionId != null) args.append("\"sessionId\":\"").append(escapeJson(sessionId)).append("\",");
            if (intent != null) args.append("\"intent\":\"").append(escapeJson(intent)).append("\",");
            if (budgetTokens > 0) args.append("\"budgetTokens\":").append(budgetTokens).append(",");
            if (categories != null && !categories.isEmpty()) {
                args.append("\"categories\":[");
                for (int i = 0; i < categories.size(); i++) {
                    if (i > 0) args.append(",");
                    args.append("\"").append(escapeJson(categories.get(i))).append("\"");
                }
                args.append("],");
            }
            if (args.charAt(args.length() - 1) == ',') {
                args.deleteCharAt(args.length() - 1);
            }
            args.append("}");
            String response = callTool(endpoint, authToken, "context_get",
                    Map.of("__rawArguments", args.toString()));
            int tokens = extractJsonInt(response, "tokenEstimate", 0);
            return new ContextResult(response, tokens);
        } catch (Exception e) {
            TrystLog.warn(EvoMcpClient.class, "context_get failed (degrading): " + e.getMessage());
            return null;
        }
    }

    /** Stores one memory record in EVO; false on failure. */
    public boolean memoryStore(String endpoint, String authToken, String content, String type,
                               String scope, String project, double confidence) {
        try {
            String response = callTool(endpoint, authToken, "memory_store", Map.of(
                    "content", content,
                    "type", type != null ? type : "EPISODIC",
                    "scope", scope != null ? scope : "PROJECT",
                    "project", project != null ? project : "",
                    "confidence", confidence,
                    "source", "TOOL"));
            return response.contains("\"entryId\"");
        } catch (Exception e) {
            TrystLog.warn(EvoMcpClient.class, "memory_store failed (degrading): " + e.getMessage());
            return false;
        }
    }

    /** Registers a correlated cognitive session in EVO; null on failure. */
    public String sessionCreate(String endpoint, String authToken, String kind, String project,
                                String correlationId) {
        try {
            String response = callTool(endpoint, authToken, "session_create", Map.of(
                    "kind", kind != null ? kind : "TASK",
                    "project", project != null ? project : "",
                    "correlationId", correlationId != null ? correlationId : ""));
            return response.contains("\"sessionId\"")
                    ? extractJsonString(response, "sessionId") : null;
        } catch (Exception e) {
            TrystLog.warn(EvoMcpClient.class, "session_create failed (degrading): " + e.getMessage());
            return null;
        }
    }

    /** Records a context snapshot reference in EVO; false on failure. */
    public boolean contextRecord(String endpoint, String authToken, String sessionId, String summary) {
        try {
            String response = callTool(endpoint, authToken, "context_record", Map.of(
                    "sessionId", sessionId != null ? sessionId : "",
                    "summary", summary != null ? summary : ""));
            return response.contains("\"contextId\"");
        } catch (Exception e) {
            TrystLog.warn(EvoMcpClient.class, "context_record failed (degrading): " + e.getMessage());
            return false;
        }
    }

    /**
     * End-to-end recording of meaningful work results: episode memory +
     * context snapshot. Never throws; local pipeline continues on failure.
     */
    public void recordOutcome(String endpoint, String authToken, String sessionId,
                              String project, boolean success, String summary) {
        try {
            String cognitiveSessionId = sessionId;
            if (cognitiveSessionId == null) {
                cognitiveSessionId = sessionCreate(endpoint, authToken, "TASK", project, null);
            }
            memoryStore(endpoint, authToken,
                    "Task outcome (" + (success ? "SUCCESS" : "FAILURE") + "): " + summary,
                    "EPISODIC", "PROJECT", project, success ? 0.9 : 0.7);
            contextRecord(endpoint, authToken, cognitiveSessionId, summary);
        } catch (Exception e) {
            TrystLog.warn(EvoMcpClient.class, "recordOutcome failed (degrading): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC serialization helpers (hand-rolled, TeipClient convention)
    // ------------------------------------------------------------------

    private String buildToolCallJson(String toolName, Map<String, Object> arguments) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"jsonrpc\":\"2.0\",");
        sb.append("\"id\":").append(requestIds.incrementAndGet()).append(",");
        sb.append("\"method\":\"tools/call\",");
        sb.append("\"params\":{\"name\":\"").append(escapeJson(toolName)).append("\",\"arguments\":");
        if (arguments.containsKey("__rawArguments")) {
            sb.append(arguments.get("__rawArguments"));
        } else {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                Object value = entry.getValue();
                if (value instanceof Number || value instanceof Boolean) {
                    sb.append(value);
                } else if (value instanceof Map) {
                    sb.append("{}");
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
                }
            }
            sb.append("}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String extractJsonString(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            end++;
        }
        return end > start ? json.substring(start + 1, end) : null;
    }

    private static int extractJsonInt(String json, String field, int defaultValue) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return defaultValue;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\n')) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end > start) {
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("EVO MCP endpoint is required");
        }
        String base = endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
