package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.evo.integration.client.TeipClient;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeipProtocolContractTest {

    @Test
    void testExecutionRequestSerialization() {
        TeipExecutionRequest req = new TeipExecutionRequest(
                "TEIP",
                "1.0",
                "req-1",
                "corr-1",
                "idem-1",
                Instant.now().toString(),
                new TeipCaller("TRYST", "proj-1", "task-1"),
                "CODE_IMPLEMENTATION",
                "DEFAULT",
                "claude-3-5-sonnet",
                "Refactor authentication",
                List.of("src/Auth.java"),
                List.of("read:workspace", "write:workspace"),
                Map.of("temperature", 0.2)
        );

        String json = TeipClient.serializeExecutionRequest(req);
        assertNotNull(json);
        assertTrue(json.contains("\"protocol\":\"TEIP\""));
        assertTrue(json.contains("\"version\":\"1.0\""));
        assertTrue(json.contains("\"idempotencyKey\":\"idem-1\""));
        assertTrue(json.contains("\"capability\":\"CODE_IMPLEMENTATION\""));
        assertTrue(json.contains("\"src/Auth.java\""));
        assertTrue(json.contains("\"read:workspace\""));
    }

    @Test
    void testOpenWorldJsonParsingTolerance() {
        String json = """
        {
            "status": "UP",
            "protocol": "TEIP",
            "version": "1.0",
            "engineVersion": "0.1.0",
            "nodeId": "evo-node-1",
            "uptimeSeconds": 12345,
            "timestamp": "2026-11-06T18:00:00Z",
            "unknownFutureField": "should-be-tolerated-and-ignored"
        }
        """;

        assertEquals("UP", TeipClient.extractJsonString(json, "status"));
        assertEquals("TEIP", TeipClient.extractJsonString(json, "protocol"));
        assertEquals(12345L, TeipClient.extractJsonLong(json, "uptimeSeconds", 0));
        assertEquals("evo-node-1", TeipClient.extractJsonString(json, "nodeId"));
    }

    @Test
    void testStringListExtraction() {
        String json = """
        {
            "items": ["apple", "banana", "cherry"]
        }
        """;
        List<String> list = TeipClient.extractJsonStringList(json, "items");
        assertEquals(3, list.size());
        assertEquals("apple", list.get(0));
        assertEquals("banana", list.get(1));
        assertEquals("cherry", list.get(2));
    }
}
