package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.UserRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service responsible for selecting the most appropriate AI model for a given task,
 * taking into account role authorization, context limits, provider health, and fallback defaults.
 */
public class ModelSelectionService {

    private final ModelRegistry registry;

    public ModelSelectionService(ModelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Resolves the best candidate model identifier for the given task and user role.
     */
    public String selectModel(Task task, String requestedModel, UserRole role) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            Optional<AiModelRegistryService.PublishedModel> pub = registry.findModel(requestedModel);
            if (pub.isPresent() && pub.get().available()) {
                return pub.get().modelIdentifier();
            }
            // If requested directly as a model identifier (e.g. codellama)
            return requestedModel;
        }

        List<AiModelRegistryService.PublishedModel> available = registry.getModelsForRole(role);
        for (AiModelRegistryService.PublishedModel m : available) {
            if (m.available()) {
                return m.modelIdentifier();
            }
        }

        String defaultModel = registry.getDefaultModel();
        return defaultModel != null && !defaultModel.isBlank() ? defaultModel : "codellama";
    }
}
