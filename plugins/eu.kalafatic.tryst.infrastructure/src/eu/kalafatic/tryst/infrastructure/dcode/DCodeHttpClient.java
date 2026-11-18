package eu.kalafatic.tryst.infrastructure.dcode;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DCode REST client for TRYST (Phase 6, FHC 1.0 wire mapping). Wire-only:
 * TRYST never binds DCode classes; the native DCode harness is driven exclusively through
 * its stable /api/v1 HTTP surface. DCode never commits in delegated mode; sessions with
 * changes end in WAITING_FOR_APPROVAL and the consuming application reviews the diff.
 */
public class DCodeHttpClient {

    private final String endpoint;
    private final String authToken;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public DCodeHttpClient(String endpoint, String authToken, Duration requestTimeout) {
        this.endpoint = endpoint != null && !endpoint.isBlank() ? endpoint
                : "http://localhost:48080";
        this.authToken = authToken;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean health() {
        try {
            HttpResponse<String> response = send("GET", "/health", null);
            return response.statusCode() == 200;
        } catch (Exception e) {
            TrystLog.warn(DCodeHttpClient.class, "DCode health probe failed: " + e.getMessage());
            return false;
        }
    }

    public String createWorkspace(String name, String rootPath) {
        String body = "{\"name\":\"" + escape(name) + "\",\"rootPath\":\""
                + escape(rootPath) + "\"}";
        HttpResponse<String> response = send("POST", "/api/v1/workspaces", body);
        requireStatus(response, 200, "workspace creation");
        return extractString(response.body(), "id");
    }

    public void addRepository(String workspaceId, String name, String localPath) {
        String body = "{\"name\":\"" + escape(name) + "\",\"localPath\":\""
                + escape(localPath) + "\",\"accessPolicy\":\"READ_WRITE\"}";
        HttpResponse<String> response = send("POST", "/api/v1/workspaces/"
                + workspaceId + "/repositories", body);
        requireStatus(response, 200, "repository registration");
    }

    public String createSession(String title, String workspaceId, String providerId,
            String modelId) {
        StringBuilder body = new StringBuilder("{\"title\":\"" + escape(title)
                + "\",\"workspaceId\":\"" + escape(workspaceId) + "\"");
        if (providerId != null && !providerId.isBlank()) {
            body.append(",\"providerId\":\"").append(escape(providerId)).append("\"");
        }
        if (modelId != null && !modelId.isBlank()) {
            body.append(",\"modelId\":\"").append(escape(modelId)).append("\"");
        }
        body.append("}");
        HttpResponse<String> response = send("POST", "/api/v1/sessions",
                body.toString());
        requireStatus(response, 200, "session creation");
        return extractString(response.body(), "id");
    }

    public void startSession(String sessionId, String task) {
        HttpResponse<String> response = send("POST", "/api/v1/sessions/"
                + sessionId + "/start", "{\"task\":\"" + escape(task) + "\"}");
        requireStatus(response, 200, "execution submission");
    }

    public void cancelSession(String sessionId) {
        try {
            send("POST", "/api/v1/sessions/" + sessionId + "/cancel", null);
        } catch (Exception e) {
            TrystLog.warn(DCodeHttpClient.class, "DCode cancel failed: " + e.getMessage());
        }
    }

    public String sessionState(String sessionId) {
        HttpResponse<String> response = send("GET", "/api/v1/sessions/"
                + sessionId, null);
        requireStatus(response, 200, "session lookup");
        return extractString(response.body(), "state");
    }

    public List<String> messages(String sessionId) {
        HttpResponse<String> response = send("GET", "/api/v1/sessions/"
                + sessionId + "/messages", null);
        requireStatus(response, 200, "message lookup");
        List<String> contents = new ArrayList<>();
        String body = response.body();
        int index = 0;
        while ((index = body.indexOf("\"content\"", index)) >= 0) {
            String value = extractStringFrom(body, index);
            if (value != null && !value.isBlank()) {
                contents.add(value);
            }
            index += "\"content\"".length();
        }
        return contents;
    }

    public List<String> changedFiles(String sessionId) {
        HttpResponse<String> response = send("GET", "/api/v1/sessions/"
                + sessionId + "/changes", null);
        requireStatus(response, 200, "change lookup");
        List<String> files = new ArrayList<>();
        String body = response.body();
        int index = 0;
        String field = "\"changedFiles\"";
        while ((index = body.indexOf(field, index)) >= 0) {
            int arrayStart = body.indexOf('[', index + field.length());
            if (arrayStart < 0) {
                break;
            }
            int arrayEnd = body.indexOf(']', arrayStart);
            if (arrayEnd < 0) {
                break;
            }
            String arrayContent = body.substring(arrayStart + 1, arrayEnd);
            int itemIndex = 0;
            while ((itemIndex = arrayContent.indexOf('"', itemIndex)) >= 0) {
                int itemEnd = arrayContent.indexOf('"', itemIndex + 1);
                if (itemEnd < 0) {
                    break;
                }
                String file = arrayContent.substring(itemIndex + 1, itemEnd);
                if (!file.isBlank()) {
                    files.add(file);
                }
                itemIndex = itemEnd + 1;
            }
            index = arrayEnd + 1;
        }
        return files;
    }

    private HttpResponse<String> send(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json");
            if (authToken != null && !authToken.isBlank()) {
                builder.header("Authorization", "Bearer " + authToken);
            }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new TrystInfrastructureException("DCode HTTP request failed ("
                    + method + " " + path + "): " + e.getMessage(), e);
        }
    }

    private static void requireStatus(HttpResponse<String> response, int expected,
            String operation) {
        if (response.statusCode() != expected) {
            throw new TrystInfrastructureException("DCode " + operation + " failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String extractString(String json, String field) {
        if (json == null) {
            return null;
        }
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) {
            return null;
        }
        return extractStringFrom(json, idx + field.length() + 2);
    }

    private static String extractStringFrom(String json, int fromIndex) {
        int colon = json.indexOf(':', fromIndex);
        if (colon < 0) {
            return null;
        }
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return null;
        }
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
