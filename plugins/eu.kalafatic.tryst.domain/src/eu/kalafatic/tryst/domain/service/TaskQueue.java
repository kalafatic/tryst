package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
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

    /**
     * Increases execution priority of a queued task (e.g. NORMAL -> HIGH -> URGENT).
     */
    public synchronized boolean moveUp(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> opt = taskRepository.findTaskById(taskId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        if (task.getStatus().isTerminal()) return false;

        TaskPriority p = task.getPriority();
        TaskPriority next = switch (p) {
            case LOW -> TaskPriority.NORMAL;
            case NORMAL -> TaskPriority.HIGH;
            case HIGH, URGENT -> TaskPriority.URGENT;
        };
        if (next != p) {
            task.setPriority(next);
            taskRepository.saveTask(task);
            return true;
        }
        return false;
    }

    /**
     * Decreases execution priority of a queued task (e.g. URGENT -> HIGH -> NORMAL -> LOW).
     */
    public synchronized boolean moveDown(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> opt = taskRepository.findTaskById(taskId);
        if (opt.isEmpty()) return false;
        Task task = opt.get();
        if (task.getStatus().isTerminal()) return false;

        TaskPriority p = task.getPriority();
        TaskPriority next = switch (p) {
            case URGENT -> TaskPriority.HIGH;
            case HIGH -> TaskPriority.NORMAL;
            case NORMAL, LOW -> TaskPriority.LOW;
        };
        if (next != p) {
            task.setPriority(next);
            taskRepository.saveTask(task);
            return true;
        }
        return false;
    }
}
