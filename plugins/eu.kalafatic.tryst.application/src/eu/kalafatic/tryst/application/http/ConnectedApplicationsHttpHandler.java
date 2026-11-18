package eu.kalafatic.tryst.application.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.security.SecurityContext;


import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP management surface for the connected-application registry (Phase 3, A-14):
 * listing, selection, connection testing, configuration updates and enable/disable.
 *
 * <p>Authorization (Phase 3, WP-3.10): every operation requires an authenticated
 * principal holding the {@link Permission#MANAGE_AGENTS} permission (admins always hold
 * it). Requests without valid credentials are rejected with 401; authenticated callers
 * without the permission are rejected with 403 and audited via the existing
 * {@link PermissionManager}. The explicit emergency development-mode bypass of the
 * platform ({@link ApplicationConfiguration#isDevelopmentMode()}) remains the only
 * unauthenticated fallback, identical to the server-wide authorization convention.</p>
 */
public final class ConnectedApplicationsHttpHandler implements HttpHandler {

    private final ConnectedApplicationRegistry registry;
    private final AuthenticationService authenticationService;
    private final PermissionManager permissionManager;

    public ConnectedApplicationsHttpHandler(ConnectedApplicationRegistry registry,
                                            AuthenticationService authenticationService,
                                            PermissionManager permissionManager) {
        this.registry = registry;
        this.authenticationService = authenticationService;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.contains("/api/integration/applications")) {
            path = path.substring(path.indexOf("/api/integration/applications"));
        }
        String method = exchange.getRequestMethod().toUpperCase();

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!authorize(exchange)) {
            return;
        }

        try {
            if ("/api/integration/applications".equals(path) && "GET".equals(method)) {
                handleList(exchange);
                return;
            }
            if ("/api/integration/applications/select".equals(path) && "POST".equals(method)) {
                handleSelect(exchange);
                return;
            }
            if (path.startsWith("/api/integration/applications/")) {
                String sub = path.substring("/api/integration/applications/".length());
                if (sub.endsWith("/test") && "POST".equals(method)) {
                    handleTest(exchange, sub.substring(0, sub.length() - "/test".length()));
                    return;
                }
                if (sub.endsWith("/config") && "POST".equals(method)) {
                    handleUpdateConfig(exchange, sub.substring(0, sub.length() - "/config".length()));
                    return;
                }
                if (sub.endsWith("/enable") && "POST".equals(method)) {
                    handleEnable(exchange, sub.substring(0, sub.length() - "/enable".length()));
                    return;
                }
            }
            sendJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (ConnectedApplicationDescriptor d : registry.listApplications()) {
            if (i++ > 0) sb.append(",");
            CodingHarnessAdapter adapter = registry.findAdapter(d.getId()).orElse(null);
            Map<String, String> config = adapter != null ? adapter.getConnectionConfiguration() : Map.of();
            StringBuilder cfg = new StringBuilder("{");
            int ci = 0;
            for (Map.Entry<String, String> e : config.entrySet()) {
                if (ci++ > 0) cfg.append(",");
                cfg.append("\"").append(escape(e.getKey())).append("\":\"")
                        .append(escape(e.getValue())).append("\"");
            }
            cfg.append("}");
            sb.append(String.format(
                    "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"enabled\":%b,\"status\":\"%s\",\"statusMessage\":\"%s\",\"selected\":%b,\"capabilities\":[%s],\"config\":%s}",
                    escape(d.getId()),
                    escape(d.getName()),
                    escape(d.getApplicationType()),
                    d.isEnabled(),
                    escape(d.getStatus().name()),
                    escape(d.getStatusMessage()),
                    registry.getSelectedApplicationId().map(id -> id.equals(d.getId())).orElse(false),
                    String.join(",", d.getCapabilities().stream().map(c -> "\"" + escape(c) + "\"").toList()),
                    cfg.toString()));
        }
        sb.append("]");
        sendJson(exchange, 200, sb.toString());
    }

    private void handleSelect(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String applicationId = extractJsonString(body, "applicationId");
        registry.selectApplication(applicationId);
        sendJson(exchange, 200, "{\"success\":true,\"selectedApplicationId\":\""
                + escape(registry.getSelectedApplicationId().orElse("")) + "\"}");
    }

    private void handleTest(HttpExchange exchange, String applicationId) throws IOException {
        CodingHarnessAdapter.ConnectionTestResult result = registry.testConnection(applicationId);
        sendJson(exchange, 200, String.format("{\"applicationId\":\"%s\",\"success\":%b,\"message\":\"%s\"}",
                escape(applicationId), result.success(), escape(result.message())));
    }

    private void handleUpdateConfig(HttpExchange exchange, String applicationId) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> config = new HashMap<>();
        String[] tokens = body.replace("{", "").replace("}", "").split(",");
        for (String token : tokens) {
            int colon = token.indexOf(':');
            if (colon > 0) {
                String key = token.substring(0, colon).trim().replace("\"", "");
                String value = token.substring(colon + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isBlank()) {
                    config.put(key, value);
                }
            }
        }
        registry.updateConfiguration(applicationId, config);
        sendJson(exchange, 200, "{\"success\":true}");
    }

    private void handleEnable(HttpExchange exchange, String applicationId) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean enabled = body.contains("\"enabled\":true") || body.contains("\"enabled\": true");
        registry.setApplicationEnabled(applicationId, enabled);
        sendJson(exchange, 200, String.format("{\"success\":true,\"applicationId\":\"%s\",\"enabled\":%b}",
                escape(applicationId), enabled));
    }

    /**
     * Authorization gate applied to every operation (GET and all state-changing POSTs).
     * Follows the platform convention established by the server-side admin/permission
     * enforcement (TrystHttpServer AdminHandler and permission checks):
     * - Missing or invalid credentials -> 401 (fail closed, audited).
     * - Authenticated principal without {@link Permission#MANAGE_AGENTS} -> 403 (audited).
     * - Authorized principal -> continue.
     * The explicit emergency development mode provides the DEV admin context exactly as
     * the embedded server does.
     */
    private boolean authorize(HttpExchange exchange) throws IOException {
        Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
        if (ctx.isEmpty()) {
            permissionManager.recordAudit("unauthenticated", "CONNECTED_APPLICATIONS_DENIED",
                    "application", null, null, null, "DENIED", "Missing or invalid credentials");
            sendJson(exchange, 401, "{\"error\":\"Authentication required\"}");
            return false;
        }
        SecurityContext principal = ctx.get();
        if (!principal.user().hasPermission(Permission.MANAGE_AGENTS)) {
            permissionManager.recordAudit(principal.getUserId(), "CONNECTED_APPLICATIONS_DENIED",
                    "application", null, null, null, "DENIED",
                    "User lacks MANAGE_AGENTS permission (role=" + principal.getRole() + ")");
            sendJson(exchange, 403, "{\"error\":\"Access denied: MANAGE_AGENTS permission required\"}");
            return false;
        }
        return true;
    }

    /**
     * Resolves the security context from the Bearer token using the existing
     * {@link AuthenticationService} (the same token validation the embedded HTTP server
     * uses for its own authorization). Falls back to the explicit emergency
     * development-mode DEV context; when authentication cannot be established the result
     * is empty and the gate fails closed.
     */
    private Optional<SecurityContext> resolveSecurityContext(HttpExchange exchange) {
        if (authenticationService == null) {
            return Optional.empty();
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length()).trim();
            if (!token.isEmpty()) {
                return authenticationService.authenticateToken(token);
            }
        }
        if (ApplicationConfiguration.isDevelopmentMode()) {
            return Optional.of(SecurityContext.DEV);
        }
        return Optional.empty();
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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

    private static String extractJsonString(String json, String field) {
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
}
