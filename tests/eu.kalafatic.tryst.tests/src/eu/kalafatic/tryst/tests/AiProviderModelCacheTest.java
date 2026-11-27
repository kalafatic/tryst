package eu.kalafatic.tryst.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.DefaultAiProviderRegistry;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SecretCodec;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAiProviderRepository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared provider-model cache tests for TRYST: idempotent per-provider initialization,
 * no re-discovery on repeated initialization, explicit refresh re-discovers, and the
 * per-provider model cache view never mixes another provider's models.
 */
class AiProviderModelCacheTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SecretCodec codec;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("cache-test.db"));
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();
        codec = new SecretCodec(tempDir);
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    private static final class CountingAiProvider implements AIProvider {
        final String id;
        final AtomicInteger listCalls = new AtomicInteger();
        final List<ProviderModelMetadata> models;

        CountingAiProvider(String id, List<String> names) {
            this.id = id;
            this.models = names.stream()
                    .map(n -> new ProviderModelMetadata(n, 0, "", "", "", ""))
                    .toList();
        }

        @Override
        public String getProviderId() {
            return id;
        }

        @Override
        public CompletableFuture<Boolean> checkConnection() {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
            listCalls.incrementAndGet();
            return CompletableFuture.completedFuture(models);
        }

        @Override
        public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
            return CompletableFuture.completedFuture(new InferenceResponse("ok", "stop", 1, 0, 1));
        }

        @Override
        public CompletableFuture<Void> streamResponse(InferenceRequest request,
                java.util.function.Consumer<String> chunkConsumer) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private DefaultAiProviderRegistry registryWith(AiModelRegistryService catalog,
            CountingAiProvider... providers) {
        DefaultAiProviderRegistry registry = new DefaultAiProviderRegistry(
                new SqliteAiProviderRepository(databaseManager, codec), catalog);
        for (CountingAiProvider provider : providers) {
            registry.registerProvider(provider.id, provider.id, "FAKE",
                    "http://localhost:1", true, provider);
        }
        return registry;
    }

    @Test
    void ensureInitializedDiscoversEachProviderOnce() {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        CountingAiProvider ollama = new CountingAiProvider("ollama", List.of("m1", "m2"));
        CountingAiProvider enr = new CountingAiProvider("enr", List.of("n1"));
        DefaultAiProviderRegistry registry = registryWith(catalog, ollama, enr);

        registry.ensureInitialized().join();

        assertEquals(1, ollama.listCalls.get());
        assertEquals(1, enr.listCalls.get());
        assertEquals(2, catalog.getModelsForProvider("ollama").size());
        assertEquals(1, catalog.getModelsForProvider("enr").size());
    }

    @Test
    void repeatedEnsureInitializedDoesNotRediscover() {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        CountingAiProvider ollama = new CountingAiProvider("ollama", List.of("m1"));
        DefaultAiProviderRegistry registry = registryWith(catalog, ollama);

        for (int i = 0; i < 5; i++) {
            registry.ensureInitialized().join();
        }

        assertEquals(1, ollama.listCalls.get());
    }

    @Test
    void refreshAllRediscovers() {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        CountingAiProvider ollama = new CountingAiProvider("ollama", List.of("m1"));
        DefaultAiProviderRegistry registry = registryWith(catalog, ollama);

        registry.ensureInitialized().join();
        registry.refreshAll().join();

        assertEquals(2, ollama.listCalls.get());
    }

    @Test
    void getModelsForProviderReturnsOnlyThatProvider() {
        AiModelRegistryService catalog = new AiModelRegistryService("codellama");
        catalog.publishModel(new AiModelRegistryService.PublishedModel(
                "p1:a", "p1", "a", "a", 4096, true, true, UserRole.USER, Instant.now()));
        catalog.publishModel(new AiModelRegistryService.PublishedModel(
                "p2:b", "p2", "b", "b", 4096, true, true, UserRole.USER, Instant.now()));

        assertEquals(1, catalog.getModelsForProvider("p1").size());
        assertEquals("a", catalog.getModelsForProvider("p1").get(0).modelIdentifier());
        assertTrue(catalog.getModelsForProvider("unknown").isEmpty());
    }
}
