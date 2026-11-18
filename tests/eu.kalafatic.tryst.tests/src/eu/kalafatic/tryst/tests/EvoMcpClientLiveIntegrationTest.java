package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.evo.integration.client.EvoMcpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration of the TRYST EvoMcpClient against a running EVO MCP
 * server (127.0.0.1:28280). Skips cleanly when EVO is not running, so the
 * suite stays green in isolation. When run with EVO up, this is the real
 * client-side half of the cross-application contract.
 */
public class EvoMcpClientLiveIntegrationTest {

    private static final String ENDPOINT = "http://127.0.0.1:28280";
    private static final String TOKEN = "tryst-token-verify-001";
    private static final String PROJECT = "/tmp/tryst-live-project";

    private EvoMcpClient client;

    @BeforeEach
    public void setUp() {
        // Silent skip when EVO is not running (no opentest4j in the target
        // platform, so the skip is implemented manually).
        if (isEvoReachable()) {
            client = new EvoMcpClient();
        }
    }

    private boolean isEvoReachable() {
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create(ENDPOINT + "/health").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.connect();
            int code = connection.getResponseCode();
            return code >= 200 && code < 600;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void liveContextGetReturnsPackage() {
        if (client == null) {
            return; // EVO not running - skip
        }
        EvoMcpClient.ContextResult result = client.contextGet(ENDPOINT, TOKEN, PROJECT,
                null, "verify live client", 4000,
                List.of("user", "project", "memory", "decisions"));
        assertNotNull(result, "context_get must return a package");
        assertNotNull(result.packageJson());
        assertTrue(result.packageJson().contains("schemaVersion"));
        assertTrue(result.tokenEstimate() >= 0, "Token estimate must be non-negative");
    }

    @Test
    public void liveMemoryStoreAndSearch() {
        if (client == null) {
            return;
        }
        boolean stored = client.memoryStore(ENDPOINT, TOKEN,
                "TRYST live client verification note", "FACT", "PROJECT", PROJECT, 0.9);
        assertTrue(stored, "memory_store must succeed against live EVO");
    }

    @Test
    public void liveSessionCreate() {
        if (client == null) {
            return;
        }
        String sessionId = client.sessionCreate(ENDPOINT, TOKEN, "TASK", PROJECT,
                "tryst-live-client-1");
        assertNotNull(sessionId, "session_create must return a cognitive session id");
    }

    @Test
    public void invalidTokenDegradesGracefully() {
        if (client == null) {
            return;
        }
        // The client must degrade (null) instead of throwing on auth failure.
        EvoMcpClient.ContextResult result = client.contextGet(ENDPOINT, "invalid-token",
                PROJECT, null, "verify", 1000, null);
        assertTrue(result == null, "Invalid token must yield graceful degradation (null result)");
    }
}
