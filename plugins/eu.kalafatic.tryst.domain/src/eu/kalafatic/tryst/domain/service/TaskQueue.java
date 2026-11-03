package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.TaskRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent SQLite-backed task queue.
 * Supports priority scheduling, FIFO within same priority, thread-safe atomic claiming,
 * task cancellation, and queue statistics.
 */
public class TaskQueue {

    private final TaskRepository taskRepository;
    private final TaskLifecycleManager lifecycleManager;

    public TaskQueue(TaskRepository taskRepository, TaskLifecycleManager lifecycleManager) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
    }

    /**
     * Enqueues an existing task or puts a newly created task into QUEUED status.
     */
    public synchronized void enqueue(Task task) {
        Objects.requireNonNull(task, "task must not be null");
        lifecycleManager.transition(task, TaskStatus.QUEUED, "Enqueued for worker execution", null);
    }

    /**
     * Atomically claims the next QUEUED task with highest priority and earliest creation time.
     * Prevents duplicate execution and race conditions across concurrent workers.
     *
     * @param workerId ID of the worker claiming the task
     * @return Claimed task if available, empty otherwise
     */
    public Optional<Task> claimNext(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        Optional<Task> optTask = taskRepository.claimNextQueuedTask(workerId);
        optTask.ifPresent(task -> {
            lifecycleManager.logProgress(task, "Task claimed by worker " + workerId, null);
        });
        return optTask;
    }

    /**
     * Atomically claims a specific task by ID if it is currently QUEUED or READY.
     */
    public boolean claim(String taskId, String workerId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        boolean claimed = taskRepository.claimTask(taskId, workerId);
        if (claimed) {
            taskRepository.findTaskById(taskId).ifPresent(task -> {
                lifecycleManager.logProgress(task, "Task explicitly claimed by worker " + workerId, null);
            });
        }
        return claimed;
    }

    /**
     * Cancels a queued or active task.
     */
    public boolean cancel(String taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return taskRepository.findTaskById(taskId).map(task -> {
            if (task.getStatus().isTerminal()) {
                return false;
            }
            lifecycleManager.transition(task, TaskStatus.CANCELLED, "Task cancelled: " + reason, null);
            return true;
        }).orElse(false);
    }

    public List<Task> getQueuedTasks() {
        return taskRepository.findTasksByTaskStatus(TaskStatus.QUEUED);
    }

    public int getQueueDepth() {
        return getQueuedTasks().size();
    }
}
