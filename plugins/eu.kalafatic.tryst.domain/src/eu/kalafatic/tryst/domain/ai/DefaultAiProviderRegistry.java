package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.repository.AiProviderRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default AI-provider registry: the single provider lifecycle for TRYST. Registers
 * {@link AIProvider} adapters, persists descriptors through the repository, maintains
 * enablement/default selection, delegates model discovery to the shared
 * {@link AiModelRegistryService} catalog, and never exposes raw credentials
 * (getConfiguration() returns masked values).
 */
public class DefaultAiProviderRegistry implements AiProviderRegistry {

    private static final String MASK = "****";

    private final AiProviderRepository repository;
    private final AiModelRegistryService modelCatalog;
    private final Map<String, AIProvider> adapters = new ConcurrentHashMap<>();

    public DefaultAiProviderRegistry(AiProviderRepository repository,
            AiModelRegistryService modelCatalog) {
        this.repository = repository;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public synchronized void registerProvider(String id, String name, String type,
            String endpoint, boolean enabled, AIProvider adapter) {
        Optional<AiProviderDescriptor> existing = repository.findById(id);
        AiProviderDescriptor descriptor = new AiProviderDescriptor(
                id, name, type, endpoint, enabled,
                existing.map(AiProviderDescriptor::defaultModel).orElse(""),
                existing.map(AiProviderDescriptor::configuration).orElse(Map.of()),
                existing.map(AiProviderDescriptor::createdAt).orElse(Instant.now()),
                Instant.now());
        repository.save(descriptor);
        if (adapter != null) {
            adapters.put(id, adapter);
            // the adapter receives the real (plaintext) configuration in memory only
            adapter.updateConfiguration(descriptor.configuration());
        } else {
            adapters.remove(id);
        }
        if (modelCatalog != null) {
            modelCatalog.registerProvider(id, name, type, endpoint, enabled);
        }
        TrystLog.info(DefaultAiProviderRegistry.class,
                "Registered AI provider: " + id + " (" + type + ")");
    }

    @Override
    public synchronized void unregisterProvider(String id) {
        adapters.remove(id);
        if (repository.deleteById(id)) {
            if (repository.findDefaultProviderId().filter(id::equals).isPresent()) {
                repository.clearDefaultProviderId();
            }
        }
        if (modelCatalog != null) {
            modelCatalog.removeProvider(id);
        }
    }

    @Override
    public List<AiProviderDescriptor> listProviders() {
        return repository.findAll();
    }

    @Override
    public Optional<AiProviderDescriptor> findProvider(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    @Override
    public Optional<AIProvider> findAdapter(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(id));
    }

    @Override
    public synchronized void setProviderEnabled(String id, boolean enabled) {
        AiProviderDescriptor descriptor = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id));
        repository.save(new AiProviderDescriptor(descriptor.id(), descriptor.name(),
                descriptor.type(), descriptor.endpoint(), enabled, descriptor.defaultModel(),
                descriptor.configuration(), descriptor.createdAt(), Instant.now()));
    }

    @Override
    public synchronized void selectDefaultProvider(String id) {
        if (id == null || id.isBlank()) {
            repository.clearDefaultProviderId();
            return;
        }
        if (repository.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown provider: " + id);
        }
        repository.saveDefaultProviderId(id);
    }

    @Override
    public synchronized void updateDefaultModel(String id, String modelId) {
        AiProviderDescriptor descriptor = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id));
        repository.save(new AiProviderDescriptor(descriptor.id(), descriptor.name(),
                descriptor.type(), descriptor.endpoint(), descriptor.enabled(),
                modelId == null ? "" : modelId.trim(),
                descriptor.configuration(), descriptor.createdAt(), Instant.now()));
    }

    @Override
    public Optional<String> getDefaultProviderId() {
        return repository.findDefaultProviderId();
    }

    @Override
    public Optional<AIProvider> getDefaultAdapter() {
        return repository.findDefaultProviderId().flatMap(this::findAdapter);
    }

    @Override
    public synchronized void updateConfiguration(String id, Map<String, String> configuration) {
        AiProviderDescriptor descriptor = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id));
        // Merge password-field semantics: masked values (****) keep the existing secret.
        Map<String, String> incoming = configuration == null ? Map.of() : configuration;
        Map<String, String> merged = new LinkedHashMap<>(descriptor.configuration());
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (MASK.equals(value)
                    && AiProviderRegistry.isSensitiveKey(entry.getKey())
                    && merged.containsKey(entry.getKey())) {
                continue;
            }
            merged.put(entry.getKey(), value);
        }
        repository.save(new AiProviderDescriptor(descriptor.id(), descriptor.name(),
                descriptor.type(), descriptor.endpoint(), descriptor.enabled(),
                descriptor.defaultModel(), merged, descriptor.createdAt(), Instant.now()));
        AIProvider adapter = adapters.get(id);
        if (adapter != null) {
            adapter.updateConfiguration(merged);
        }
    }

    @Override
    public Map<String, String> getConfiguration(String id) {
        AiProviderDescriptor descriptor = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id));
        return maskedConfiguration(descriptor.configuration());
    }

    private Map<String, String> maskedConfiguration(Map<String, String> configuration) {
        Map<String, String> masked = new LinkedHashMap<>();
        configuration.forEach((key, value) -> masked.put(key,
                AiProviderRegistry.isSensitiveKey(key) && value != null && !value.isBlank()
                        ? MASK : value));
        return masked;
    }

    @Override
    public ConnectionStatus testConnection(String id) {
        AIProvider adapter = findAdapter(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + id));
        try {
            Boolean connected = adapter.checkConnection().get(15,
                    java.util.concurrent.TimeUnit.SECONDS);
            return connected != null && connected
                    ? ConnectionStatus.ok("Provider '" + id + "' is reachable")
                    : ConnectionStatus.error("Provider '" + id + "' is not reachable");
        } catch (Exception e) {
            return ConnectionStatus.error("Connection test failed: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<Void> discoverModels(String id) {
        AIProvider adapter = findAdapter(id).orElse(null);
        if (modelCatalog == null) {
            return CompletableFuture.completedFuture(null);
        }
        return modelCatalog.discoverModelsFromProvider(id, adapter);
    }
}
