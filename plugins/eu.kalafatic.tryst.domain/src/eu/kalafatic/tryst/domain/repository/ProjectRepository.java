package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.Project;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing Project domain entities.
 */
public interface ProjectRepository {

    void save(Project project);

    Optional<Project> findById(String id);

    List<Project> findAll();

    Optional<Project> findActive();

    List<Project> findByOwnerId(String ownerId);

    boolean delete(String id);
}
