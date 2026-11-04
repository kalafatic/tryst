package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.TaskRecoveryCheckpoint;
import eu.kalafatic.tryst.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and querying execution recovery checkpoints.
 */
public interface TaskRecoveryCheckpointRepository {

    void save(TaskRecoveryCheckpoint checkpoint);

    Optional<TaskRecoveryCheckpoint> findLatestByTaskId(String taskId);

    List<TaskRecoveryCheckpoint> findByTaskId(String taskId);

    Optional<TaskRecoveryCheckpoint> findByTaskIdAndStage(String taskId, TaskStatus stage);

    void deleteByTaskId(String taskId);

    int count();
}
