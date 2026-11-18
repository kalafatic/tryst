package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.infrastructure.ai.EnrProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRYST → ENR inference provider wire tests (Phase 6, WP-6.6). Stub ENR /enr/v1 server;
 * live inference verification runs against the real ENR runtime.
 */
class EnrProviderTest {

    private HttpServer stubServer;
    private int stubPort;

    @BeforeEach
    void setUp() throws Exception {
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/health", exchange -> respond(exchange, 200,
                "{\"application\":\"ENR\",\"status\":\"UP\"}"));
        stubServer.createContext("/enr/v1/models", exchange -> respond(exchange, 200,
                "[{\"id\":\"ollama:demo:1\",\"name\":\"demo:1\",\"provider\":\"ollama\","
                        + "\"engine\":\"ollama\",\"sizeBytes\":1024,\"available\":true},"
                        + "{\"id\":\"evo-native:demo.evo\",\"name\":\"demo.evo\","
                        + "\"provider\":\"evo-native\",\"engine\":\"evo native\","
                        + "\"sizeBytes\":2048,\"available\":true}]"));
        stubServer.createContext("/enr/v1/chat", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String payload = new String(body, StandardCharsets.UTF_8);
            String model = extract(payload, "model");
            respond(exchange, 200,
                    "{\"content\":\"enr answer for " + model
                            + "\",\"finishReason\":\"stop\",\"engine\":\"ollama\","
                            + "\"model\":\"" + model + "\",\"durationMs\":7}");
        });
        stubServer.createContext("/enr/v1/chat/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"chunk\":\"streamed \",\"done\":false}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("data: {\"chunk\":\"answer\",\"done\":false}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(("data: {\"chunk\":\"\",\"done\":true}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        stubServer.stop(0);
    }

    private static String extract(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx + key.length());
        int start = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', start + 1);
        return start < 0 || end < 0 ? "" : json.substring(start + 1, end);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private EnrProvider provider() {
        return new EnrProvider("enr-test", "http://127.0.0.1:" + stubPort,
                Duration.ofSeconds(30));
    }

    @Test
    void identityAndEndpoint() {
        EnrProvider provider = provider();
        assertEquals("enr-test", provider.getProviderId());
        assertTrue(provider.getEndpoint().contains(String.valueOf(stubPort)));
        assertEquals("http://localhost:28780", EnrProvider.DEFAULT_ENDPOINT);
    }

    @Test
    void connectionCheck() throws Exception {
        assertTrue(provider().checkConnection().get(10, TimeUnit.SECONDS));
    }

    @Test
    void connectionCheckFailsWhenUnreachable() throws Exception {
        EnrProvider unreachable = new EnrProvider("enr-x", "http://127.0.0.1:1",
                Duration.ofSeconds(5));
        assertFalse(unreachable.checkConnection().get(10, TimeUnit.SECONDS));
    }

    @Test
    void modelDiscovery() throws Exception {
        List<AIProvider.ProviderModelMetadata> models = provider().listAvailableModels()
                .get(10, TimeUnit.SECONDS);
        assertEquals(2, models.size());
        assertTrue(models.stream().anyMatch(m -> m.name().equals("ollama:demo:1")));
        assertTrue(models.stream().anyMatch(m -> m.name().equals("evo-native:demo.evo")));
    }

    @Test
    void generateResponse() throws Exception {
        AIProvider.InferenceRequest request = new AIProvider.InferenceRequest(
                "ollama:demo:1",
                List.of(AIProvider.ChatMessage.user("hello")),
                0.7, 128, java.util.Map.of());
        AIProvider.InferenceResponse response = provider().generateResponse(request)
                .get(10, TimeUnit.SECONDS);
        assertEquals("enr answer for ollama:demo:1", response.content());
        assertEquals("stop", response.finishReason());
    }

    @Test
    void streamResponseDeliversChunks() throws Exception {
        AIProvider.InferenceRequest request = new AIProvider.InferenceRequest(
                "ollama:demo:1",
                List.of(AIProvider.ChatMessage.user("hello")),
                0.7, 128, java.util.Map.of());
        StringBuilder collected = new StringBuilder();
        AtomicInteger chunks = new AtomicInteger();
        provider().streamResponse(request, text -> {
            chunks.incrementAndGet();
            collected.append(text);
        }).get(15, TimeUnit.SECONDS);
        assertEquals(2, chunks.get());
        assertEquals("streamed answer", collected.toString());
    }

    @Test
    void chatFailureIsReported() {
        EnrProvider unreachable = new EnrProvider("enr-x", "http://127.0.0.1:1",
                Duration.ofSeconds(5));
        AIProvider.InferenceRequest request = new AIProvider.InferenceRequest(
                "ollama:demo:1", List.of(AIProvider.ChatMessage.user("hello")));
        try {
            unreachable.generateResponse(request).get(15, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("expected failure");
        } catch (Exception expected) {
            // unreachable ENR endpoint must surface a provider failure
        }
    }
}
