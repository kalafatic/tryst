package eu.kalafatic.tryst.evo.integration.client;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Pure Java 21 HTTP Client implementing normative TEIP 1.0 client specification.
 * Connects to EVO endpoints:
 * - GET  /teip/v1/health
 * - GET  /teip/v1/capabilities
 * - POST /teip/v1/executions
 * - GET  /teip/v1/executions/{id}
 * - GET  /teip/v1/executions/{id}/events
 * - GET  /teip/v1/executions/{id}/result
 * - POST /teip/v1/executions/{id}/cancel
 */
public class TeipClient {

    private final HttpClient httpClient;
    private final Duration timeout;

    public TeipClient(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    public TeipClient() {
        this(Duration.ofSeconds(60));
    }

    public TeipHealthResponse checkHealth(String endpoint, String authToken) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        HttpRequest req = createRequestBuilder(base + "/teip/v1/health", authToken)
                .GET()
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseHealthResponse(resp.body());
        }
        throw new TrystInfrastructureException("EVO health check failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipCapabilitiesResponse getCapabilities(String endpoint, String authToken) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        HttpRequest req = createRequestBuilder(base + "/teip/v1/capabilities", authToken)
                .GET()
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseCapabilitiesResponse(resp.body());
        }
        throw new TrystInfrastructureException("EVO capabilities retrieval failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipExecutionResponse createExecution(String endpoint, String authToken, TeipExecutionRequest request) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        String jsonBody = serializeExecutionRequest(request);

        HttpRequest.Builder b = createRequestBuilder(base + "/teip/v1/executions", authToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            b.header("Idempotency-Key", request.idempotencyKey());
        }

        HttpResponse<String> resp = sendWithRetry(b.build());
        if (resp.statusCode() == 200 || resp.statusCode() == 201) {
            return parseExecutionResponse(resp.body());
        }
        throw new TrystInfrastructureException("EVO execution creation failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipExecutionStatus getExecutionStatus(String endpoint, String authToken, String executionId) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        HttpRequest req = createRequestBuilder(base + "/teip/v1/executions/" + executionId, authToken)
                .GET()
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseExecutionStatus(resp.body());
        }
        throw new TrystInfrastructureException("EVO execution status retrieval failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipEventsResponse getExecutionEvents(String endpoint, String authToken, String executionId, long afterSequence, int limit) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        String url = base + "/teip/v1/executions/" + executionId + "/events?afterSequence=" + afterSequence + "&limit=" + (limit > 0 ? limit : 100);
        HttpRequest req = createRequestBuilder(url, authToken)
                .GET()
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseEventsResponse(resp.body(), executionId);
        }
        throw new TrystInfrastructureException("EVO execution events retrieval failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipExecutionResult getExecutionResult(String endpoint, String authToken, String executionId) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        HttpRequest req = createRequestBuilder(base + "/teip/v1/executions/" + executionId + "/result", authToken)
                .GET()
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseExecutionResult(resp.body());
        }
        throw new TrystInfrastructureException("EVO execution result retrieval failed with status " + resp.statusCode() + ": " + resp.body());
    }

    public TeipCancelResponse cancelExecution(String endpoint, String authToken, String executionId) throws IOException, InterruptedException {
        String base = normalizeEndpoint(endpoint);
        HttpRequest req = createRequestBuilder(base + "/teip/v1/executions/" + executionId + "/cancel", authToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> resp = sendWithRetry(req);
        if (resp.statusCode() == 200) {
            return parseCancelResponse(resp.body());
        }
        throw new TrystInfrastructureException("EVO execution cancellation failed with status " + resp.statusCode() + ": " + resp.body());
    }

    private HttpRequest.Builder createRequestBuilder(String url, String authToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout);
        if (authToken != null && !authToken.isBlank()) {
            b.header("Authorization", "Bearer " + authToken.trim());
        }
        return b;
    }

    private HttpResponse<String> sendWithRetry(HttpRequest req) throws IOException, InterruptedException {
        int maxAttempts = 3;
        int attempt = 0;
        long delayMs = 300;

        while (true) {
            attempt++;
            try {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if ((sc == 503 || sc == 504) && attempt < maxAttempts) {
                    Thread.sleep(delayMs);
                    delayMs *= 2;
                    continue;
                }
                return resp;
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                Thread.sleep(delayMs);
                delayMs *= 2;
            }
        }
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:39080";
        }
        String ep = endpoint.trim();
        while (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        return ep;
    }

    // JSON Parsing and Serialization Helpers
    public static String extractJsonString(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static long extractJsonLong(String json, String field, long defaultValue) {
        if (json == null) return defaultValue;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return defaultValue;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (start < end) {
            try {
                return Long.parseLong(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public static double extractJsonDouble(String json, String field, double defaultValue) {
        if (json == null) return defaultValue;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return defaultValue;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        if (start < end) {
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public static List<String> extractJsonStringList(String json, String field) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return list;
        int arrayStart = json.indexOf('[', idx + key.length());
        if (arrayStart < 0) return list;
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayEnd < 0) return list;
        String sub = json.substring(arrayStart + 1, arrayEnd);
        boolean inStr = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sub.length(); i++) {
            char c = sub.charAt(i);
            if (c == '"') {
                if (inStr) {
                    list.add(sb.toString());
                    sb.setLength(0);
                    inStr = false;
                } else {
                    inStr = true;
                }
            } else if (inStr) {
                sb.append(c);
            }
        }
        return list;
    }

    private TeipHealthResponse parseHealthResponse(String json) {
        return new TeipHealthResponse(
                extractJsonString(json, "status"),
                extractJsonString(json, "protocol"),
                extractJsonString(json, "version"),
                extractJsonString(json, "engineVersion"),
                extractJsonString(json, "nodeId"),
                extractJsonLong(json, "uptimeSeconds", 0),
                extractJsonString(json, "timestamp")
        );
    }

    private TeipCapabilitiesResponse parseCapabilitiesResponse(String json) {
        List<TeipCapability> caps = new ArrayList<>();
        List<TeipModel> models = new ArrayList<>();

        int capsIdx = json.indexOf("\"capabilities\"");
        if (capsIdx >= 0) {
            int arrStart = json.indexOf('[', capsIdx);
            int arrEnd = findMatchingBracket(json, arrStart);
            if (arrStart >= 0 && arrEnd > arrStart) {
                String sub = json.substring(arrStart + 1, arrEnd);
                for (String item : splitJsonObjects(sub)) {
                    caps.add(new TeipCapability(
                            extractJsonString(item, "id"),
                            extractJsonString(item, "name"),
                            extractJsonStringList(item, "supportedModes"),
                            extractJsonStringList(item, "requiredScopes")
                    ));
                }
            }
        }

        int modelsIdx = json.indexOf("\"availableModels\"");
        if (modelsIdx >= 0) {
            int arrStart = json.indexOf('[', modelsIdx);
            int arrEnd = findMatchingBracket(json, arrStart);
            if (arrStart >= 0 && arrEnd > arrStart) {
                String sub = json.substring(arrStart + 1, arrEnd);
                for (String item : splitJsonObjects(sub)) {
                    boolean rec = item.contains("\"recommended\":true") || item.contains("\"recommended\": true");
                    models.add(new TeipModel(
                            extractJsonString(item, "id"),
                            extractJsonString(item, "name"),
                            extractJsonLong(item, "contextWindowTokens", 0),
                            rec
                    ));
                }
            }
        }

        return new TeipCapabilitiesResponse(caps, models);
    }

    private TeipExecutionResponse parseExecutionResponse(String json) {
        return new TeipExecutionResponse(
                extractJsonString(json, "executionId"),
                extractJsonString(json, "evoSessionId"),
                extractJsonString(json, "status"),
                extractJsonString(json, "createdAt")
        );
    }

    private TeipExecutionStatus parseExecutionStatus(String json) {
        return new TeipExecutionStatus(
                extractJsonString(json, "executionId"),
                extractJsonString(json, "status"),
                extractJsonString(json, "phase"),
                extractJsonDouble(json, "progress", 0.0),
                extractJsonLong(json, "lastSequence", 0),
                extractJsonString(json, "createdAt"),
                extractJsonString(json, "updatedAt")
        );
    }

    private TeipEventsResponse parseEventsResponse(String json, String execId) {
        List<TeipEvent> events = new ArrayList<>();
        int eventsIdx = json.indexOf("\"events\"");
        if (eventsIdx >= 0) {
            int arrStart = json.indexOf('[', eventsIdx);
            int arrEnd = findMatchingBracket(json, arrStart);
            if (arrStart >= 0 && arrEnd > arrStart) {
                String sub = json.substring(arrStart + 1, arrEnd);
                for (String item : splitJsonObjects(sub)) {
                    String dataJson = extractJsonObjectField(item, "data");
                    events.add(new TeipEvent(
                            extractJsonString(item, "eventId"),
                            extractJsonString(item, "executionId"),
                            extractJsonLong(item, "sequence", 0),
                            extractJsonString(item, "type"),
                            extractJsonString(item, "timestamp"),
                            extractJsonString(item, "correlationId"),
                            dataJson != null ? dataJson : "{}"
                    ));
                }
            }
        }
        boolean hasMore = json.contains("\"hasMore\":true") || json.contains("\"hasMore\": true");
        long latestSeq = extractJsonLong(json, "latestSequence", 0);
        return new TeipEventsResponse(execId, events, hasMore, latestSeq);
    }

    private TeipExecutionResult parseExecutionResult(String json) {
        String execId = extractJsonString(json, "executionId");
        String status = extractJsonString(json, "status");
        String summary = extractJsonString(json, "summary");
        String patch = extractJsonString(json, "patch");
        List<String> modified = extractJsonStringList(json, "modifiedFiles");
        String completedAt = extractJsonString(json, "completedAt");

        TeipExecutionMetrics metrics = new TeipExecutionMetrics(
                extractJsonLong(json, "durationMs", 0),
                extractJsonLong(json, "promptTokens", 0),
                extractJsonLong(json, "completionTokens", 0),
                (int) extractJsonLong(json, "toolCallsCount", 0)
        );

        return new TeipExecutionResult(execId, status, summary, patch, modified, metrics, completedAt);
    }

    private TeipCancelResponse parseCancelResponse(String json) {
        return new TeipCancelResponse(
                extractJsonString(json, "executionId"),
                extractJsonString(json, "status"),
                extractJsonString(json, "message")
        );
    }

    public static String serializeExecutionRequest(TeipExecutionRequest req) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"protocol\":\"").append(escapeJson(req.protocol())).append("\",");
        sb.append("\"version\":\"").append(escapeJson(req.version())).append("\",");
        sb.append("\"requestId\":\"").append(escapeJson(req.requestId())).append("\",");
        sb.append("\"correlationId\":\"").append(escapeJson(req.correlationId())).append("\",");
        if (req.idempotencyKey() != null) {
            sb.append("\"idempotencyKey\":\"").append(escapeJson(req.idempotencyKey())).append("\",");
        }
        sb.append("\"timestamp\":\"").append(escapeJson(req.timestamp())).append("\",");
        sb.append("\"caller\":{");
        sb.append("\"system\":\"").append(escapeJson(req.caller().system())).append("\",");
        sb.append("\"projectId\":\"").append(escapeJson(req.caller().projectId())).append("\",");
        sb.append("\"taskId\":\"").append(escapeJson(req.caller().taskId())).append("\"");
        sb.append("},");
        sb.append("\"capability\":\"").append(escapeJson(req.capability())).append("\",");
        sb.append("\"aiMode\":\"").append(escapeJson(req.aiMode())).append("\",");
        if (req.model() != null) {
            sb.append("\"model\":\"").append(escapeJson(req.model())).append("\",");
        }
        sb.append("\"instruction\":\"").append(escapeJson(req.instruction())).append("\",");

        // contextReferences
        sb.append("\"contextReferences\":[");
        if (req.contextReferences() != null) {
            for (int i = 0; i < req.contextReferences().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(req.contextReferences().get(i))).append("\"");
            }
        }
        sb.append("],");

        // mcpToolScopes
        sb.append("\"mcpToolScopes\":[");
        if (req.mcpToolScopes() != null) {
            for (int i = 0; i < req.mcpToolScopes().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(req.mcpToolScopes().get(i))).append("\"");
            }
        }
        sb.append("]");

        if (req.parameters() != null && !req.parameters().isEmpty()) {
            sb.append(",\"parameters\":{");
            int pi = 0;
            for (Map.Entry<String, Object> entry : req.parameters().entrySet()) {
                if (pi++ > 0) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                if (entry.getValue() instanceof Number || entry.getValue() instanceof Boolean) {
                    sb.append(entry.getValue());
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
                }
            }
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static int findMatchingBracket(String s, int openIdx) {
        if (openIdx < 0) return -1;
        char open = s.charAt(openIdx);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;

        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                inStr = !inStr;
            } else if (!inStr) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitJsonObjects(String arrayBody) {
        List<String> list = new ArrayList<>();
        int i = 0;
        while (i < arrayBody.length()) {
            int open = arrayBody.indexOf('{', i);
            if (open < 0) break;
            int close = findMatchingBracket(arrayBody, open);
            if (close < 0) break;
            list.add(arrayBody.substring(open, close + 1));
            i = close + 1;
        }
        return list;
    }

    private static String extractJsonObjectField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int open = json.indexOf('{', colon);
        if (open < 0) return null;
        int close = findMatchingBracket(json, open);
        if (close < 0) return null;
        return json.substring(open, close + 1);
    }
}
