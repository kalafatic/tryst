package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.EvoConfiguration;

import java.util.Optional;

/**
 * Repository interface for managing TEIP EVO integration configuration.
 */
public interface EvoConfigurationRepository {

    void save(EvoConfiguration configuration);

    Optional<EvoConfiguration> getConfiguration();
}
