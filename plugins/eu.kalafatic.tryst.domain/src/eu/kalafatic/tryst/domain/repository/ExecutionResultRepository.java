package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.ExecutionResult;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing ExecutionResult domain entities.
 */
public interface ExecutionResultRepository {

    void save(ExecutionResult result);

    Optional<ExecutionResult> findById(String id);

    List<ExecutionResult> findByTaskId(String taskId);

    Optional<ExecutionResult> findLatestByTaskId(String taskId);

    boolean delete(String id);
}
