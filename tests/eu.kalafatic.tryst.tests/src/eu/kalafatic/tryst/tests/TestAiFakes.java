package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test fixture providing a deterministic, always-available fake inference service for
 * orchestration/flow tests. It returns a valid markdown file-block directive so the
 * implementation stage succeeds without any real model. Tests that assert the HONEST
 * failure behavior when no AI is available must NOT use this fixture.
 */
public final class TestAiFakes {

    private TestAiFakes() {
    }

    public static final String FAKE_FILE_DIRECTIVE =
            "file: SampleService.java\n```\npublic class SampleService {\n    public int add(int a, int b) {\n        return a + b;\n    }\n}\n```";

    public static AiInferenceService codingAi() {
        return new AiInferenceService() {
            @Override
            public CompletableFuture<Boolean> checkConnection() {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<List<ModelMetadata>> listModels() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest req) {
                return CompletableFuture.completedFuture(
                        new InferenceResponse(FAKE_FILE_DIRECTIVE, "stop", 5, 1, 1));
            }

            @Override
            public CompletableFuture<Void> streamResponse(InferenceRequest req, java.util.function.Consumer<String> chunkConsumer) {
                if (chunkConsumer != null) {
                    chunkConsumer.accept(FAKE_FILE_DIRECTIVE);
                }
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    /**
     * Wires the single-source-of-truth model configuration into an orchestrator under
     * test: a global execution configuration selecting {@code modelId} plus a matching
     * provider registry. Mirrors the production wiring in
     * {@code ApplicationLifecycleCoordinator}.
     */
    public static void configureModelSelection(TaskOrchestrator orchestrator, String modelId) {
        orchestrator.setExecutionConfigurationService(new StaticExecutionConfigurationService(modelId));
        orchestrator.setAiProviderRegistry(new StaticProviderRegistry(modelId));
    }

    /** In-memory global execution configuration with a fixed model. */
    public static final class StaticExecutionConfigurationService implements ExecutionConfigurationService {
        private ExecutionConfiguration global;

        public StaticExecutionConfigurationService(String modelId) {
            this.global = new ExecutionConfiguration("", "", modelId, "", Map.of(), List.of(),
                    Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL);
        }

        @Override
        public void saveGlobal(ExecutionConfiguration configuration) {
            this.global = configuration;
        }

        @Override
        public void saveProjectOverride(String projectId, ExecutionConfiguration configuration) {}

        @Override
        public void saveTaskOverride(String taskId, ExecutionConfiguration configuration) {}

        @Override
        public void removeTaskOverride(String taskId) {}

        @Override
        public Optional<ExecutionConfiguration> getGlobal() {
            return Optional.ofNullable(global);
        }

        @Override
        public Optional<ExecutionConfiguration> getProjectOverride(String projectId) {
            return Optional.empty();
        }

        @Override
        public Optional<ExecutionConfiguration> getTaskOverride(String taskId) {
            return Optional.empty();
        }

        @Override
        public ExecutionConfiguration resolve(String projectId, String taskId) {
            return global;
        }

        @Override
        public void saveSnapshot(String taskId, ExecutionConfiguration resolved) {}

        @Override
        public Optional<ExecutionConfiguration> getSnapshot(String taskId) {
            return Optional.empty();
        }

        @Override
        public void saveSession(String name, ExecutionConfiguration configuration) {}

        @Override
        public Optional<ExecutionConfiguration> loadSession(String name) {
            return Optional.empty();
        }

        @Override
        public void removeSession(String name) {}

        @Override
        public List<EnvironmentSession> listSessions() {
            return List.of();
        }
    }

    /** In-memory provider registry with a single enabled provider. */
    public static final class StaticProviderRegistry implements AiProviderRegistry {
        private final AiProviderDescriptor descriptor;

        public StaticProviderRegistry(String defaultModel) {
            this.descriptor = new AiProviderDescriptor("ollama-default", "Default Ollama Endpoint",
                    "OLLAMA", "http://localhost:11434", true,
                    defaultModel == null ? "" : defaultModel, Map.of(), Instant.now(), Instant.now());
        }

        @Override
        public void registerProvider(String id, String name, String type, String endpoint,
                                     boolean enabled, AIProvider adapter) {}

        @Override
        public void unregisterProvider(String id) {}

        @Override
        public List<AiProviderDescriptor> listProviders() {
            return List.of(descriptor);
        }

        @Override
        public Optional<AiProviderDescriptor> findProvider(String id) {
            return descriptor.id().equals(id) ? Optional.of(descriptor) : Optional.empty();
        }

        @Override
        public Optional<AIProvider> findAdapter(String id) {
            return Optional.empty();
        }

        @Override
        public void setProviderEnabled(String id, boolean enabled) {}

        @Override
        public void selectDefaultProvider(String id) {}

        @Override
        public void updateDefaultModel(String id, String modelId) {}

        @Override
        public Optional<String> getDefaultProviderId() {
            return Optional.of(descriptor.id());
        }

        @Override
        public Optional<AIProvider> getDefaultAdapter() {
            return Optional.empty();
        }

        @Override
        public void updateConfiguration(String id, Map<String, String> configuration) {}

        @Override
        public Map<String, String> getConfiguration(String id) {
            return Map.of();
        }

        @Override
        public ConnectionStatus testConnection(String id) {
            return ConnectionStatus.ok("connected");
        }

        @Override
        public CompletableFuture<Void> discoverModels(String id) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> ensureInitialized() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> refreshAll() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
