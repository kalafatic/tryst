package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.DCodeConfiguration;

import java.util.Optional;

/**
 * Persistence contract for the DCode coding harness connection configuration (Phase 6).
 */
public interface DCodeConfigurationRepository {

    void save(DCodeConfiguration configuration);

    Optional<DCodeConfiguration> findById(String id);
}
