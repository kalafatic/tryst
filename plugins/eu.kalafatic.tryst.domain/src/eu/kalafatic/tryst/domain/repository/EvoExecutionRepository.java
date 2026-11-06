package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.EvoExecutionEvent;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing EVO executions and received execution events.
 */
public interface EvoExecutionRepository {

    void saveExecution(EvoExecution execution);

    Optional<EvoExecution> findExecutionById(String id);

    Optional<EvoExecution> findExecutionByEvoExecutionId(String evoExecutionId);

    Optional<EvoExecution> findExecutionByIdempotencyKey(String idempotencyKey);

    List<EvoExecution> findExecutionsByTaskId(String taskId);

    List<EvoExecution> findAllExecutions();

    boolean saveEvent(EvoExecutionEvent event);

    List<EvoExecutionEvent> findEventsByExecutionId(String executionId, long afterSequence, int limit);

    long getLatestSequence(String executionId);
}
