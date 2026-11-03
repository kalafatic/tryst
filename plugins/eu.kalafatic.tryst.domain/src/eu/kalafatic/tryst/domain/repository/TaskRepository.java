package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing AgentTask and Task domain entities,
 * persistent queues, atomic claims, and task event logs.
 */
public interface TaskRepository {

    void save(AgentTask task);

    void saveTask(Task task);

    Optional<AgentTask> findById(String id);

    Optional<Task> findTaskById(String id);

    List<AgentTask> findBySessionId(String sessionId);

    List<AgentTask> findByProjectId(String projectId);

    List<AgentTask> findTasksByStatus(ExecutionStatus status);

    List<Task> findTasksByTaskStatus(TaskStatus status);

    List<AgentTask> findAll();

    List<Task> findAllTasks();

    boolean delete(String id);

    /**
     * Atomically claims the next QUEUED task with highest priority and earliest creation time.
     * Sets status to CLAIMED and assigns workerId.
     *
     * @param workerId ID of the claiming worker thread
     * @return Claimed task if available, empty otherwise
     */
    Optional<Task> claimNextQueuedTask(String workerId);

    /**
     * Atomically claims a specific task if it is in QUEUED or READY status.
     */
    boolean claimTask(String taskId, String workerId);

    /**
     * Saves a TaskEvent log entry.
     */
    void saveTaskEvent(TaskEvent event);

    /**
     * Retrieves all events recorded for a specific task ordered by timestamp ascending.
     */
    List<TaskEvent> findEventsByTaskId(String taskId);
}
