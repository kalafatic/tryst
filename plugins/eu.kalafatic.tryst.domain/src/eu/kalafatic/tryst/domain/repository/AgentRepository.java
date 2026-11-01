package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.AgentConfiguration;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing AgentConfiguration domain entities.
 */
public interface AgentRepository {

    void save(AgentConfiguration agent);

    Optional<AgentConfiguration> findById(String id);

    Optional<AgentConfiguration> findByName(String name);

    List<AgentConfiguration> findAll();

    boolean delete(String id);
}
