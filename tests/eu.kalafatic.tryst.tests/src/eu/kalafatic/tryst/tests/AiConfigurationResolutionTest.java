package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiExecutionResolver;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.DefaultAiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.DefaultExecutionConfigurationService;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ModelSelectionService;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAiProviderRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionConfigurationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI configuration resolution, persistence and execution-pipeline consistency tests
 * (single source of truth for harness/provider/endpoint/model):
 * <ul>
 *   <li>resolver precedence: explicit selection → provider default → explicit error
 *       (never a hidden model);</li>
 *   <li>exact model identifier preservation (no normalization);</li>
 *   <li>persistence across repository re-instantiation (restart semantics);</li>
 *   <li>the task execution pipeline uses exactly the resolved model;</li>
 *   <li>a task fails with a clear configuration error when no model is selected.</li>
 * </ul>
 */
public class AiConfigurationResolutionTest {

    private static final String QWEN = "hf.co/Qwen/Qwen3-14B-GGUF:Q4_K_M";

    private SqliteDatabaseManager databaseManager;
    private AiModelRegistryService modelCatalog;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("ai_config_test.db"));
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();
        modelCatalog = new AiModelRegistryService("");
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private DefaultExecutionConfigurationService executionConfigurationService() {
        return new DefaultExecutionConfigurationService(
                new SqliteExecutionConfigurationRepository(databaseManager));
    }

    private DefaultAiProviderRegistry providerRegistry() {
        return new DefaultAiProviderRegistry(
                new SqliteAiProviderRepository(databaseManager,
                        new eu.kalafatic.tryst.infrastructure.persistence.SecretCodec(tempDir)),
                modelCatalog);
    }

    private AiExecutionResolver resolver(DefaultExecutionConfigurationService exec,
                                         AiProviderRegistry providers) {
        return new AiExecutionResolver(exec, providers, modelCatalog);
    }

    @Test
    void testResolverPrefersExplicitUserSelection() {
        DefaultExecutionConfigurationService exec = executionConfigurationService();
        DefaultAiProviderRegistry providers = providerRegistry();
        providers.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);
        providers.updateDefaultModel("ollama-default", "other-model");
        exec.saveGlobal(new ExecutionConfiguration("dcode", "ollama-default", QWEN, "",
                Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));

        AiExecutionResolver.ResolvedExecution resolved = resolver(exec, providers).resolve(null, null);

        assertEquals(QWEN, resolved.modelId(), "the explicit selection must win verbatim");
        assertEquals("GLOBAL", resolved.source());
        assertEquals("ollama-default", resolved.providerId());
        assertEquals("OLLAMA", resolved.providerType());
        assertEquals("http://127.0.0.1:11434", resolved.endpoint());
        assertTrue(resolved.providerEnabled());
        assertTrue(resolved.hasModel());
    }

    @Test
    void testResolverProviderDefaultIsSecondLevel() {
        DefaultExecutionConfigurationService exec = executionConfigurationService();
        DefaultAiProviderRegistry providers = providerRegistry();
        providers.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);
        providers.updateDefaultModel("ollama-default", QWEN);
        // No global configuration at all.

        AiExecutionResolver.ResolvedExecution resolved = resolver(exec, providers).resolve(null, null);

        assertEquals(QWEN, resolved.modelId());
        assertEquals(AiExecutionResolver.SOURCE_PROVIDER_DEFAULT, resolved.source());
    }

    @Test
    void testResolverNoImplicitFallback() {
        DefaultExecutionConfigurationService exec = executionConfigurationService();
        DefaultAiProviderRegistry providers = providerRegistry();
        providers.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);

        AiExecutionResolver.ResolvedExecution resolved = resolver(exec, providers).resolve(null, null);

        assertEquals("", resolved.modelId(), "no model must ever be silently substituted");
        assertEquals(AiExecutionResolver.SOURCE_NONE, resolved.source());
        assertFalse(resolved.hasModel());
    }

    @Test
    void testResolverPreservesExactIdentifierAndCatalogMatching() {
        DefaultExecutionConfigurationService exec = executionConfigurationService();
        DefaultAiProviderRegistry providers = providerRegistry();
        providers.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);
        exec.saveGlobal(new ExecutionConfiguration("", "ollama-default", QWEN, "",
                Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));

        modelCatalog.publishModel(new AiModelRegistryService.PublishedModel(
                "ollama-default:" + QWEN, "ollama-default", QWEN, QWEN,
                4096, true, true, eu.kalafatic.tryst.domain.model.UserRole.USER,
                java.time.Instant.now()));

        AiExecutionResolver resolver = resolver(exec, providers);
        assertEquals(QWEN, resolver.resolve(null, null).modelId());
        assertTrue(resolver.resolve(null, null).modelAvailable());
        assertTrue(resolver.isModelKnownForProvider("ollama-default", QWEN),
                "exact identifier must match the catalog");
        assertFalse(resolver.isModelKnownForProvider("ollama-default", "qwen3"),
                "normalized identifiers must not be treated as matches");
        assertFalse(resolver.isModelKnownForProvider("other-provider", QWEN),
                "models are provider-scoped");
    }

    @Test
    void testGlobalConfigurationPersistsAcrossRestart() {
        DefaultExecutionConfigurationService first = executionConfigurationService();
        first.saveGlobal(new ExecutionConfiguration("dcode", "ollama-default", QWEN, "agent-1",
                Map.of("temperature", "0.2"), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_GLOBAL));

        // Simulated restart: a fresh service instance over the same database.
        DefaultExecutionConfigurationService second = executionConfigurationService();
        Optional<ExecutionConfiguration> restored = second.getGlobal();
        assertTrue(restored.isPresent(), "global configuration must survive restart");
        assertEquals("dcode", restored.get().harnessId());
        assertEquals("ollama-default", restored.get().providerId());
        assertEquals(QWEN, restored.get().modelId(), "the exact model identifier must survive");
        assertEquals("agent-1", restored.get().agentProfileId());
    }

    @Test
    void testProviderDefaultModelPersistsAcrossRestart() {
        DefaultAiProviderRegistry first = providerRegistry();
        first.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);
        first.updateDefaultModel("ollama-default", QWEN);

        DefaultAiProviderRegistry second = providerRegistry();
        Optional<eu.kalafatic.tryst.domain.ai.AiProviderDescriptor> restored =
                second.findProvider("ollama-default");
        assertTrue(restored.isPresent());
        assertEquals(QWEN, restored.get().defaultModel(),
                "provider default model must survive restart with the exact identifier");
    }

    @Test
    void testModelSelectionServiceHasNoImplicitFallback() {
        DefaultExecutionConfigurationService exec = executionConfigurationService();
        DefaultAiProviderRegistry providers = providerRegistry();
        providers.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA",
                "http://127.0.0.1:11434", true, null);

        ModelSelectionService selector =
                new ModelSelectionService(modelCatalog, exec, providers);
        Task task = new Task("t1", null, null, "Task", "Description",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0,
                null, java.time.Instant.now(), null);

        // Nothing configured: explicit error, never a hardcoded model.
        assertEquals("", selector.selectModel(task, null,
                eu.kalafatic.tryst.domain.model.UserRole.USER));

        // Explicit request passes through verbatim.
        assertEquals(QWEN, selector.selectModel(task, QWEN,
                eu.kalafatic.tryst.domain.model.UserRole.USER));

        // Persisted global configuration wins.
        exec.saveGlobal(new ExecutionConfiguration("", "ollama-default", QWEN, "",
                Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        assertEquals(QWEN, selector.selectModel(task, null,
                eu.kalafatic.tryst.domain.model.UserRole.USER));
    }

    @Test
    void testTaskExecutionUsesExactlyTheResolvedModel(@TempDir Path tempDir) throws Exception {
        SqliteDatabaseManager db = new SqliteDatabaseManager(tempDir.resolve("exec_model.db"));
        db.initialize();
        new SchemaMigrationService(db).migrate();
        try {
            SqliteProjectRepository projectRepository = new SqliteProjectRepository(db);
            SqliteTaskRepository taskRepository = new SqliteTaskRepository(db);
            EventBus eventBus = new EventBus();
            TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, eventBus);
            TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

            // Capturing inference service: records every model identifier actually sent.
            List<String> requestedModels = new CopyOnWriteArrayList<>();
            eu.kalafatic.tryst.domain.ai.AiInferenceService capturingAi =
                    new eu.kalafatic.tryst.domain.ai.AiInferenceService() {
                        @Override
                        public java.util.concurrent.CompletableFuture<Boolean> checkConnection() {
                            return java.util.concurrent.CompletableFuture.completedFuture(true);
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<List<ModelMetadata>> listModels() {
                            return java.util.concurrent.CompletableFuture.completedFuture(List.of());
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<InferenceResponse> generateResponse(
                                InferenceRequest req) {
                            requestedModels.add(req.model());
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    new InferenceResponse(TestAiFakes.FAKE_FILE_DIRECTIVE, "stop", 5, 1, 1));
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<Void> streamResponse(
                                InferenceRequest req, java.util.function.Consumer<String> chunkConsumer) {
                            requestedModels.add(req.model());
                            if (chunkConsumer != null) {
                                chunkConsumer.accept(TestAiFakes.FAKE_FILE_DIRECTIVE);
                            }
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                    };

            TaskOrchestrator orchestrator = new TaskOrchestrator(
                    taskQueue, taskRepository, lifecycleManager, null, projectRepository,
                    null, null, capturingAi, null, null, null, null, null, 1);
            TestAiFakes.configureModelSelection(orchestrator, QWEN);
            orchestrator.setEventBus(eventBus);
            orchestrator.start();
            try {
                Task task = orchestrator.submitTask(null, null, "Model Propagation", "Verify model",
                        TaskPriority.NORMAL, "dev-user", null);
                assertNotNull(task);

                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    Task current = taskRepository.findTaskById(task.getId()).orElse(null);
                    if (current != null && (current.getStatus() == TaskStatus.WAITING_FOR_REVIEW
                            || current.getStatus() == TaskStatus.COMPLETED
                            || current.getStatus() == TaskStatus.FAILED)) {
                        break;
                    }
                    Thread.sleep(100);
                }
                assertFalse(requestedModels.isEmpty(), "the task pipeline must issue inference requests");
                for (String used : requestedModels) {
                    assertEquals(QWEN, used, "every inference request must use the resolved model verbatim");
                }
                assertFalse(requestedModels.contains("codellama"), "no hardcoded model may ever be substituted");
            } finally {
                orchestrator.shutdown();
            }
        } finally {
            db.close();
        }
    }

    @Test
    void testTaskFailsWithClearErrorWhenNoModelSelected(@TempDir Path tempDir) throws Exception {
        SqliteDatabaseManager db = new SqliteDatabaseManager(tempDir.resolve("no_model.db"));
        db.initialize();
        new SchemaMigrationService(db).migrate();
        try {
            SqliteProjectRepository projectRepository = new SqliteProjectRepository(db);
            SqliteTaskRepository taskRepository = new SqliteTaskRepository(db);
            EventBus eventBus = new EventBus();
            TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, eventBus);
            TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);
            AtomicReference<String> failureReason = new AtomicReference<>();

            TaskOrchestrator orchestrator = new TaskOrchestrator(
                    taskQueue, taskRepository, lifecycleManager, null, projectRepository,
                    null, null, TestAiFakes.codingAi(), null, null, null, null, null, 1);
            // Deliberately NO model configuration: the task must fail with an explicit
            // configuration error, never silently pick a model.
            orchestrator.setEventBus(eventBus);
            orchestrator.start();
            try {
                Task task = orchestrator.submitTask(null, null, "No Model", "Must fail cleanly",
                        TaskPriority.NORMAL, "dev-user", null);
                long deadline = System.currentTimeMillis() + 10000;
                Task terminal = null;
                while (System.currentTimeMillis() < deadline) {
                    Task current = taskRepository.findTaskById(task.getId()).orElse(null);
                    if (current != null && current.getStatus().isTerminal()) {
                        terminal = current;
                        break;
                    }
                    Thread.sleep(100);
                }
                assertNotNull(terminal, "task must reach a terminal state");
                assertEquals(TaskStatus.FAILED, terminal.getStatus());
                assertTrue(terminal.getFailureReason() != null
                                && terminal.getFailureReason().toLowerCase().contains("no ai model"),
                        "failure reason must name the missing model: " + terminal.getFailureReason());
            } finally {
                orchestrator.shutdown();
            }
        } finally {
            db.close();
        }
    }
}
