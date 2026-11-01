package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.Workspace;
import java.util.Optional;

/**
 * Repository interface for managing Workspace domain entities.
 */
public interface WorkspaceRepository {

    void save(Workspace workspace);

    Optional<Workspace> findById(String id);

    Optional<Workspace> findActive();

    boolean delete(String id);
}
