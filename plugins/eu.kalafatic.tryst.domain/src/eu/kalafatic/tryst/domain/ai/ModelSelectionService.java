package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.UserRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service responsible for selecting the model for a given task.
 *
 * <p>Resolution precedence (single source of truth — see {@link AiExecutionResolver}):</p>
 * <ol>
 *   <li>An explicitly requested model identifier (passed verbatim — never substituted).</li>
 *   <li>The persisted execution-environment configuration
 *       ({@link ExecutionConfigurationService}, GLOBAL → PROJECT → TASK).</li>
 *   <li>The explicitly configured provider default
 *       ({@code AiProviderDescriptor.defaultModel}).</li>
 *   <li>No implicit fallback: when no model is selected, an empty identifier is
 *       returned and the caller must fail with a clear configuration error. No
 *       hardcoded model is ever substituted.</li>
 * </ol>
 */
public class ModelSelectionService {

    private final ModelRegistry registry;
    private final ExecutionConfigurationService executionConfigurationService;
    private final AiProviderRegistry providerRegistry;

    public ModelSelectionService(ModelRegistry registry) {
        this(registry, null, null);
    }

    public ModelSelectionService(ModelRegistry registry,
                                 ExecutionConfigurationService executionConfigurationService,
                                 AiProviderRegistry providerRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.executionConfigurationService = executionConfigurationService;
        this.providerRegistry = providerRegistry;
    }

    /**
     * Resolves the model identifier for the given task and user role.
     *
     * @return the exact model identifier, or an empty string when no model is selected
     *         (callers must report a configuration error instead of substituting a model)
     */
    public String selectModel(Task task, String requestedModel, UserRole role) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            // 1. An explicit request resolves its catalog identifier (never a substitute).
            Optional<AiModelRegistryService.PublishedModel> published = registry.findModel(requestedModel);
            if (published.isPresent() && published.get().modelIdentifier() != null
                    && !published.get().modelIdentifier().isBlank()) {
                return published.get().modelIdentifier();
            }
            // Explicit request wins and is passed through verbatim (TEIP configuration
            // contract: never silently substitute an explicitly requested model).
            return requestedModel;
        }

        // 1. Persisted execution-environment configuration (GLOBAL → PROJECT → TASK).
        if (executionConfigurationService != null && task != null) {
            try {
                ExecutionConfiguration resolved = executionConfigurationService.resolve(
                        task.getProjectId(), task.getId());
                if (resolved != null && resolved.modelId() != null
                        && !resolved.modelId().isBlank()) {
                    return resolved.modelId().trim();
                }
            } catch (Exception e) {
                eu.kalafatic.tryst.core.log.TrystLog.warn(ModelSelectionService.class,
                        "Execution configuration resolution failed: " + e.getMessage());
            }
        }

        // 2. Explicitly configured provider default (with the same deterministic
        //    single-enabled-provider rule as AiExecutionResolver).
        if (providerRegistry != null) {
            try {
                String providerId = null;
                Optional<String> defaultProviderId = providerRegistry.getDefaultProviderId();
                if (defaultProviderId.isPresent()) {
                    providerId = defaultProviderId.get();
                } else {
                    List<AiProviderDescriptor> enabled = new java.util.ArrayList<>();
                    for (AiProviderDescriptor descriptor : providerRegistry.listProviders()) {
                        if (descriptor.enabled()) {
                            enabled.add(descriptor);
                        }
                    }
                    if (enabled.size() == 1) {
                        providerId = enabled.get(0).id();
                    }
                }
                if (providerId != null) {
                    Optional<AiProviderDescriptor> descriptor =
                            providerRegistry.findProvider(providerId);
                    if (descriptor.isPresent() && descriptor.get().defaultModel() != null
                            && !descriptor.get().defaultModel().isBlank()) {
                        return descriptor.get().defaultModel().trim();
                    }
                }
            } catch (Exception e) {
                eu.kalafatic.tryst.core.log.TrystLog.warn(ModelSelectionService.class,
                        "Provider default model resolution failed: " + e.getMessage());
            }
        }

        // 3. No implicit fallback: the task fails with a clear configuration error
        // instead of silently using a hardcoded model.
        return "";
    }
}
