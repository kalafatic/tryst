package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing AgentTask domain entities.
 */
public interface TaskRepository {

    void save(AgentTask task);

    Optional<AgentTask> findById(String id);

    List<AgentTask> findBySessionId(String sessionId);

    List<AgentTask> findByProjectId(String projectId);

    List<AgentTask> findTasksByStatus(ExecutionStatus status);

    List<AgentTask> findAll();

    boolean delete(String id);
}
