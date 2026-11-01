package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.infrastructure.ai.OllamaClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OllamaClientTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/tags", exchange -> {
            String json = """
                {
                    "models": [
                        {"name": "codellama:latest"},
                        {"name": "llama3:latest"}
                    ]
                }
            """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/chat", exchange -> {
            String json = """
                {
                    "model": "codellama",
                    "message": {
                        "role": "assistant",
                        "content": "Hello! I am ready to write code."
                    },
                    "done": true
                }
            """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testCheckConnectionAndListModels() throws Exception {
        OllamaClient client = new OllamaClient("http://localhost:" + port);
        assertTrue(client.checkConnection().get(5, TimeUnit.SECONDS));

        List<AiInferenceService.ModelMetadata> models = client.listModels().get(5, TimeUnit.SECONDS);
        assertNotNull(models);
        assertEquals(2, models.size());
        assertEquals("codellama:latest", models.get(0).name());
    }

    @Test
    void testGenerateResponse() throws Exception {
        OllamaClient client = new OllamaClient("http://localhost:" + port);
        AiInferenceService.InferenceRequest req = new AiInferenceService.InferenceRequest(
                "codellama",
                List.of(AiInferenceService.ChatMessage.user("Hi"))
        );
        AiInferenceService.InferenceResponse response = client.generateResponse(req).get(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("Hello! I am ready to write code.", response.content());
    }

    @Test
    void testAiContextManager() {
        AiContextManager ctxMgr = new AiContextManager(1000);
        String sysPrompt = ctxMgr.buildSystemInstructions(null, null);
        assertNotNull(sysPrompt);
        assertTrue(sysPrompt.contains("autonomous software development agent"));

        List<AiInferenceService.ChatMessage> messages = ctxMgr.assembleConversationContext(
                sysPrompt, List.of(), "Fix bug", null
        );
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertEquals("Fix bug", messages.get(1).content());
    }
}
