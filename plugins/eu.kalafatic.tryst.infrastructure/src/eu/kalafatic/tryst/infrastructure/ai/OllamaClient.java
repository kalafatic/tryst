package eu.kalafatic.tryst.infrastructure.ai;

import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Real Ollama HTTP client implementation using Java 21 java.net.http.HttpClient.
 * Handles tags discovery, model inspection, generation, and streaming chat.
 * Implements legacy AiInferenceService and exposes AIProvider.
 */
public class OllamaClient implements AiInferenceService {

    private final OllamaProvider provider;

    public OllamaClient(String endpoint, Duration timeout) {
        this.provider = new OllamaProvider("ollama-client", endpoint, timeout);
    }

    public OllamaClient(String endpoint) {
        this(endpoint, Duration.ofSeconds(60));
    }

    public String getEndpoint() {
        return provider.getEndpoint();
    }

    public String getProviderId() {
        return provider.getProviderId();
    }

    public OllamaProvider getProvider() {
        return provider;
    }

    @Override
    public CompletableFuture<Boolean> checkConnection() {
        return provider.checkConnection();
    }

    @Override
    public CompletableFuture<List<AiInferenceService.ModelMetadata>> listModels() {
        return provider.listAvailableModels().thenApply(list ->
                list.stream().map(m -> new AiInferenceService.ModelMetadata(
                        m.name(), m.sizeBytes(), m.modifiedAt(), m.format(), m.family(), m.parameterSize()
                )).toList());
    }

    @Override
    public CompletableFuture<AiInferenceService.InferenceResponse> generateResponse(AiInferenceService.InferenceRequest request) {
        List<AIProvider.ChatMessage> msgs = request.messages().stream()
                .map(m -> new AIProvider.ChatMessage(m.role(), m.content()))
                .toList();
        AIProvider.InferenceRequest provReq =
                new AIProvider.InferenceRequest(request.model(), msgs, request.temperature(), request.maxTokens(), request.extraParameters());

        return provider.generateResponse(provReq).thenApply(res ->
                new AiInferenceService.InferenceResponse(res.content(), res.finishReason(), res.durationMs(), res.promptEvalCount(), res.evalCount()));
    }

    @Override
    public CompletableFuture<Void> streamResponse(AiInferenceService.InferenceRequest request, Consumer<String> chunkConsumer) {
        List<AIProvider.ChatMessage> msgs = request.messages().stream()
                .map(m -> new AIProvider.ChatMessage(m.role(), m.content()))
                .toList();
        AIProvider.InferenceRequest provReq =
                new AIProvider.InferenceRequest(request.model(), msgs, request.temperature(), request.maxTokens(), request.extraParameters());

        return provider.streamResponse(provReq, chunkConsumer);
    }
}
