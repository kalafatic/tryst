package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.evo.integration.client.TeipClient;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeipClientTest {

    private HttpServer mockServer;
    private int mockPort;
    private TeipClient client;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockPort = mockServer.getAddress().getPort();
        client = new TeipClient(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void testHealthCheck() throws Exception {
        mockServer.createContext("/teip/v1/health", exchange -> {
            assertEquals("Bearer secret-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String resp = """
            {
                "status": "UP",
                "protocol": "TEIP",
                "version": "1.1",
                "engineVersion": "2.6.11",
                "nodeId": "evo-test",
                "uptimeSeconds": 100,
                "timestamp": "2026-11-06T18:00:00Z"
            }
            """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });
        mockServer.start();

        TeipHealthResponse hr = client.checkHealth("http://127.0.0.1:" + mockPort, "secret-token");
        assertNotNull(hr);
        assertEquals("UP", hr.status());
        assertEquals("TEIP", hr.protocol());
        assertEquals("1.1", hr.version());
        assertEquals("evo-test", hr.nodeId());
    }

    @Test
    void testExecutionCreationAndCancellation() throws Exception {
        mockServer.createContext("/teip/v1/executions", exchange -> {
            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                String resp = """
                {
                    "executionId": "evo-exec-101",
                    "evoSessionId": "evo-sess-101",
                    "status": "QUEUED",
                    "createdAt": "2026-11-06T18:00:00Z"
                }
                """;
                exchange.sendResponseHeaders(201, resp.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp.getBytes());
                }
            }
        });

        mockServer.createContext("/teip/v1/executions/evo-exec-101/cancel", exchange -> {
            String resp = "{\"executionId\":\"evo-exec-101\",\"status\":\"CANCELLED\",\"message\":\"Accepted\"}";
            exchange.sendResponseHeaders(200, resp.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });
        mockServer.start();

        TeipExecutionRequest req = new TeipExecutionRequest(
                "TEIP", "1.1", "req-1", "corr-1", "idem-1", Instant.now().toString(),
                new TeipCaller("TRYST", "proj-1", "task-1"),
                "CODE_IMPLEMENTATION", "DEFAULT", "claude-3-5-sonnet",
                "Refactor codebase", List.of(), List.of("read:workspace"), Map.of()
        );

        TeipExecutionResponse createResp = client.createExecution("http://127.0.0.1:" + mockPort, "token", req);
        assertNotNull(createResp);
        assertEquals("evo-exec-101", createResp.executionId());
        assertEquals("QUEUED", createResp.status());

        TeipCancelResponse cancelResp = client.cancelExecution("http://127.0.0.1:" + mockPort, "token", "evo-exec-101");
        assertNotNull(cancelResp);
        assertEquals("CANCELLED", cancelResp.status());
    }

    @Test
    void testCreateExecutionAcceptsCanonical202AndDuplicate200() throws Exception {
        mockServer.createContext("/teip/v1/executions", exchange -> {
            String idemKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            String resp = "{\"executionId\":\"evo-exec-202\",\"status\":\"QUEUED\",\"correlationId\":\"corr-1\",\"createdAt\":\"2026-11-06T18:00:00Z\"}";
            int status = "idem-dup".equals(idemKey) ? 200 : 202;
            exchange.sendResponseHeaders(status, resp.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });
        mockServer.start();

        TeipExecutionRequest newReq = new TeipExecutionRequest(
                "TEIP", "1.1", "req-new", "corr-1", "idem-new", Instant.now().toString(),
                new TeipCaller("TRYST", "proj-1", "task-1"),
                "CODE_IMPLEMENTATION", "DEFAULT", null, "Do it", List.of(), List.of(), Map.of());
        TeipExecutionResponse newResp = client.createExecution("http://127.0.0.1:" + mockPort, "token", newReq);
        assertEquals("evo-exec-202", newResp.executionId());

        TeipExecutionRequest dupReq = new TeipExecutionRequest(
                "TEIP", "1.1", "req-dup", "corr-1", "idem-dup", Instant.now().toString(),
                new TeipCaller("TRYST", "proj-1", "task-1"),
                "CODE_IMPLEMENTATION", "DEFAULT", null, "Do it", List.of(), List.of(), Map.of());
        TeipExecutionResponse dupResp = client.createExecution("http://127.0.0.1:" + mockPort, "token", dupReq);
        assertEquals("evo-exec-202", dupResp.executionId());
    }

    @Test
    void testErrorEnvelopeParsingOnNon2xx() throws Exception {
        mockServer.createContext("/teip/v1/executions", exchange -> {
            String resp = "{\"protocol\":\"TEIP\",\"version\":\"1.1\",\"code\":\"VALIDATION_FAILED\",\"message\":\"Missing instruction\",\"retryable\":false}";
            exchange.sendResponseHeaders(400, resp.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });
        mockServer.start();

        TeipExecutionRequest req = new TeipExecutionRequest(
                "TEIP", "1.1", "req-1", "corr-1", null, Instant.now().toString(),
                new TeipCaller("TRYST", "proj-1", "task-1"),
                "CODE_IMPLEMENTATION", "DEFAULT", null, "", List.of(), List.of(), Map.of());
        Exception thrown = assertThrows(Exception.class,
                () -> client.createExecution("http://127.0.0.1:" + mockPort, "token", req));
        assertTrue(thrown.getMessage().contains("VALIDATION_FAILED"));
        assertTrue(thrown.getMessage().contains("Missing instruction"));
    }

    @Test
    void testCursorEventPagination() throws Exception {
        mockServer.createContext("/teip/v1/executions/evo-exec-101/events", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("afterSequence=2"));
            String resp = """
            {
                "executionId": "evo-exec-101",
                "events": [
                    {
                        "eventId": "evt-3",
                        "executionId": "evo-exec-101",
                        "sequence": 3,
                        "type": "AST_ANALYSIS_COMPLETED",
                        "timestamp": "2026-11-06T18:00:05Z",
                        "correlationId": "corr-1",
                        "data": { "astNodes": 42 }
                    }
                ],
                "hasMore": false,
                "nextSequence": 4
            }
            """;
            exchange.sendResponseHeaders(200, resp.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp.getBytes());
            }
        });
        mockServer.start();

        TeipEventsResponse events = client.getExecutionEvents("http://127.0.0.1:" + mockPort, "token", "evo-exec-101", 2, 50);
        assertNotNull(events);
        assertEquals(1, events.events().size());
        assertEquals("evt-3", events.events().get(0).eventId());
        assertEquals(3, events.events().get(0).sequence());
        assertFalse(events.hasMore());
        assertEquals(4, events.nextSequence());
    }

    @Test
    void testLatestSequenceDeprecatedAliasStillParsed() {
        String json = """
        {
            "executionId": "evo-exec-101",
            "events": [],
            "hasMore": false,
            "latestSequence": 7
        }
        """;
        TeipEventsResponse events = TeipClient.parseEventsResponse(json, "evo-exec-101");
        assertEquals(7L, events.nextSequence());
    }
}
