package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter.HarnessExecutionResult;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.evo.integration.service.DStudioCodingHarnessAdapter;
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
 * TRYST → DStudio TEIP 1.1 connector tests (Phase 4, WP-4.10). Wire-level tests against a
 * stub DStudio TEIP server; live cross-product verification runs in the Phase 4 end-to-end
 * suite against the real DStudio headless runtime.
 */
class DStudioCodingHarnessAdapterTest {

    @TempDir
    Path tempDir;

    private HttpServer stubServer;
    private int stubPort;
    private final AtomicReference<String> stubStatus = new AtomicReference<>("COMPLETED");
    private final AtomicBoolean cancelReceived = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        stubStatus.set("COMPLETED");
        cancelReceived.set(false);
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/teip/v1/health", exchange -> {
            respond(exchange, 200,
                    "{\"protocol\":\"TEIP\",\"version\":\"1.1\",\"status\":\"UP\","
                    + "\"application\":\"DSTUDIO\",\"ready\":true}");
        });
        stubServer.createContext("/teip/v1/executions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/teip/v1/executions")) {
                respond(exchange, 202,
                        "{\"protocol\":\"TEIP\",\"version\":\"1.1\","
                        + "\"executionId\":\"exec-stub-1\",\"status\":\"QUEUED\"}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        stubServer.createContext("/teip/v1/executions/exec-stub-1", exchange -> {
            respond(exchange, 200,
                    "{\"executionId\":\"exec-stub-1\",\"status\":\""
                    + stubStatus.get() + "\",\"progress\":1.0}");
        });
        stubServer.createContext("/teip/v1/executions/exec-stub-1/events", exchange -> {
            respond(exchange, 200,
                    "{\"executionId\":\"exec-stub-1\","
                    + "\"events\":[{\"eventId\":\"evt-1\",\"executionId\":\"exec-stub-1\","
                    + "\"sequence\":1,\"type\":\"EXECUTION_LOG\",\"data\":"
                    + "{\"message\":\"stub log line\"}}],"
                    + "\"hasMore\":false,\"nextSequence\":2}");
        });
        stubServer.createContext("/teip/v1/executions/exec-stub-1/result", exchange -> {
            respond(exchange, 200,
                    "{\"executionId\":\"exec-stub-1\",\"status\":\"COMPLETED\","
                    + "\"summary\":\"stub summary\",\"patch\":\"diff --git\","
                    + "\"modifiedFiles\":[\"src/Changed.java\"]}");
        });
        stubServer.createContext("/teip/v1/executions/exec-stub-1/cancel", exchange -> {
            cancelReceived.set(true);
            respond(exchange, 200,
                    "{\"executionId\":\"exec-stub-1\",\"status\":\"CANCELLED\","
                    + "\"message\":\"cancelled\"}");
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

    private DStudioCodingHarnessAdapter adapter() {
        DStudioCodingHarnessAdapter adapter = new DStudioCodingHarnessAdapter();
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
        DStudioCodingHarnessAdapter adapter = adapter();
        assertEquals("dstudio", adapter.getApplicationId());
        assertEquals("DSTUDIO", adapter.getApplicationType());
        assertTrue(adapter.getCapabilities().contains("CODE_IMPLEMENTATION"));
    }

    @Test
    void connectionTestAgainstStub() {
        DStudioCodingHarnessAdapter adapter = adapter();
        var result = adapter.testConnection();
        assertTrue(result.success(), result.message());
    }

    @Test
    void connectionTestFailsWhenUnreachable() {
        DStudioCodingHarnessAdapter adapter = new DStudioCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "endpoint", "http://127.0.0.1:1",
                "timeoutSeconds", "5"));
        assertFalse(adapter.testConnection().success());
    }

    @Test
    void executeTaskCompletesWithFilesAndStreamedLogs() {
        DStudioCodingHarnessAdapter adapter = adapter();
        List<String> logs = new ArrayList<>();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", logs::add, () -> false,
                Duration.ofSeconds(30));
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.modifiedFiles().size());
        assertEquals("src/Changed.java", result.modifiedFiles().get(0));
        assertTrue(result.summary().contains("stub summary"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("stub log line")));
    }

    @Test
    void failurePropagates() {
        stubStatus.set("FAILED");
        DStudioCodingHarnessAdapter adapter = adapter();
        HarnessExecutionResult result = adapter.executeTask(null, workspace(),
                "Do something", "Do something", l -> {}, () -> false,
                Duration.ofSeconds(30));
        assertFalse(result.success());
    }

    @Test
    void cancellationSendsCancelRequest() {
        stubStatus.set("RUNNING");
        DStudioCodingHarnessAdapter adapter = adapter();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelFlag.set(true);
            stubStatus.set("CANCELLED");
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
        DStudioCodingHarnessAdapter adapter = new DStudioCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of("authToken", "topsecret"));
        Map<String, String> config = adapter.getConnectionConfiguration();
        assertFalse(config.get("authToken").contains("topsecret"));
    }

    @Test
    void configurationPersistsAcrossAdapterInstances() throws Exception {
        var dbManager = new eu.kalafatic.tryst.infrastructure.persistence
                .SqliteDatabaseManager(tempDir.resolve("dstudio-cfg-test.db"));
        try {
            new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(
                    dbManager).migrate();
            var repository = new eu.kalafatic.tryst.infrastructure.persistence.repository
                    .SqliteDStudioConfigurationRepository(dbManager);
            DStudioCodingHarnessAdapter first = new DStudioCodingHarnessAdapter(repository);
            first.updateConnectionConfiguration(Map.of(
                    "endpoint", "http://127.0.0.1:" + stubPort,
                    "harness", "opencode",
                    "model", "ollama/test:latest",
                    "timeoutSeconds", "90",
                    "enabled", "true"));
            DStudioCodingHarnessAdapter second = new DStudioCodingHarnessAdapter(repository);
            Map<String, String> restored = second.getConnectionConfiguration();
            assertEquals("http://127.0.0.1:" + stubPort,
                    restored.get("endpoint"));
            assertEquals("opencode", restored.get("harness"));
            assertEquals("ollama/test:latest", restored.get("model"));
            assertEquals("90", restored.get("timeoutSeconds"));
            assertEquals("true", restored.get("enabled"));
            assertTrue(second.isAvailable(), "persisted endpoint must be usable");
        } finally {
            dbManager.close();
        }
    }

    @Test
    void disabledConfigurationIsUnavailable() {
        var dbManager = new eu.kalafatic.tryst.infrastructure.persistence
                .SqliteDatabaseManager(tempDir.resolve("dstudio-disabled-test.db"));
        try {
            new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(
                    dbManager).migrate();
            var repository = new eu.kalafatic.tryst.infrastructure.persistence.repository
                    .SqliteDStudioConfigurationRepository(dbManager);
            DStudioCodingHarnessAdapter adapter =
                    new DStudioCodingHarnessAdapter(repository);
            adapter.updateConnectionConfiguration(Map.of(
                    "endpoint", "http://127.0.0.1:" + stubPort,
                    "enabled", "false"));
            assertFalse(adapter.isAvailable());
            DStudioCodingHarnessAdapter second =
                    new DStudioCodingHarnessAdapter(repository);
            assertFalse(second.isAvailable(), "disabled state must persist");
        } finally {
            dbManager.close();
        }
    }

    @Test
    void unreachableEndpointIsReportedNotFatal() {
        var dbManager = new eu.kalafatic.tryst.infrastructure.persistence
                .SqliteDatabaseManager(tempDir.resolve("dstudio-unreach-test.db"));
        try {
            new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(
                    dbManager).migrate();
            var repository = new eu.kalafatic.tryst.infrastructure.persistence.repository
                    .SqliteDStudioConfigurationRepository(dbManager);
            DStudioCodingHarnessAdapter adapter =
                    new DStudioCodingHarnessAdapter(repository);
            adapter.updateConnectionConfiguration(Map.of(
                    "endpoint", "http://127.0.0.1:1",
                    "timeoutSeconds", "5"));
            var result = adapter.testConnection();
            assertFalse(result.success());
            assertTrue(result.message().contains("unreachable"));
        } finally {
            dbManager.close();
        }
    }
}
