package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter.HarnessExecutionResult;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.infrastructure.dcode.DCodeCodingHarnessAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRYST → DCode harness adapter wire tests (Phase 6, WP-6.6). Stub DCode /api/v1 server;
 * live cross-product verification runs against the real DCode headless runtime.
 */
class DCodeCodingHarnessAdapterTest {

    @TempDir
    Path tempDir;

    private HttpServer stubServer;
    private int stubPort;
    private final AtomicReference<String> stubState = new AtomicReference<>("COMPLETED");
    private final AtomicBoolean cancelReceived = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        stubState.set("COMPLETED");
        cancelReceived.set(false);
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/health", exchange -> respond(exchange, 200,
                "{\"application\":\"DCODE\",\"status\":\"UP\"}"));
        stubServer.createContext("/api/v1/workspaces", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/workspaces")) {
                respond(exchange, 200, "{\"id\":\"ws-stub-1\"}");
                return;
            }
            if (path.equals("/api/v1/workspaces/ws-stub-1/repositories")) {
                respond(exchange, 200, "{\"id\":\"ws-stub-1\"}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        stubServer.createContext("/api/v1/sessions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/sessions")) {
                respond(exchange, 200, "{\"id\":\"sess-stub-1\",\"state\":\"CREATED\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/start")) {
                respond(exchange, 200, "{\"status\":\"accepted\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/cancel")) {
                cancelReceived.set(true);
                respond(exchange, 200, "{\"status\":\"accepted\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1")) {
                respond(exchange, 200, "{\"id\":\"sess-stub-1\",\"state\":\""
                        + stubState.get() + "\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/messages")) {
                respond(exchange, 200,
                        "[{\"role\":\"user\",\"content\":\"task\"},"
                        + "{\"role\":\"assistant\",\"content\":\"stub summary\"}]");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/changes")) {
                respond(exchange, 200,
                        "{\"repositories\":[{\"name\":\"repo\","
                        + "\"changedFiles\":[\"src/Changed.java\"]}]}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        stubServer.stop(0);
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

    private DCodeCodingHarnessAdapter adapter() {
        DCodeCodingHarnessAdapter adapter = new DCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "endpoint", "http://127.0.0.1:" + stubPort,
                "timeoutSeconds", "60"));
        return adapter;
    }

    private Workspace workspace() {
        return new Workspace("ws-1", tempDir);
    }

    @Test
    void identityAndCapabilities() {
        DCodeCodingHarnessAdapter adapter = adapter();
        assertEquals("dcode", adapter.getApplicationId());
        assertEquals("DCODE", adapter.getApplicationType());
        assertTrue(adapter.getCapabilities().contains("CODE_IMPLEMENTATION"));
        assertTrue(adapter.getCapabilities().contains("CODE_ANALYSIS"));
    }

    @Test
    void connectionTestAgainstStub() {
        DCodeCodingHarnessAdapter adapter = adapter();
        assertTrue(adapter.testConnection().success(), adapter.testConnection().message());
    }

    @Test
    void connectionTestFailsWhenUnreachable() {
        DCodeCodingHarnessAdapter adapter = new DCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "endpoint", "http://127.0.0.1:1",
                "timeoutSeconds", "5"));
        assertFalse(adapter.testConnection().success());
    }

    @Test
    void executeTaskCompletesWithFilesAndLogs() {
        DCodeCodingHarnessAdapter adapter = adapter();
        List<String> logs = new ArrayList<>();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", logs::add, () -> false,
                Duration.ofSeconds(30));
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.modifiedFiles().size());
        assertEquals("src/Changed.java", result.modifiedFiles().get(0));
        assertTrue(result.summary().contains("stub summary"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("stub summary")));
    }

    @Test
    void waitingForApprovalIsReportedAsCompletedWithPendingReview() {
        stubState.set("WAITING_FOR_APPROVAL");
        DCodeCodingHarnessAdapter adapter = adapter();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", l -> {}, () -> false,
                Duration.ofSeconds(30));
        assertTrue(result.success());
        assertTrue(result.summary().contains("pending review"));
    }

    @Test
    void failurePropagates() {
        stubState.set("FAILED");
        DCodeCodingHarnessAdapter adapter = adapter();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", l -> {}, () -> false,
                Duration.ofSeconds(30));
        assertFalse(result.success());
    }

    @Test
    void cancellationSendsCancelRequest() {
        stubState.set("RUNNING");
        DCodeCodingHarnessAdapter adapter = adapter();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelFlag.set(true);
            stubState.set("CANCELLED");
        });
        canceller.setDaemon(true);
        canceller.start();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", l -> {}, cancelFlag::get,
                Duration.ofSeconds(30));
        assertTrue(result.cancelled());
        assertTrue(cancelReceived.get());
    }

    @Test
    void configurationMasksToken() {
        DCodeCodingHarnessAdapter adapter = new DCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of("authToken", "topsecret"));
        Map<String, String> config = adapter.getConnectionConfiguration();
        assertFalse(config.get("authToken").contains("topsecret"));
    }

    @Test
    void configurationPersistsAcrossAdapterInstances() throws Exception {
        var dbManager = new eu.kalafatic.tryst.infrastructure.persistence
                .SqliteDatabaseManager(tempDir.resolve("dcode-cfg-test.db"));
        try {
            new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(
                    dbManager).migrate();
            var repository = new eu.kalafatic.tryst.infrastructure.persistence.repository
                    .SqliteDCodeConfigurationRepository(dbManager);
            DCodeCodingHarnessAdapter first = new DCodeCodingHarnessAdapter(repository);
            first.updateConnectionConfiguration(Map.of(
                    "endpoint", "http://127.0.0.1:" + stubPort,
                    "providerId", "ollama",
                    "model", "qwen3:latest",
                    "timeoutSeconds", "90",
                    "enabled", "true"));
            DCodeCodingHarnessAdapter second = new DCodeCodingHarnessAdapter(repository);
            Map<String, String> restored = second.getConnectionConfiguration();
            assertEquals("http://127.0.0.1:" + stubPort,
                    restored.get("endpoint"));
            assertEquals("ollama", restored.get("providerId"));
            assertEquals("qwen3:latest", restored.get("model"));
            assertEquals("90", restored.get("timeoutSeconds"));
            assertEquals("true", restored.get("enabled"));
        } finally {
            dbManager.close();
        }
    }

    @Test
    void disabledConfigurationIsUnavailable() {
        DCodeCodingHarnessAdapter adapter = new DCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "endpoint", "http://127.0.0.1:" + stubPort,
                "enabled", "false"));
        assertFalse(adapter.isAvailable());
    }
}
