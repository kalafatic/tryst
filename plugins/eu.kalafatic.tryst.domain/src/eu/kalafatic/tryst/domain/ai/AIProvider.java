package eu.kalafatic.tryst.domain.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Common AI provider communication abstraction separating transport and protocol
 * details from agent execution, planning, and task orchestration logic.
 */
public interface AIProvider {

    record ChatMessage(String role, String content) {
        public ChatMessage {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    record ProviderModelMetadata(
            String name,
            long sizeBytes,
            String modifiedAt,
            String format,
            String family,
            String parameterSize
    ) {}

    record InferenceRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            int maxTokens,
            Map<String, Object> extraParameters
    ) {
        public InferenceRequest(String model, List<ChatMessage> messages) {
            this(model, messages, 0.7, 2048, Map.of());
        }
    }

    record InferenceResponse(
            String content,
            String finishReason,
            long durationMs,
            int promptEvalCount,
            int evalCount
    ) {}

    String getProviderId();

    default String getEndpoint() {
        return "http://localhost:11434";
    }

    /**
     * Applies a configuration update (endpoint, credentials, defaults). Compatible
     * evolution of the provider contract: adapters that support runtime reconfiguration
     * override this; all other implementations keep the no-op default.
     */
    default void updateConfiguration(Map<String, String> configuration) {
    }

    /**
     * Returns the current connection configuration with sensitive values masked.
     */
    default Map<String, String> getConnectionConfiguration() {
        return Map.of();
    }

    CompletableFuture<Boolean> checkConnection();

    CompletableFuture<List<ProviderModelMetadata>> listAvailableModels();

    CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request);

    CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer);

    /**
     * Loads (pre-warms) a model into the inference backend's memory.
     * Providers that support preloading override this; the default reports the
     * operation as unsupported.
     */
    default CompletableFuture<Boolean> loadModel(String model) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Unloads a model from the inference backend's memory (releases resources).
     */
    default CompletableFuture<Boolean> unloadModel(String model) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Permanently removes a model from the inference backend's local storage.
     */
    default CompletableFuture<Boolean> deleteModel(String model) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Returns backend-specific model details (for example the Ollama
     * {@code /api/show} payload) or an empty optional when unsupported.
     */
    default CompletableFuture<Optional<String>> showModel(String model) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
