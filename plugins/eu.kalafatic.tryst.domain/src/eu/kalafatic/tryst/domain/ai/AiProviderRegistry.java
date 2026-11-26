package eu.kalafatic.tryst.domain.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Authoritative lifecycle registry for AI providers (unified multi-provider dimension).
 * Wraps the existing {@link AiModelRegistryService} model catalog: provider registration,
 * persistence, enablement, default selection, connection testing and model discovery all
 * go through this registry so exactly one provider lifecycle exists. Registered adapters
 * implement the existing {@link AIProvider} contract.
 */
public interface AiProviderRegistry {

    record ConnectionStatus(boolean success, String message) {
        public static ConnectionStatus ok(String message) {
            return new ConnectionStatus(true, message == null ? "Connected" : message);
        }

        public static ConnectionStatus error(String message) {
            return new ConnectionStatus(false, message == null ? "Unavailable" : message);
        }
    }

    /**
     * Registers (or refreshes) a provider adapter and persists its descriptor. Model
     * discovery is delegated to the shared model catalog.
     */
    void registerProvider(String id, String name, String type, String endpoint,
            boolean enabled, AIProvider adapter);

    void unregisterProvider(String id);

    List<AiProviderDescriptor> listProviders();

    Optional<AiProviderDescriptor> findProvider(String id);

    Optional<AIProvider> findAdapter(String id);

    void setProviderEnabled(String id, boolean enabled);

    void selectDefaultProvider(String id);

    /**
     * Persists the provider's configured default model (exact identifier, no
     * normalization). Used as the last permitted fallback level in the unified
     * configuration chain — never a hardcoded identifier.
     */
    void updateDefaultModel(String id, String modelId);

    Optional<String> getDefaultProviderId();

    Optional<AIProvider> getDefaultAdapter();

    /**
     * Updates the provider configuration. Values whose keys look sensitive
     * (token/secret/password/credential/apiKey) are encrypted at rest by the underlying
     * repository; getConfiguration() returns them masked.
     */
    void updateConfiguration(String id, Map<String, String> configuration);

    /**
     * Returns the provider configuration with every sensitive value masked
     * (never the real credentials).
     */
    Map<String, String> getConfiguration(String id);

    /**
     * Live connection test against the provider endpoint.
     */
    ConnectionStatus testConnection(String id);

    /**
     * Discovers models from the provider into the shared model catalog.
     */
    CompletableFuture<Void> discoverModels(String id);

    /**
     * Masks a secret value for display: never expose real credentials in UI or logs.
     */
    static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 2);
    }

    /**
     * Sensitive configuration key detection (shared rule; the infrastructure secret
     * codec delegates to this so encryption and masking never diverge).
     */
    static boolean isSensitiveKey(String keyName) {
        if (keyName == null) {
            return false;
        }
        String lower = keyName.toLowerCase();
        return lower.contains("token") || lower.contains("secret")
                || lower.contains("password") || lower.contains("credential")
                || lower.contains("apikey") || lower.contains("api_key")
                || lower.contains("api-key");
    }
}
