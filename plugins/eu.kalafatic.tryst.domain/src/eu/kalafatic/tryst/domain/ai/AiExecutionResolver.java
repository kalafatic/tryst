package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single authoritative AI execution-configuration resolver.
 *
 * <p>Every inference operation (connectivity tests, task execution, AI-assisted code
 * changes, agent execution, background AI operations, workflow execution) must obtain
 * its harness/provider/endpoint/model through this resolver. No UI component or
 * inference implementation may independently invent a default model.</p>
 *
 * <p>Precedence (TEIP-family configuration contract):</p>
 * <ol>
 *   <li>Explicit user selection — the persisted execution-environment configuration
 *       ({@link ExecutionConfigurationService}, GLOBAL → PROJECT → TASK).</li>
 *   <li>Explicitly configured provider default ({@code AiProviderDescriptor.defaultModel}).</li>
 *   <li>No implicit fallback. When no model is selected, the resolver reports an empty
 *       model identifier with source {@code NONE}; callers must fail with a clear
 *       configuration error. {@code codellama}, {@code llama3.2} or any other hardcoded
 *       model are never substituted.</li>
 * </ol>
 */
public class AiExecutionResolver {

    /** Resolution provenance of the selected model. */
    public static final String SOURCE_NONE = "NONE";
    public static final String SOURCE_PROVIDER_DEFAULT = "PROVIDER_DEFAULT";

    /**
     * The fully resolved AI execution configuration.
     *
     * @param harnessId            resolved harness (empty = built-in local agent)
     * @param providerId           resolved provider id (empty = no provider selected)
     * @param providerType         provider type (OLLAMA / ENR / …)
     * @param endpoint             provider endpoint (never contains secrets)
     * @param providerEnabled      whether the resolved provider is enabled
     * @param modelId              the exact model identifier to send to the provider
     * @param agentProfileId       resolved agent profile (empty = none)
     * @param executionParameters  resolved inference parameters
     * @param source               resolution provenance (GLOBAL/PROJECT/TASK/
     *                             PROVIDER_DEFAULT/NONE)
     * @param modelAvailable       whether the model is present in the provider's
     *                             discovered catalog (observability only — the
     *                             resolved identifier is still used verbatim)
     */
    public record ResolvedExecution(
            String harnessId,
            String providerId,
            String providerType,
            String endpoint,
            boolean providerEnabled,
            String modelId,
            String agentProfileId,
            Map<String, String> executionParameters,
            String source,
            boolean modelAvailable
    ) {
        public ResolvedExecution {
            harnessId = harnessId == null ? "" : harnessId;
            providerId = providerId == null ? "" : providerId;
            providerType = providerType == null ? "" : providerType;
            endpoint = endpoint == null ? "" : endpoint;
            modelId = modelId == null ? "" : modelId;
            agentProfileId = agentProfileId == null ? "" : agentProfileId;
            executionParameters = executionParameters == null
                    ? Map.of() : Map.copyOf(executionParameters);
            source = source == null || source.isBlank() ? SOURCE_NONE : source;
        }

        public boolean hasModel() {
            return !modelId.isBlank();
        }
    }

    private final ExecutionConfigurationService executionConfigurationService;
    private final AiProviderRegistry providerRegistry;
    private final AiModelRegistryService modelCatalog;

    public AiExecutionResolver(ExecutionConfigurationService executionConfigurationService,
                               AiProviderRegistry providerRegistry,
                               AiModelRegistryService modelCatalog) {
        this.executionConfigurationService = executionConfigurationService;
        this.providerRegistry = providerRegistry;
        this.modelCatalog = modelCatalog;
    }

    /**
     * Resolves the execution configuration for the given project/task scope. A null
     * project/task resolves the global defaults (used by connectivity tests and UI
     * surfaces).
     */
    public ResolvedExecution resolve(String projectId, String taskId) {
        ExecutionConfiguration configuration;
        try {
            configuration = executionConfigurationService != null
                    ? executionConfigurationService.resolve(projectId, taskId)
                    : ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL);
        } catch (Exception e) {
            TrystLog.warn(AiExecutionResolver.class,
                    "Execution configuration resolution failed: " + e.getMessage());
            configuration = ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL);
        }
        if (configuration == null) {
            configuration = ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL);
        }

        String providerId = configuration.providerId();
        if (providerId == null || providerId.isBlank()) {
            providerId = resolveDefaultProviderId();
        }

        AiProviderDescriptor descriptor = resolveProviderDescriptor(providerId);

        String modelId = configuration.modelId() == null ? "" : configuration.modelId().trim();
        String source = modelId.isBlank() ? SOURCE_NONE : configuration.source();
        if (modelId.isBlank() && descriptor != null && descriptor.defaultModel() != null
                && !descriptor.defaultModel().isBlank()) {
            modelId = descriptor.defaultModel().trim();
            source = SOURCE_PROVIDER_DEFAULT;
        }

        boolean modelAvailable = modelId.isBlank()
                || isModelKnownForProvider(providerId, modelId);

        return new ResolvedExecution(
                configuration.harnessId(),
                providerId,
                descriptor != null ? descriptor.type() : "",
                descriptor != null ? descriptor.endpoint() : "",
                descriptor == null || descriptor.enabled(),
                modelId,
                configuration.agentProfileId(),
                configuration.executionParameters(),
                source,
                modelAvailable
        );
    }

    private String resolveDefaultProviderId() {
        if (providerRegistry == null) {
            return "";
        }
        try {
            Optional<String> selected = providerRegistry.getDefaultProviderId();
            if (selected.isPresent()) {
                return selected.get();
            }
            // No explicitly selected default: fall back only to the unambiguous case
            // of exactly one enabled provider. Multiple enabled providers without an
            // explicit selection are treated as unconfigured (never a silent choice).
            List<AiProviderDescriptor> enabled = new java.util.ArrayList<>();
            for (AiProviderDescriptor descriptor : providerRegistry.listProviders()) {
                if (descriptor.enabled()) {
                    enabled.add(descriptor);
                }
            }
            return enabled.size() == 1 ? enabled.get(0).id() : "";
        } catch (Exception e) {
            TrystLog.warn(AiExecutionResolver.class,
                    "Default provider resolution failed: " + e.getMessage());
            return "";
        }
    }

    private AiProviderDescriptor resolveProviderDescriptor(String providerId) {
        if (providerRegistry == null || providerId == null || providerId.isBlank()) {
            return null;
        }
        try {
            return providerRegistry.findProvider(providerId).orElse(null);
        } catch (Exception e) {
            TrystLog.warn(AiExecutionResolver.class,
                    "Provider descriptor resolution failed for " + providerId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether the given model identifier is present in the discovered catalog of the
     * given provider. Exact identifier comparison — no normalization.
     */
    public boolean isModelKnownForProvider(String providerId, String modelId) {
        if (modelId == null || modelId.isBlank() || modelCatalog == null) {
            return false;
        }
        List<AiModelRegistryService.PublishedModel> models;
        try {
            models = modelCatalog.getAllModels();
        } catch (Exception e) {
            return false;
        }
        for (AiModelRegistryService.PublishedModel model : models) {
            if (Objects.equals(model.providerId(), providerId)
                    && model.modelIdentifier() != null
                    && model.modelIdentifier().equals(modelId)) {
                return true;
            }
        }
        return false;
    }
}
