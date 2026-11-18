package eu.kalafatic.tryst.infrastructure.ai;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AIProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * ENR inference provider for TRYST (Phase 6, WP-6.6). Consumes the ENR 1.0 wire contract
 * (base {@code /enr/v1}) over HTTP: health probe, model discovery, blocking chat and SSE
 * streaming. ENR is the default family inference engine; the Ollama provider remains
 * registered as an alternative (fallback), and both are selectable per task through the
 * existing AiModelRegistryService / ModelSelectionService.
 */
public class EnrProvider implements AIProvider {

    public static final String PROVIDER_ID = "enr-default";
    public static final String SYS_ENR_ENDPOINT = "tryst.enr.endpoint";
    public static final String DEFAULT_ENDPOINT = "http://localhost:28780";

    private final String providerId;
    private final String endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;

    public EnrProvider() {
        this(PROVIDER_ID, resolveEndpoint(), Duration.ofSeconds(120));
    }

    public EnrProvider(String providerId, String endpoint, Duration timeout) {
        this.providerId = providerId == null || providerId.isBlank()
                ? PROVIDER_ID : providerId;
        this.endpoint = endpoint == null || endpoint.isBlank()
                ? resolveEndpoint()
                : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
                        : endpoint);
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static String resolveEndpoint() {
        String property = System.getProperty(SYS_ENR_ENDPOINT);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv("TRYST_ENR_ENDPOINT");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_ENDPOINT;
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    @Override
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public CompletableFuture<Boolean> checkConnection() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                TrystLog.warn(EnrProvider.class, "ENR health probe failed: "
                        + e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/enr/v1/models"))
                        .timeout(Duration.ofSeconds(15))
                        .GET().build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new TrystInfrastructureException(
                            "Failed to fetch models from ENR: HTTP "
                                    + response.statusCode());
                }
                List<ProviderModelMetadata> models = new ArrayList<>();
                String body = response.body();
                int index = 0;
                while ((index = body.indexOf("\"id\"", index)) >= 0) {
                    int colon = body.indexOf(':', index + 4);
                    if (colon < 0) {
                        break;
                    }
                    int startQuote = body.indexOf('"', colon + 1);
                    if (startQuote < 0) {
                        break;
                    }
                    int endQuote = body.indexOf('"', startQuote + 1);
                    if (endQuote < 0) {
                        break;
                    }
                    String id = body.substring(startQuote + 1, endQuote);
                    if (!id.isBlank()) {
                        models.add(new ProviderModelMetadata(id, 0, "", "standard",
                                "", ""));
                    }
                    index = endQuote + 1;
                }
                return models;
            } catch (Exception e) {
                throw new TrystInfrastructureException(
                        "ENR model discovery failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String payload = buildChatPayload(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/enr/v1/chat"))
                        .header("Content-Type", "application/json")
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                long start = System.currentTimeMillis();
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new TrystInfrastructureException("ENR chat failed with HTTP "
                            + response.statusCode() + ": " + response.body());
                }
                String content = extractJsonString(response.body(), "content");
                String finishReason = extractJsonString(response.body(), "finishReason");
                return new InferenceResponse(content == null ? "" : content,
                        finishReason == null ? "stop" : finishReason,
                        System.currentTimeMillis() - start, 0, 0);
            } catch (TrystInfrastructureException e) {
                throw e;
            } catch (Exception e) {
                throw new TrystInfrastructureException(
                        "ENR chat failed: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> streamResponse(InferenceRequest request,
            Consumer<String> chunkConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                String payload = buildChatPayload(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/enr/v1/chat/stream"))
                        .header("Content-Type", "application/json")
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<java.util.stream.Stream<String>> response =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() != 200) {
                    throw new TrystInfrastructureException(
                            "ENR streaming chat failed with HTTP "
                                    + response.statusCode());
                }
                try (java.util.stream.Stream<String> lines = response.body()) {
                    lines.forEach(line -> {
                        if (line == null || !line.startsWith("data:")) {
                            return;
                        }
                        String data = line.substring("data:".length()).trim();
                        String chunk = extractJsonString(data, "chunk");
                        boolean done = "true".equals(extractJsonString(data, "done"));
                        if (chunk != null && !chunk.isEmpty()) {
                            chunkConsumer.accept(chunk);
                        }
                        if (done) {
                            // terminal event
                        }
                    });
                }
            } catch (TrystInfrastructureException e) {
                throw e;
            } catch (Exception e) {
                throw new TrystInfrastructureException(
                        "ENR streaming chat failed: " + e.getMessage(), e);
            }
        });
    }

    private String buildChatPayload(InferenceRequest request) {
        StringBuilder messages = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            if (messages.length() > 0) {
                messages.append(',');
            }
            messages.append("{\"role\":\"").append(escapeJson(message.role()))
                    .append("\",\"content\":\"").append(escapeJson(message.content()))
                    .append("\"}");
        }
        String model = request.model() == null || request.model().isBlank()
                ? "llama3.2:3b" : request.model();
        return "{\"model\":\"" + escapeJson(model) + "\",\"messages\":["
                + messages + "],\"temperature\":" + request.temperature()
                + ",\"maxTokens\":" + request.maxTokens() + "}";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String extractJsonString(String json, String field) {
        if (json == null) {
            return null;
        }
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
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
