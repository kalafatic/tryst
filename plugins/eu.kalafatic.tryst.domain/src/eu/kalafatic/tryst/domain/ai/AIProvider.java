package eu.kalafatic.tryst.domain.ai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    CompletableFuture<Boolean> checkConnection();

    CompletableFuture<List<ProviderModelMetadata>> listAvailableModels();

    CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request);

    CompletableFuture<Void> streamResponse(InferenceRequest request, Consumer<String> chunkConsumer);
}
