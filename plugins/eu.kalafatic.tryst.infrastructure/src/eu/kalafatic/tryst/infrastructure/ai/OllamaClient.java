package eu.kalafatic.tryst.infrastructure.ai;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Ollama HTTP client implementation using Java 21 java.net.http.HttpClient.
 * Handles tags discovery, model inspection, generation, and streaming chat.
 */
public class OllamaClient implements AiInferenceService {

    private final String endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;

    public OllamaClient(String endpoint, Duration timeout) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public OllamaClient(String endpoint) {
        this(endpoint, Duration.ofSeconds(60));
    }

    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public CompletableFuture<Boolean> checkConnection() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> res.statusCode() == 200)
                .exceptionally(t -> {
                    TrystLog.warn(OllamaClient.class, "Ollama connection check failed: " + t.getMessage());
                    return false;
                });
    }

    @Override
    public CompletableFuture<List<ModelMetadata>> listModels() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/tags"))
                .timeout(timeout)
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() != 200) {
                        throw new TrystInfrastructureException("Failed to fetch models from Ollama: HTTP " + res.statusCode());
                    }
                    return parseModelsJson(res.body());
                });
    }

    @Override
    public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String jsonPayload = buildChatPayload(request, false);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/chat"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        long start = System.currentTimeMillis();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    long duration = System.currentTimeMillis() - start;
                    if (res.statusCode() != 200) {
                        throw new TrystInfrastructureException("Ollama chat failed with HTTP " + res.statusCode() + ": " + res.body());
                    }
                    String content = extractContentFromChatResponse(res.body());
                    return new InferenceResponse(content, "stop", duration, 0, 0);
                });
    }

    @Override
    public CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(chunkConsumer, "chunkConsumer must not be null");
        String jsonPayload = buildChatPayload(request, true);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/api/chat"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) {
                        throw new TrystInfrastructureException("Ollama streaming chat failed: HTTP " + res.statusCode());
                    }
                    res.body().forEach(line -> {
                        String chunk = extractContentChunk(line);
                        if (!chunk.isEmpty()) {
                            chunkConsumer.accept(chunk);
                        }
                    });
                });
    }

    private String buildChatPayload(InferenceRequest request, boolean stream) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(request.model())).append("\",");
        sb.append("\"stream\":").append(stream).append(",");
        sb.append("\"options\":{");
        sb.append("\"temperature\":").append(request.temperature());
        if (request.maxTokens() > 0) {
            sb.append(",\"num_predict\":").append(request.maxTokens());
        }
        sb.append("},");
        sb.append("\"messages\":[");
        for (int i = 0; i < request.messages().size(); i++) {
            ChatMessage msg = request.messages().get(i);
            sb.append("{");
            sb.append("\"role\":\"").append(escapeJson(msg.role())).append("\",");
            sb.append("\"content\":\"").append(escapeJson(msg.content())).append("\"");
            sb.append("}");
            if (i < request.messages().size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
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

    private static String extractContentFromChatResponse(String json) {
        // Find message content in: "message":{"role":"assistant","content":"..."}
        Pattern pattern = Pattern.compile("\"message\"\\s*:\\s*\\{[^}]*\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        // Fallback for /api/generate format: "response":"..."
        Pattern genPattern = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher genMatcher = genPattern.matcher(json);
        if (genMatcher.find()) {
            return unescapeJson(genMatcher.group(1));
        }
        return "";
    }

    private static String extractContentChunk(String line) {
        Pattern pattern = Pattern.compile("\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        Pattern respPattern = Pattern.compile("\"response\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher respMatcher = respPattern.matcher(line);
        if (respMatcher.find()) {
            return unescapeJson(respMatcher.group(1));
        }
        return "";
    }

    private static String unescapeJson(String input) {
        StringBuilder sb = new StringBuilder();
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = input.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '/' -> { sb.append('/'); i++; }
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'u' -> {
                        if (i + 5 < len) {
                            String hex = input.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<ModelMetadata> parseModelsJson(String json) {
        List<ModelMetadata> models = new ArrayList<>();
        Pattern modelPattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = modelPattern.matcher(json);
        while (matcher.find()) {
            String name = matcher.group(1);
            models.add(new ModelMetadata(name, 0, "", "standard", "", ""));
        }
        return models;
    }
}
