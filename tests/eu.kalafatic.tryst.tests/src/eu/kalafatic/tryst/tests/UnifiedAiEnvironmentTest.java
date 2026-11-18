package eu.kalafatic.tryst.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.tryst.domain.ai.AgentProfileSupport;
import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.DefaultAiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.DefaultExecutionConfigurationService;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.harness.DefaultConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.HarnessAiDelegator;
import eu.kalafatic.tryst.domain.model.AgentConfiguration;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SecretCodec;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAiProviderRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionConfigurationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unified multi-harness / multi-provider / multi-agent control platform tests (Phase 9):
 * encrypted provider credentials, persisted provider registry, scope-based execution
 * configuration with immutable snapshots, agent-profile field round trip and per-task
 * harness resolution through the ExternalAiDelegator seam.
 */
class UnifiedAiEnvironmentTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SecretCodec codec;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("unified-ai-test.db"));
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();
        codec = new SecretCodec(tempDir);
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    private AiProviderRegistry newProviderRegistry() {
        return new DefaultAiProviderRegistry(
                new SqliteAiProviderRepository(databaseManager, codec),
                new AiModelRegistryService("codellama"));
    }

    private ExecutionConfigurationService newConfigService() {
        return new DefaultExecutionConfigurationService(
                new SqliteExecutionConfigurationRepository(databaseManager));
    }

    private Task task(String id) {
        return new Task(id, "project-p1", "session-s1", "Task " + id,
                "description", TaskStatus.CREATED, TaskPriority.NORMAL, null, null,
                null, null, 0, null, Instant.now(), null);
    }

    @Test
    void providerCredentialsEncryptedAtRestAndMaskedOnRead() throws Exception {
        AiProviderRegistry registry = newProviderRegistry();
        registry.registerProvider("cloud-1", "Cloud Provider", "OPENAI_COMPATIBLE",
                "https://api.example.com/v1", true, null);
        registry.updateConfiguration("cloud-1", Map.of(
                "apiKey", "sk-super-secret", "model", "gpt-x"));
        // masked view never exposes the secret
        Map<String, String> masked = registry.getConfiguration("cloud-1");
        assertEquals("****", masked.get("apiKey"));
        assertEquals("gpt-x", masked.get("model"));
        // at rest: the raw config_json in SQLite never contains the plaintext secret
        try (Statement statement = databaseManager.getConnection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT config_json FROM ai_providers WHERE id = 'cloud-1'")) {
            assertTrue(rs.next());
            String stored = rs.getString(1);
            assertFalse(stored.contains("sk-super-secret"),
                    "plaintext secret must not be persisted: " + stored);
        }
        // reload through a fresh registry instance: descriptor exists, still masked
        AiProviderRegistry reloaded = newProviderRegistry();
        assertTrue(reloaded.findProvider("cloud-1").isPresent());
        assertEquals("****", reloaded.getConfiguration("cloud-1").get("apiKey"));
    }

    @Test
    void providerLifecyclePersistsEnablementAndDefault() {
        AiProviderRegistry registry = newProviderRegistry();
        registry.registerProvider("ollama-default", "Ollama", "OLLAMA",
                "http://localhost:11434", true, null);
        registry.registerProvider("enr-default", "ENR", "ENR",
                "http://localhost:38281", false, null);
        assertEquals(2, registry.listProviders().size());
        registry.selectDefaultProvider("enr-default");
        assertEquals("enr-default", newProviderRegistry().getDefaultProviderId().orElseThrow());
        registry.setProviderEnabled("enr-default", true);
        assertTrue(newProviderRegistry().findProvider("enr-default").orElseThrow().enabled());
        assertThrows(IllegalArgumentException.class,
                () -> registry.setProviderEnabled("missing", true));
    }

    @Test
    void executionConfigurationResolvesScopesAndSnapshots() {
        ExecutionConfigurationService service = newConfigService();
        service.saveGlobal(new ExecutionConfiguration("dcode", "enr-default", "global-model",
                "", Map.of("timeoutSeconds", "1800"), List.of("read:workspace"),
                Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        service.saveProjectOverride("p1", new ExecutionConfiguration("", "ollama-default", "",
                "", Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_PROJECT));
        service.saveTaskOverride("t1", new ExecutionConfiguration("", "", "task-model",
                "agent-9", Map.of("timeoutSeconds", "600"), List.of("write:workspace"),
                Map.of(), Map.of(), ExecutionConfiguration.SOURCE_TASK));
        ExecutionConfiguration resolved = service.resolve("p1", "t1");
        assertEquals("dcode", resolved.harnessId());
        assertEquals("ollama-default", resolved.providerId());
        assertEquals("task-model", resolved.modelId());
        assertEquals("agent-9", resolved.agentProfileId());
        assertEquals("600", resolved.executionParameters().get("timeoutSeconds"));
        assertEquals(List.of("write:workspace"), resolved.toolPermissions());
        service.saveSnapshot("t1", resolved);
        ExecutionConfiguration snapshot = service.getSnapshot("t1").orElseThrow();
        assertEquals("dcode", snapshot.harnessId());
        assertEquals(ExecutionConfiguration.SOURCE_EXECUTION, snapshot.source());
        service.saveGlobal(new ExecutionConfiguration("opencode", "ollama-default", "other",
                "", Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        assertEquals("dcode", service.getSnapshot("t1").orElseThrow().harnessId());
    }

    @Test
    void agentProfileFieldsRoundTripThroughConfigurationJson() {
        AgentConfiguration original = new AgentConfiguration("agent-1", "Dev Agent",
                "model-x", "Be rigorous.", "{}", Instant.now(), Instant.now());
        String json = AgentProfileSupport.withProfileFields(original, "developer",
                "Autonomous developer", List.of("read:workspace", "write:workspace"),
                List.of("no-network"), "opencode", "ollama-default", "model-x",
                Map.of("timeoutSeconds", "900"));
        AgentConfiguration persisted = new AgentConfiguration("agent-1", "Dev Agent",
                "model-x", "Be rigorous.", json, Instant.now(), Instant.now());
        AgentProfileSupport.AgentProfileView view = AgentProfileSupport.view(persisted);
        assertEquals("developer", view.role());
        assertEquals("Autonomous developer", view.description());
        assertEquals(List.of("read:workspace", "write:workspace"), view.toolPermissions());
        assertEquals(List.of("no-network"), view.restrictions());
        assertEquals("opencode", view.preferredHarness());
        assertEquals("ollama-default", view.preferredProvider());
        assertEquals("model-x", view.preferredModel());
        assertEquals("900", view.executionParameters().get("timeoutSeconds"));
    }

    @Test
    void perTaskHarnessResolutionThroughDelegator() {
        ConnectedApplicationRegistry registry = new DefaultConnectedApplicationRegistry(null);
        CodingHarnessAdapter dcode = new FakeCodingHarnessAdapter("dcode", "DCode");
        CodingHarnessAdapter opencode = new FakeCodingHarnessAdapter("opencode", "OpenCode");
        registry.registerAdapter(dcode);
        registry.registerAdapter(opencode);
        registry.selectApplication("dcode");
        ExecutionConfigurationService configService = newConfigService();
        HarnessAiDelegator delegator = new HarnessAiDelegator(registry, configService);
        Task task = task("task-1");
        // no task override: the globally selected harness applies
        assertTrue(delegator.isAvailableForTask(task));
        // task-level override wins over the global selection
        configService.saveTaskOverride("task-1", new ExecutionConfiguration("opencode", "",
                "", "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_TASK));
        assertTrue(delegator.isAvailableForTask(task));
        // an explicitly configured but unavailable harness fails clearly instead of
        // silently falling back to another harness
        configService.saveTaskOverride("task-1", new ExecutionConfiguration("unknown-harness",
                "", "", "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_TASK));
        assertFalse(delegator.isAvailableForTask(task));
        eu.kalafatic.tryst.domain.ai.ExternalAiDelegator.DelegationResult result =
                delegator.delegateImplementation(task, workspace(), "instruction", "prompt",
                        line -> {});
        assertTrue(result.handled());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("unavailable"));
    }

    @Test
    void executionEnvironmentSessionsRoundTrip() {
        ExecutionConfigurationService service = newConfigService();
        service.saveSession("fast-code", new ExecutionConfiguration("opencode",
                "ollama-default", "codellama", "agent-1", Map.of(), List.of(), Map.of(),
                Map.of(), ExecutionConfiguration.SOURCE_SESSION));
        service.saveSession("deep-code", new ExecutionConfiguration("dcode",
                "enr-default", "deep-model", "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_SESSION));
        List<ExecutionConfigurationService.EnvironmentSession> sessions = service.listSessions();
        assertEquals(2, sessions.size());
        assertEquals("deep-code", sessions.get(0).name());
        assertEquals("fast-code", sessions.get(1).name());
        ExecutionConfiguration loaded = service.loadSession("fast-code").orElseThrow();
        assertEquals("opencode", loaded.harnessId());
        assertEquals("codellama", loaded.modelId());
        assertEquals(ExecutionConfiguration.SOURCE_SESSION, loaded.source());
        service.removeSession("fast-code");
        assertEquals(1, service.listSessions().size());
        assertTrue(service.loadSession("fast-code").isEmpty());
        // sessions do not leak into GLOBAL resolution
        assertTrue(service.getGlobal().isEmpty());
    }

    @Test
    void modelCatalogRemovalOperations() {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        catalog.registerProvider("ollama-default", "Ollama", "OLLAMA",
                "http://localhost:11434", true);
        catalog.publishModel(new AiModelRegistryService.PublishedModel(
                "ollama-default:codellama", "ollama-default", "codellama", "codellama",
                4096, true, true, eu.kalafatic.tryst.domain.model.UserRole.USER, Instant.now()));
        catalog.publishModel(new AiModelRegistryService.PublishedModel(
                "ollama-default:llama3", "ollama-default", "llama3", "llama3",
                8192, true, true, eu.kalafatic.tryst.domain.model.UserRole.USER, Instant.now()));
        assertEquals(2, catalog.getAllModels().size());
        catalog.removeModel("ollama-default:llama3");
        assertEquals(1, catalog.getAllModels().size());
        catalog.removeProvider("ollama-default");
        assertTrue(catalog.getAllModels().isEmpty());
        assertTrue(catalog.getAllProviders().isEmpty());
    }

    @Test
    void providerUnregisterCleansModelCatalog() throws Exception {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        AiProviderRegistry registry = new DefaultAiProviderRegistry(
                new SqliteAiProviderRepository(databaseManager, codec), catalog);
        registry.registerProvider("cloud-1", "Cloud", "OPENAI_COMPATIBLE",
                "https://api.example.com/v1", true, new FakeAiProvider("cloud-1"));
        registry.discoverModels("cloud-1").join();
        assertFalse(catalog.getAllModels().isEmpty());
        assertTrue(catalog.findProvider("cloud-1").isPresent());
        registry.unregisterProvider("cloud-1");
        assertTrue(catalog.getAllModels().isEmpty());
        assertTrue(catalog.findProvider("cloud-1").isEmpty());
        assertTrue(registry.findProvider("cloud-1").isEmpty());
    }

    private static final class FakeAiProvider implements eu.kalafatic.tryst.domain.ai.AIProvider {

        private final String providerId;

        FakeAiProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String getProviderId() {
            return providerId;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> checkConnection() {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    List.of(new ProviderModelMetadata("model-a", 0, "", "", "", "")));
        }

        @Override
        public java.util.concurrent.CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new InferenceResponse("ok", "stop", 1, 0, 1));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> streamResponse(InferenceRequest request,
                java.util.function.Consumer<String> chunkConsumer) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private Workspace workspace() {
        return new Workspace("ws-1", tempDir.resolve("workspace"));
    }

    private static final class FakeCodingHarnessAdapter implements CodingHarnessAdapter {

        private final String id;
        private final String name;

        FakeCodingHarnessAdapter(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String getApplicationId() {
            return id;
        }

        @Override
        public String getApplicationName() {
            return name;
        }

        @Override
        public String getApplicationType() {
            return "FAKE";
        }

        @Override
        public List<String> getCapabilities() {
            return List.of("CODE_IMPLEMENTATION");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public ConnectionTestResult testConnection() {
            return ConnectionTestResult.ok("available");
        }

        @Override
        public HarnessExecutionResult executeTask(Task task, Workspace workspace,
                String instructions, String prompt,
                java.util.function.Consumer<String> logConsumer,
                java.util.function.BooleanSupplier cancellationSupplier,
                java.time.Duration timeout) {
            return HarnessExecutionResult.ok("done", List.of());
        }
    }
}
