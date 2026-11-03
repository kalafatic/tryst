package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.UserRole;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Authoritative registry and catalog service for AI providers and published models.
 * Manages provider health checks, model discovery, role-based visibility filtering,
 * and session-level model resolution.
 */
public class AiModelRegistryService implements ModelRegistry {

    public enum ProviderHealth {
        UNKNOWN,
        ONLINE,
        DEGRADED,
        OFFLINE
    }

    public record PublishedModel(
            String id,
            String providerId,
            String modelIdentifier,
            String displayName,
            int contextLimit,
            boolean available,
            boolean published,
            UserRole minRole,
            Instant lastVerifiedAt
    ) {
        public PublishedModel {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(providerId, "providerId must not be null");
            Objects.requireNonNull(modelIdentifier, "modelIdentifier must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(minRole, "minRole must not be null");
        }
    }

    public record AiProviderInfo(
            String id,
            String name,
            String providerType,
            String endpoint,
            boolean enabled,
            ProviderHealth health,
            Instant lastCheckedAt
    ) {}

    private final Map<String, AiProviderInfo> providers = new ConcurrentHashMap<>();
    private final Map<String, PublishedModel> models = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<PublishedModel>>> catalogListeners = new CopyOnWriteArrayList<>();
    private volatile String defaultModelId;

    public AiModelRegistryService() {
        this("codellama");
    }

    public AiModelRegistryService(String defaultModelIdentifier) {
        this.defaultModelId = defaultModelIdentifier;
    }

    public void registerProvider(String id, String name, String type, String endpoint, boolean enabled) {
        providers.put(id, new AiProviderInfo(id, name, type, endpoint, enabled, ProviderHealth.UNKNOWN, Instant.now()));
    }

    public void updateProviderHealth(String providerId, ProviderHealth health) {
        providers.computeIfPresent(providerId, (k, v) ->
                new AiProviderInfo(v.id(), v.name(), v.providerType(), v.endpoint(), v.enabled(), health, Instant.now()));
    }

    public void publishModel(PublishedModel model) {
        models.put(model.id(), model);
        notifyListeners();
    }

    public Optional<PublishedModel> findModel(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    public List<PublishedModel> getModelsForRole(UserRole userRole) {
        return models.values().stream()
                .filter(PublishedModel::published)
                .filter(m -> userRole == null || isRolePermitted(userRole, m.minRole()))
                .sorted(Comparator.comparing(PublishedModel::displayName))
                .toList();
    }

    private static boolean isRolePermitted(UserRole actual, UserRole required) {
        if (actual == UserRole.ADMIN) return true;
        if (required == UserRole.USER) return true;
        return actual == required;
    }

    public List<PublishedModel> getAllModels() {
        return List.copyOf(models.values());
    }

    public List<AiProviderInfo> getAllProviders() {
        return List.copyOf(providers.values());
    }

    public String getDefaultModel() {
        return defaultModelId;
    }

    public void setDefaultModel(String modelId) {
        this.defaultModelId = modelId;
    }

    @Override
    public CompletableFuture<Void> discoverModelsFromProvider(String providerId, AIProvider provider) {
        if (provider == null) {
            updateProviderHealth(providerId, ProviderHealth.OFFLINE);
            return CompletableFuture.completedFuture(null);
        }

        return provider.checkConnection().thenCompose(connected -> {
            if (!connected) {
                updateProviderHealth(providerId, ProviderHealth.OFFLINE);
                models.values().stream()
                        .filter(m -> m.providerId().equals(providerId))
                        .forEach(m -> models.put(m.id(), new PublishedModel(
                                m.id(), m.providerId(), m.modelIdentifier(), m.displayName(),
                                m.contextLimit(), false, m.published(), m.minRole(), Instant.now()
                        )));
                notifyListeners();
                return CompletableFuture.completedFuture(null);
            }

            updateProviderHealth(providerId, ProviderHealth.ONLINE);
            return provider.listAvailableModels().thenAccept(metas -> {
                for (AIProvider.ProviderModelMetadata meta : metas) {
                    String id = providerId + ":" + meta.name();
                    PublishedModel pm = new PublishedModel(
                            id,
                            providerId,
                            meta.name(),
                            meta.name(),
                            4096,
                            true,
                            true,
                            UserRole.USER,
                            Instant.now()
                    );
                    models.put(id, pm);
                }
                notifyListeners();
            }).exceptionally(t -> {
                TrystLog.warn(AiModelRegistryService.class, "Failed listing models from " + providerId + ": " + t.getMessage());
                updateProviderHealth(providerId, ProviderHealth.DEGRADED);
                return null;
            });
        });
    }

    public CompletableFuture<Void> discoverModelsFromInferenceService(String providerId, AiInferenceService inferenceService) {
        if (inferenceService == null) {
            updateProviderHealth(providerId, ProviderHealth.OFFLINE);
            return CompletableFuture.completedFuture(null);
        }

        return inferenceService.checkConnection().thenCompose(connected -> {
            if (!connected) {
                updateProviderHealth(providerId, ProviderHealth.OFFLINE);
                models.values().stream()
                        .filter(m -> m.providerId().equals(providerId))
                        .forEach(m -> models.put(m.id(), new PublishedModel(
                                m.id(), m.providerId(), m.modelIdentifier(), m.displayName(),
                                m.contextLimit(), false, m.published(), m.minRole(), Instant.now()
                        )));
                notifyListeners();
                return CompletableFuture.completedFuture(null);
            }

            updateProviderHealth(providerId, ProviderHealth.ONLINE);
            return inferenceService.listModels().thenAccept(metas -> {
                for (AiInferenceService.ModelMetadata meta : metas) {
                    String id = providerId + ":" + meta.name();
                    PublishedModel pm = new PublishedModel(
                            id,
                            providerId,
                            meta.name(),
                            meta.name(),
                            4096,
                            true,
                            true,
                            UserRole.USER,
                            Instant.now()
                    );
                    models.put(id, pm);
                }
                notifyListeners();
            }).exceptionally(t -> {
                TrystLog.warn(AiModelRegistryService.class, "Failed listing models from " + providerId + ": " + t.getMessage());
                updateProviderHealth(providerId, ProviderHealth.DEGRADED);
                return null;
            });
        });
    }

    public void addCatalogListener(Consumer<List<PublishedModel>> listener) {
        if (listener != null) {
            catalogListeners.add(listener);
        }
    }

    public void removeCatalogListener(Consumer<List<PublishedModel>> listener) {
        if (listener != null) {
            catalogListeners.remove(listener);
        }
    }

    private void notifyListeners() {
        List<PublishedModel> list = getAllModels();
        for (Consumer<List<PublishedModel>> listener : catalogListeners) {
            try {
                listener.accept(list);
            } catch (Throwable t) {
                TrystLog.error(AiModelRegistryService.class, "Catalog listener error: " + t.getMessage(), t);
            }
        }
    }
}
