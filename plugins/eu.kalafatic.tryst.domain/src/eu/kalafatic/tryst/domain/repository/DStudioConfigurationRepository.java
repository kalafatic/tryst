package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.DStudioConfiguration;

import java.util.Optional;

/**
 * Persistence contract for DStudio Harness Platform connection configuration (Phase 4).
 */
public interface DStudioConfigurationRepository {

    void save(DStudioConfiguration configuration);

    Optional<DStudioConfiguration> findById(String id);
}
