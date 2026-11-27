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

    /**
     * The cached model list for a single provider (the provider-model cache view used
     * by the AI settings model combo). Returns an empty list when the provider is
     * unknown or has no discovered models yet.
     */
    public List<PublishedModel> getModelsForProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return List.of();
        }
        return models.values().stream()
                .filter(m -> providerId.equals(m.providerId()))
                .sorted(Comparator.comparing(PublishedModel::displayName))
                .toList();
    }

    public List<AiProviderInfo> getAllProviders() {
        return List.copyOf(providers.values());
    }

    public Optional<AiProviderInfo> findProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(providerId));
    }

    public ProviderHealth getProviderHealth(String providerId) {
        return findProvider(providerId).map(AiProviderInfo::health).orElse(ProviderHealth.UNKNOWN);
    }

    /**
     * Removes a single published model from the catalog and notifies listeners.
     */
    public void removeModel(String modelId) {
        if (modelId == null) {
            return;
        }
        if (models.remove(modelId) != null) {
            notifyListeners();
        }
    }

    /**
     * Removes every published model that belongs to the given provider.
     */
    public void removeModelsForProvider(String providerId) {
        if (providerId == null) {
            return;
        }
        boolean removed = models.values().removeIf(m -> providerId.equals(m.providerId()));
        if (removed) {
            notifyListeners();
        }
    }

    /**
     * Removes a provider and all of its published models from the catalog.
     */
    public void removeProvider(String providerId) {
        if (providerId == null) {
            return;
        }
        providers.remove(providerId);
        boolean removed = models.values().removeIf(m -> providerId.equals(m.providerId()));
        if (removed) {
            notifyListeners();
        }
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
                Set<String> liveIds = new HashSet<>();
                for (AIProvider.ProviderModelMetadata meta : metas) {
                    String id = providerId + ":" + meta.name();
                    liveIds.add(id);
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
                models.values().stream()
                        .filter(m -> providerId.equals(m.providerId()) && !liveIds.contains(m.id())
                                && m.available())
                        .forEach(m -> models.put(m.id(), new PublishedModel(
                                m.id(), m.providerId(), m.modelIdentifier(), m.displayName(),
                                m.contextLimit(), false, m.published(), m.minRole(), Instant.now()
                        )));
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
