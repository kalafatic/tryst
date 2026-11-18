package eu.kalafatic.tryst.infrastructure.contextual;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import eu.kalafatic.tryst.domain.contextual.ContextualAction;
import eu.kalafatic.tryst.domain.contextual.ContextualActionIds;
import eu.kalafatic.tryst.domain.contextual.ContextualActionRegistry;
import eu.kalafatic.tryst.domain.contextual.ContextualExecutionResult;
import eu.kalafatic.tryst.domain.contextual.ContextualSelection;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.security.SecurityContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Web surface of the unified contextual action framework for TRYST (Darwin Family
 * Contextual Action Contract v1.0): the same action ids and domain operations as the
 * RCP navigator menus, resolved and executed through the single registry. Permissions
 * are populated from the authenticated {@link SecurityContext}; the domain services
 * enforce them. Uses the same hand-rolled JSON style as the rest of TrystHttpServer
 * (no JSON library in this bundle).
 */
public class ContextualHttpHandler implements HttpHandler {

    public static final String PATH = "/api/contextual";

    private final ContextualActionRegistry registry;
    private final Function<HttpExchange, SecurityContext> securityContextResolver;

    public ContextualHttpHandler(ContextualActionRegistry registry,
            Function<HttpExchange, SecurityContext> securityContextResolver) {
        this.registry = registry;
        this.securityContextResolver = securityContextResolver;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String rest = path.startsWith(PATH) ? path.substring(PATH.length()) : path;
            if ("GET".equals(method) && rest.equals("/actions")) {
                handleResolve(exchange);
            } else if ("POST".equals(method) && rest.equals("/execute")) {
                handleExecute(exchange);
            } else {
                respond(exchange, 404, "{\"ok\": false, \"error\": \"NOT_FOUND\"}");
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"ok\": false, \"error\": \"INTERNAL\", \"message\": \""
                    + escape(e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()) + "\"}");
        }
    }

    private void handleResolve(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        ContextualSelection selection = selectionFrom(query, permissions(exchange));
        StringBuilder json = new StringBuilder();
        json.append("{\"contractVersion\": \"").append(ContextualActionIds.CONTRACT_VERSION)
                .append("\", \"actions\": [");
        List<ContextualAction> actions = registry.resolve(selection);
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            ContextualAction action = actions.get(i);
            json.append("{\"actionId\": \"").append(action.actionId())
                    .append("\", \"label\": \"")
                    .append(escape(action.descriptor().label()))
                    .append("\", \"description\": \"")
                    .append(escape(action.descriptor().description()))
                    .append("\", \"group\": \"").append(action.descriptor().group())
                    .append("\", \"enabled\": ").append(action.enabled())
                    .append(", \"disabledReason\": \"")
                    .append(escape(action.disabledReason()))
                    .append("\", \"confirmation\": \"")
                    .append(action.descriptor().confirmation().name().toLowerCase())
                    .append("\", \"dangerous\": ")
                    .append(action.descriptor().dangerous()).append("}");
        }
        json.append("]}");
        respond(exchange, 200, json.toString());
    }

    private void handleExecute(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        String actionId = extractJsonField(body, "actionId");
        if (actionId == null || actionId.isBlank()) {
            respond(exchange, 400, "{\"ok\": false, \"error\": \"VALIDATION_FAILED\"}");
            return;
        }
        ContextualSelection selection = selectionFrom(body, permissions(exchange));
        ContextualExecutionResult result = registry.execute(actionId, selection);
        respond(exchange, 200, "{\"ok\": " + result.ok() + ", \"actionId\": \""
                + result.actionId() + "\", \"message\": \""
                + escape(result.message()) + "\", \"error\": \"" + result.errorCode()
                + "\"}");
    }

    private java.util.Set<String> permissionSet(SecurityContext context) {
        if (context == null || context.user() == null) {
            return Set.of();
        }
        if (context.isAdmin()) {
            List<String> all = new ArrayList<>();
            for (Permission permission : Permission.values()) {
                all.add(permission.name());
            }
            return Set.copyOf(all);
        }
        List<String> granted = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (context.user().hasPermission(permission)) {
                granted.add(permission.name());
            }
        }
        return Set.copyOf(granted);
    }

    private Set<String> permissions(HttpExchange exchange) {
        if (securityContextResolver == null) {
            return Set.of();
        }
        return permissionSet(securityContextResolver.apply(exchange));
    }

    private ContextualSelection selectionFrom(Map<String, String> fields,
            Set<String> permissions) {
        ContextualSelection.Builder builder = ContextualSelection.builder("TRYST", "WEB",
                fields.getOrDefault("type", fields.getOrDefault("objectType", "")));
        builder.object(fields.getOrDefault("id", fields.getOrDefault("objectId", "")),
                fields.getOrDefault("label", fields.getOrDefault("objectLabel", "")))
                .projectId(fields.getOrDefault("projectId", ""))
                .sessionId(fields.getOrDefault("sessionId", ""))
                .permissions(permissions)
                .user("web");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey().startsWith("state.")) {
                builder.state(entry.getKey().substring("state.".length()),
                        entry.getValue());
            }
        }
        return builder.build();
    }

    private ContextualSelection selectionFrom(String json, Set<String> permissions) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("objectType", extractJsonField(json, "objectType"));
        fields.put("objectId", extractJsonField(json, "objectId"));
        fields.put("objectLabel", extractJsonField(json, "objectLabel"));
        fields.put("projectId", extractJsonField(json, "projectId"));
        fields.put("sessionId", extractJsonField(json, "sessionId"));
        return selectionFrom(fields, permissions);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                result.put(URLDecoder.decode(pair.substring(0, idx),
                        StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1),
                                StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /** Minimal flat-JSON string field extraction (same style as TrystHttpServer). */
    private static String extractJsonField(String json, String field) {
        if (json == null) {
            return "";
        }
        String needle = "\"" + field + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return "";
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) {
                return "";
            }
            return json.substring(start + 1, end)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == 't'
                || json.charAt(end) == 'f' || json.charAt(end) == 'n')) {
            end++;
        }
        return json.substring(start, end);
    }

    private static String escape(String value) {
        return value == null ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r");
    }

    private void respond(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
