package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.harness.DefaultConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConnectedApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ConnectedApplicationRegistryTest {

    private ConnectedApplicationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultConnectedApplicationRegistry(null);
    }

    @Test
    void testRegisterListAndSelection() {
        StubHarnessAdapter evo = new StubHarnessAdapter("evo", "EVO Darwin Engine", "EVO", true);
        StubHarnessAdapter opencode = new StubHarnessAdapter("opencode", "OpenCode CLI", "OPENCODE", true);

        registry.registerAdapter(evo);
        registry.registerAdapter(opencode);

        assertEquals(2, registry.listApplications().size());
        assertTrue(registry.findApplication("opencode").isPresent());
        assertEquals("OPENCODE", registry.findApplication("opencode").get().getApplicationType());

        registry.selectApplication("opencode");
        assertEquals(Optional.of("opencode"), registry.getSelectedApplicationId());
        assertTrue(registry.findSelectedAdapter().isPresent());
        assertEquals("OpenCode CLI", registry.findSelectedAdapter().get().getApplicationName());
    }

    @Test
    void testSelectionAndAvailabilityGating() {
        StubHarnessAdapter evo = new StubHarnessAdapter("evo", "EVO", "EVO", false);
        registry.registerAdapter(evo);
        registry.selectApplication("evo");
        assertTrue(registry.findSelectedAdapter().isEmpty());

        // Enabling does not change availability when the adapter itself is unavailable
        registry.setApplicationEnabled("evo", true);
        assertTrue(registry.findSelectedAdapter().isEmpty());

        // Disabling an available adapter gates it as well
        StubHarnessAdapter opencode = new StubHarnessAdapter("opencode", "OpenCode", "OPENCODE", true);
        registry.registerAdapter(opencode);
        registry.selectApplication("opencode");
        assertTrue(registry.findSelectedAdapter().isPresent());
        registry.setApplicationEnabled("opencode", false);
        assertTrue(registry.findSelectedAdapter().isEmpty());
    }

    @Test
    void testConnectionTestUpdatesStatus() {
        StubHarnessAdapter evo = new StubHarnessAdapter("evo", "EVO", "EVO", true);
        registry.registerAdapter(evo);

        var result = registry.testConnection("evo");
        assertTrue(result.success());
        assertEquals(ConnectedApplicationDescriptor.ApplicationStatus.CONNECTED,
                registry.findApplication("evo").get().getStatus());

        StubHarnessAdapter broken = new StubHarnessAdapter("broken", "Broken", "UNKNOWN", true, false);
        registry.registerAdapter(broken);
        var brokenResult = registry.testConnection("broken");
        assertFalse(brokenResult.success());
        assertEquals(ConnectedApplicationDescriptor.ApplicationStatus.DISCONNECTED,
                registry.findApplication("broken").get().getStatus());
    }

    @Test
    void testUnregisterAndClear() {
        StubHarnessAdapter evo = new StubHarnessAdapter("evo", "EVO", "EVO", true);
        registry.registerAdapter(evo);
        registry.selectApplication("evo");

        registry.unregisterApplication("evo");
        assertTrue(registry.findApplication("evo").isEmpty());
        assertTrue(registry.getSelectedApplicationId().isEmpty());

        registry.registerAdapter(evo);
        registry.clearAll();
        assertTrue(registry.listApplications().isEmpty());
    }

    @Test
    void testConfigurationUpdate() {
        StubHarnessAdapter opencode = new StubHarnessAdapter("opencode", "OpenCode", "OPENCODE", true);
        registry.registerAdapter(opencode);
        registry.updateConfiguration("opencode", Map.of("model", "qwen3-coder-30b-32k:latest", "agent", "build"));
        assertEquals("qwen3-coder-30b-32k:latest", opencode.receivedConfig.get("model"));
        assertEquals("build", opencode.receivedConfig.get("agent"));
    }

    @Test
    void testPersistenceRoundTrip(@TempDir Path tempDir) throws Exception {
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(tempDir.resolve("registry_test.db"))) {
            dbManager.initialize();
            new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(dbManager).migrate();

            var repository = new SqliteConnectedApplicationRepository(dbManager);
            ConnectedApplicationRegistry persistentRegistry = new DefaultConnectedApplicationRegistry(repository);
            StubHarnessAdapter opencode = new StubHarnessAdapter("opencode", "OpenCode CLI", "OPENCODE", true);
            persistentRegistry.registerAdapter(opencode);
            persistentRegistry.selectApplication("opencode");
            persistentRegistry.testConnection("opencode");

            // Second registry instance hydrates the persisted descriptors and selection
            ConnectedApplicationRegistry hydrated = new DefaultConnectedApplicationRegistry(repository);
            assertEquals(1, hydrated.listApplications().size());
            ConnectedApplicationDescriptor descriptor = hydrated.findApplication("opencode").orElseThrow();
            assertEquals("OpenCode CLI", descriptor.getName());
            assertEquals("OPENCODE", descriptor.getApplicationType());
            assertEquals(ConnectedApplicationDescriptor.ApplicationStatus.CONNECTED, descriptor.getStatus());
            assertEquals(Optional.of("opencode"), hydrated.getSelectedApplicationId());
        }
    }

    private static class StubHarnessAdapter implements CodingHarnessAdapter {
        private final String id;
        private final String name;
        private final String type;
        private final boolean available;
        private final boolean connectionOk;
        private final Map<String, String> receivedConfig = new java.util.HashMap<>();

        StubHarnessAdapter(String id, String name, String type, boolean available) {
            this(id, name, type, available, true);
        }

        StubHarnessAdapter(String id, String name, String type, boolean available, boolean connectionOk) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.available = available;
            this.connectionOk = connectionOk;
        }

        @Override public String getApplicationId() { return id; }
        @Override public String getApplicationName() { return name; }
        @Override public String getApplicationType() { return type; }
        @Override public List<String> getCapabilities() { return List.of("CODE_IMPLEMENTATION"); }
        @Override public boolean isAvailable() { return available; }
        @Override public ConnectionTestResult testConnection() {
            return connectionOk ? ConnectionTestResult.ok("ok") : ConnectionTestResult.error("unreachable");
        }
        @Override public void updateConnectionConfiguration(Map<String, String> configuration) {
            receivedConfig.putAll(configuration);
        }
        @Override public HarnessExecutionResult executeTask(Task task, Workspace workspace, String instructions,
                String prompt, Consumer<String> logConsumer, BooleanSupplier cancellationSupplier, Duration timeout) {
            return HarnessExecutionResult.ok("stub executed", List.of());
        }
    }
}
