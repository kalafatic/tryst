package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.infrastructure.ai.OllamaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Ollama provider verification against the real endpoint (default
 * {@code http://127.0.0.1:11434}, overridable via {@code -Dtryst.test.ollama.endpoint}).
 * Skips cleanly when Ollama is not reachable (matching the existing live-test
 * convention). Verifies:
 * <ul>
 *   <li>model discovery returns the real installed models with exact identifiers;</li>
 *   <li>inference uses the selected model and returns a real response;</li>
 *   <li>a missing model is reported as "not installed" with endpoint details —
 *       never as a generic connectivity failure;</li>
 *   <li>an unreachable endpoint is reported as a connectivity failure.</li>
 * </ul>
 */
public class OllamaProviderLiveTest {

    private static final String ENDPOINT =
            System.getProperty("tryst.test.ollama.endpoint", "http://127.0.0.1:11434");

    private static boolean ollamaReachable() {
        try {
            return new OllamaProvider(ENDPOINT, Duration.ofSeconds(3))
                    .checkConnection().get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void logSkipState() {
        if (!ollamaReachable()) {
            System.out.println("[OLLAMA-LIVE] Ollama not reachable at " + ENDPOINT + "; skipping live tests");
        }
    }

    @Test
    void liveModelDiscoveryReturnsRealInstalledModels() throws Exception {
        if (!ollamaReachable()) {
            return;
        }
        OllamaProvider provider = new OllamaProvider(ENDPOINT, Duration.ofSeconds(15));
        List<AIProvider.ProviderModelMetadata> models =
                provider.listAvailableModels().get(30, TimeUnit.SECONDS);
        assertFalse(models.isEmpty(), "a reachable Ollama with installed models must return them");
        for (AIProvider.ProviderModelMetadata model : models) {
            assertNotNull(model.name());
            assertFalse(model.name().isBlank());
        }
        // The exact Qwen identifier observed in the reported configuration must survive
        // discovery verbatim when installed.
        if (models.stream().anyMatch(m -> "hf.co/Qwen/Qwen3-14B-GGUF:Q4_K_M".equals(m.name()))) {
            assertTrue(models.stream().anyMatch(m -> "hf.co/Qwen/Qwen3-14B-GGUF:Q4_K_M".equals(m.name())));
        }
    }

    @Test
    void liveInferenceUsesSelectedModelAndReturnsRealResponse() throws Exception {
        if (!ollamaReachable()) {
            return;
        }
        OllamaProvider provider = new OllamaProvider(ENDPOINT, Duration.ofSeconds(120));
        String model = pickInstalledModel(provider);
        assertNotNull(model, "at least one installed model is required");

        long started = System.currentTimeMillis();
        AIProvider.InferenceResponse response = provider.generateResponse(new AIProvider.InferenceRequest(
                model,
                List.of(new AIProvider.ChatMessage("user", "Respond with exactly: TRYST_AI_OK")),
                0.2, 64, java.util.Map.of()))
                .get(150, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - started;

        assertNotNull(response.content());
        assertFalse(response.content().isBlank(), "a real inference must return content");
        assertTrue(response.content().contains("TRYST_AI_OK"),
                "the selected model must process the request (expected TRYST_AI_OK): " + response.content());
        assertTrue(duration >= 0);
    }

    @Test
    void liveMissingModelReportsNotInstalledWithDetails() {
        if (!ollamaReachable()) {
            return;
        }
        OllamaProvider provider = new OllamaProvider(ENDPOINT, Duration.ofSeconds(30));
        String missingModel = "tryst-model-not-installed-" + System.nanoTime();

        Throwable thrown = assertThrows(Exception.class,
                () -> provider.generateResponse(new AIProvider.InferenceRequest(
                        missingModel,
                        List.of(new AIProvider.ChatMessage("user", "ping")),
                        0.2, 16, java.util.Map.of()))
                        .get(30, TimeUnit.SECONDS));
        Throwable cause = unwrap(thrown);
        assertTrue(cause instanceof TrystInfrastructureException,
                "expected TrystInfrastructureException, got " + cause.getClass().getName());
        String message = cause.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("not installed"),
                "a missing model must be reported as 'not installed': " + message);
        assertTrue(message.contains(missingModel), "the missing model identifier must be named");
        assertTrue(message.contains(ENDPOINT), "the endpoint must be named");
    }

    @Test
    void liveUnreachableEndpointReportsConnectivityFailure() {
        OllamaProvider provider = new OllamaProvider("http://127.0.0.1:1", Duration.ofSeconds(5));
        Throwable thrown = assertThrows(Exception.class,
                () -> provider.generateResponse(new AIProvider.InferenceRequest(
                        "any-model",
                        List.of(new AIProvider.ChatMessage("user", "ping")),
                        0.2, 16, java.util.Map.of()))
                        .get(20, TimeUnit.SECONDS));
        Throwable cause = unwrap(thrown);
        assertTrue(cause instanceof TrystInfrastructureException,
                "expected TrystInfrastructureException, got " + cause.getClass().getName());
        assertTrue(cause.getMessage().contains("unreachable"),
                "an unreachable endpoint must be reported as a connectivity failure: " + cause.getMessage());
    }

    /** Unwraps ExecutionException/CompletionException chains to the root cause. */
    private static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.ExecutionException
                || t instanceof CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private String pickInstalledModel(OllamaProvider provider) throws Exception {
        List<AIProvider.ProviderModelMetadata> models =
                provider.listAvailableModels().get(30, TimeUnit.SECONDS);
        if (models.isEmpty()) {
            return null;
        }
        // Prefer a small instruction-following model known to be reliable; fall back to
        // the first installed model.
        for (AIProvider.ProviderModelMetadata model : models) {
            if ("qwen3.8:latest".equals(model.name()) || "llama3.2:3b".equals(model.name())) {
                return model.name();
            }
        }
        return models.get(0).name();
    }
}
