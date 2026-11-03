package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing Workspace domain entities.
 */
public interface WorkspaceRepository {

    void save(Workspace workspace);

    Optional<Workspace> findById(String id);

    Optional<Workspace> findActive();

    Optional<Workspace> findByTaskId(String taskId);

    List<Workspace> findByOwnerId(String ownerId);

    List<Workspace> findByStatus(WorkspaceStatus status);

    List<Workspace> findAll();

    boolean delete(String id);
}
