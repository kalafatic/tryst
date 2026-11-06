package eu.kalafatic.tryst.evo.integration.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.EvoExecutionEvent;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.evo.integration.client.TeipClient;
import eu.kalafatic.tryst.evo.integration.mcp.McpServer;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HTTP Handlers exposing /api/integration/evo/* endpoints and /api/mcp endpoint.
 */
public final class EvoHttpHandlers {

    private EvoHttpHandlers() {}

    public static class EvoIntegrationHandler implements HttpHandler {
        private final EvoIntegrationService evoIntegrationService;
        private final PermissionManager permissionManager;

        public EvoIntegrationHandler(EvoIntegrationService evoIntegrationService, PermissionManager permissionManager) {
            this.evoIntegrationService = evoIntegrationService;
            this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // Handle context path prefix e.g. /tryst/api/integration/evo -> /api/integration/evo
            if (path.contains("/api/integration/evo")) {
                path = path.substring(path.indexOf("/api/integration/evo"));
            }
            String method = exchange.getRequestMethod().toUpperCase();

            // Set CORS & JSON headers
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                if ("/api/integration/evo/config".equals(path)) {
                    if ("GET".equals(method)) {
                        handleGetConfig(exchange);
                    } else if ("POST".equals(method)) {
                        handleUpdateConfig(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed");
                    }
                    return;
                }

                if ("/api/integration/evo/health".equals(path) && "GET".equals(method)) {
                    handleHealthCheck(exchange);
                    return;
                }

                if ("/api/integration/evo/capabilities".equals(path) && "GET".equals(method)) {
                    handleGetCapabilities(exchange);
                    return;
                }

                if ("/api/integration/evo/executions".equals(path)) {
                    if ("GET".equals(method)) {
                        handleGetExecutions(exchange);
                    } else if ("POST".equals(method)) {
                        handleCreateExecution(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed");
                    }
                    return;
                }

                if (path.startsWith("/api/integration/evo/executions/")) {
                    String sub = path.substring("/api/integration/evo/executions/".length());
                    if (sub.endsWith("/cancel") && "POST".equals(method)) {
                        String execId = sub.substring(0, sub.length() - "/cancel".length());
                        handleCancelExecution(exchange, execId);
                        return;
                    }
                    if (sub.endsWith("/events") && "GET".equals(method)) {
                        String execId = sub.substring(0, sub.length() - "/events".length());
                        handleGetExecutionEvents(exchange, execId);
                        return;
                    }
                    if (sub.endsWith("/result") && "GET".equals(method)) {
                        String execId = sub.substring(0, sub.length() - "/result".length());
                        handleGetExecutionResult(exchange, execId);
                        return;
                    }
                    if ("GET".equals(method)) {
                        handleGetSingleExecution(exchange, sub);
                        return;
                    }
                }

                sendError(exchange, 404, "Not Found");
            } catch (Exception e) {
                TrystLog.error(EvoIntegrationHandler.class, "Error processing EVO API request: " + e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private void handleGetConfig(HttpExchange exchange) throws IOException {
            EvoConfiguration c = evoIntegrationService.getConfiguration();
            String json = String.format("{\"endpoint\":\"%s\",\"enabled\":%b,\"timeoutSeconds\":%d,\"maxRetries\":%d,\"hasAuthToken\":%b}",
                    TeipClient.escapeJson(c.getEndpoint()), c.isEnabled(), c.getTimeoutSeconds(), c.getMaxRetries(),
                    c.getAuthToken() != null && !c.getAuthToken().isBlank());
            sendResponse(exchange, 200, json);
        }

        private void handleUpdateConfig(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String endpoint = TeipClient.extractJsonString(body, "endpoint");
            String authToken = TeipClient.extractJsonString(body, "authToken");
            boolean enabled = body.contains("\"enabled\":true") || body.contains("\"enabled\": true");
            int timeout = (int) TeipClient.extractJsonLong(body, "timeoutSeconds", 60);
            int maxRetries = (int) TeipClient.extractJsonLong(body, "maxRetries", 3);

            evoIntegrationService.updateConfiguration(endpoint, authToken, enabled, timeout, maxRetries);
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"EVO configuration updated.\"}");
        }

        private void handleHealthCheck(HttpExchange exchange) throws IOException {
            try {
                TeipHealthResponse hr = evoIntegrationService.checkHealth();
                String json = String.format("{\"connected\":true,\"status\":\"%s\",\"protocol\":\"%s\",\"version\":\"%s\",\"engineVersion\":\"%s\",\"nodeId\":\"%s\",\"uptimeSeconds\":%d}",
                        hr.status(), hr.protocol(), hr.version(), hr.engineVersion(), hr.nodeId(), hr.uptimeSeconds());
                sendResponse(exchange, 200, json);
            } catch (Exception e) {
                String json = String.format("{\"connected\":false,\"status\":\"DOWN\",\"error\":\"%s\"}", TeipClient.escapeJson(e.getMessage()));
                sendResponse(exchange, 200, json);
            }
        }

        private void handleGetCapabilities(HttpExchange exchange) throws IOException {
            try {
                TeipCapabilitiesResponse resp = evoIntegrationService.getCapabilities();
                StringBuilder sb = new StringBuilder("{\"capabilities\":[");
                for (int i = 0; i < resp.capabilities().size(); i++) {
                    if (i > 0) sb.append(",");
                    var c = resp.capabilities().get(i);
                    sb.append(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"supportedModes\":[%s],\"requiredScopes\":[%s]}",
                            c.id(), c.name(),
                            String.join(",", c.supportedModes().stream().map(m -> "\"" + m + "\"").toList()),
                            String.join(",", c.requiredScopes().stream().map(s -> "\"" + s + "\"").toList())));
                }
                sb.append("],\"availableModels\":[");
                for (int i = 0; i < resp.availableModels().size(); i++) {
                    if (i > 0) sb.append(",");
                    var m = resp.availableModels().get(i);
                    sb.append(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"contextWindowTokens\":%d,\"recommended\":%b}",
                            m.id(), m.name(), m.contextWindowTokens(), m.recommended()));
                }
                sb.append("]}");
                sendResponse(exchange, 200, sb.toString());
            } catch (Exception e) {
                sendError(exchange, 503, "Capabilities unavailable: " + e.getMessage());
            }
        }

        private void handleGetExecutions(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String taskId = null;
            if (query != null && query.contains("taskId=")) {
                for (String p : query.split("&")) {
                    if (p.startsWith("taskId=")) {
                        taskId = p.substring("taskId=".length());
                    }
                }
            }

            List<EvoExecution> list = taskId != null && !taskId.isBlank()
                    ? evoIntegrationService.getExecutionsForTask(taskId)
                    : evoIntegrationService.getAllExecutions();

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                EvoExecution ex = list.get(i);
                sb.append(String.format("{\"id\":\"%s\",\"trystTaskId\":\"%s\",\"evoExecutionId\":\"%s\",\"capability\":\"%s\",\"aiMode\":\"%s\",\"status\":\"%s\",\"phase\":\"%s\",\"progress\":%.2f,\"summary\":\"%s\",\"error\":\"%s\",\"createdAt\":\"%s\"}",
                        ex.getId(), ex.getTrystTaskId(),
                        ex.getEvoExecutionId() != null ? ex.getEvoExecutionId() : "",
                        ex.getCapability(), ex.getAiMode(), ex.getStatus(),
                        TeipClient.escapeJson(ex.getPhase()), ex.getProgress(),
                        TeipClient.escapeJson(ex.getSummary()), TeipClient.escapeJson(ex.getError()),
                        ex.getCreatedAt()));
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleCreateExecution(HttpExchange exchange) throws Exception {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String taskId = TeipClient.extractJsonString(body, "trystTaskId");
            String capability = TeipClient.extractJsonString(body, "capability");
            String aiMode = TeipClient.extractJsonString(body, "aiMode");
            String instruction = TeipClient.extractJsonString(body, "instruction");
            String model = TeipClient.extractJsonString(body, "model");
            String idemKey = TeipClient.extractJsonString(body, "idempotencyKey");

            if (taskId == null || taskId.isBlank()) {
                sendError(exchange, 400, "Missing required parameter: trystTaskId");
                return;
            }

            EvoExecution exec = evoIntegrationService.createExecution(
                    taskId,
                    null,
                    capability != null ? capability : "CODE_IMPLEMENTATION",
                    aiMode != null ? aiMode : "DEFAULT",
                    model,
                    instruction != null ? instruction : "Perform task implementation",
                    List.of(),
                    List.of("read:workspace", "read:task", "write:workspace"),
                    idemKey != null ? idemKey : UUID.randomUUID().toString()
            );

            String json = String.format("{\"id\":\"%s\",\"evoExecutionId\":\"%s\",\"status\":\"%s\"}",
                    exec.getId(), exec.getEvoExecutionId() != null ? exec.getEvoExecutionId() : "", exec.getStatus());
            sendResponse(exchange, 201, json);
        }

        private void handleCancelExecution(HttpExchange exchange, String localId) throws Exception {
            evoIntegrationService.cancelExecution(localId);
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Execution cancelled\"}");
        }

        private void handleGetSingleExecution(HttpExchange exchange, String localId) throws IOException {
            Optional<EvoExecution> opt = evoIntegrationService.getExecution(localId);
            if (opt.isEmpty()) {
                sendError(exchange, 404, "Execution not found");
                return;
            }
            EvoExecution ex = opt.get();
            String json = String.format("{\"id\":\"%s\",\"trystTaskId\":\"%s\",\"evoExecutionId\":\"%s\",\"capability\":\"%s\",\"aiMode\":\"%s\",\"status\":\"%s\",\"phase\":\"%s\",\"progress\":%.2f,\"summary\":\"%s\",\"error\":\"%s\",\"createdAt\":\"%s\"}",
                    ex.getId(), ex.getTrystTaskId(),
                    ex.getEvoExecutionId() != null ? ex.getEvoExecutionId() : "",
                    ex.getCapability(), ex.getAiMode(), ex.getStatus(),
                    TeipClient.escapeJson(ex.getPhase()), ex.getProgress(),
                    TeipClient.escapeJson(ex.getSummary()), TeipClient.escapeJson(ex.getError()),
                    ex.getCreatedAt());
            sendResponse(exchange, 200, json);
        }

        private void handleGetExecutionEvents(HttpExchange exchange, String localId) throws IOException {
            List<EvoExecutionEvent> events = evoIntegrationService.getExecutionEvents(localId, 0, 100);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) sb.append(",");
                var ev = events.get(i);
                sb.append(String.format("{\"eventId\":\"%s\",\"sequence\":%d,\"eventType\":\"%s\",\"timestamp\":\"%s\",\"correlationId\":\"%s\",\"data\":%s}",
                        ev.getEventId(), ev.getSequence(), ev.getEventType(), ev.getTimestamp(), ev.getCorrelationId(), ev.getDataJson()));
            }
            sb.append("]");
            sendResponse(exchange, 200, sb.toString());
        }

        private void handleGetExecutionResult(HttpExchange exchange, String localId) throws Exception {
            TeipExecutionResult res = evoIntegrationService.getRemoteResult(localId);
            String json = String.format("{\"executionId\":\"%s\",\"status\":\"%s\",\"summary\":\"%s\",\"patch\":\"%s\",\"completedAt\":\"%s\"}",
                    res.executionId(), res.status(), TeipClient.escapeJson(res.summary()), TeipClient.escapeJson(res.patch()), res.completedAt());
            sendResponse(exchange, 200, json);
        }

        private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void sendError(HttpExchange exchange, int status, String message) throws IOException {
            sendResponse(exchange, status, String.format("{\"error\":{\"code\":\"ERROR\",\"message\":\"%s\"}}", TeipClient.escapeJson(message)));
        }
    }

    public static class McpHttpHandler implements HttpHandler {
        private final McpServer mcpServer;

        public McpHttpHandler(McpServer mcpServer) {
            this.mcpServer = Objects.requireNonNull(mcpServer, "mcpServer must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Execution-Id");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");

            String method = exchange.getRequestMethod().toUpperCase();
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(method)) {
                byte[] err = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"HTTP POST required\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Default granted scopes for integration calls
            Set<String> scopes = Set.of("read:workspace", "read:task", "write:workspace", "execute:build");
            String respJson = mcpServer.handleJsonRpcRequest(body, "evo-client", "default-proj", null, scopes);

            byte[] bytes = respJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
