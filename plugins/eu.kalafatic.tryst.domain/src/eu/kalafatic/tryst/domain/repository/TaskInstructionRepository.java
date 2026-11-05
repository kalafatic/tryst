package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.TaskInstruction;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing persistent TaskInstruction domain entities.
 */
public interface TaskInstructionRepository {

    void save(TaskInstruction instruction);

    Optional<TaskInstruction> findById(String id);

    List<TaskInstruction> findByTaskId(String taskId);

    boolean delete(String id);

    void deleteByTaskId(String taskId);
}
