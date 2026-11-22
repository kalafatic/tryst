package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;

import java.util.List;
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
}
