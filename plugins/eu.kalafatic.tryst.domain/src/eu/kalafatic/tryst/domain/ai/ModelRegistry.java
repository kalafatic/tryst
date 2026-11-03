package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.UserRole;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Registry contract managing registered AI providers and published models.
 */
public interface ModelRegistry {

    void registerProvider(String id, String name, String type, String endpoint, boolean enabled);

    void updateProviderHealth(String providerId, AiModelRegistryService.ProviderHealth health);

    void publishModel(AiModelRegistryService.PublishedModel model);

    Optional<AiModelRegistryService.PublishedModel> findModel(String modelId);

    List<AiModelRegistryService.PublishedModel> getModelsForRole(UserRole userRole);

    List<AiModelRegistryService.PublishedModel> getAllModels();

    List<AiModelRegistryService.AiProviderInfo> getAllProviders();

    String getDefaultModel();

    void setDefaultModel(String modelId);

    CompletableFuture<Void> discoverModelsFromProvider(String providerId, AIProvider provider);
}
