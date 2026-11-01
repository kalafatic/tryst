package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.Session;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing Session domain entities.
 */
public interface SessionRepository {

    void save(Session session);

    Optional<Session> findById(String id);

    List<Session> findByProjectId(String projectId);

    List<Session> findRecoverableSessions();

    List<Session> findAll();

    boolean delete(String id);
}
