package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for AI provider descriptors and provider-level state
 * (default selection). Secrets are handled by the implementation: configuration values
 * whose keys look sensitive are encrypted at rest and returned masked by the registry.
 */
public interface AiProviderRepository {

    void save(AiProviderDescriptor descriptor);

    Optional<AiProviderDescriptor> findById(String id);

    List<AiProviderDescriptor> findAll();

    boolean deleteById(String id);

    void saveDefaultProviderId(String providerId);

    Optional<String> findDefaultProviderId();

    void clearDefaultProviderId();
}
