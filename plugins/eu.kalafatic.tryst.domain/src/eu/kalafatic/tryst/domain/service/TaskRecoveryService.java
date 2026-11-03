package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.TaskRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles detection and recovery of interrupted tasks upon server startup.
 * Automatically marks tasks in active execution states (CLAIMED, CLONING, ANALYZING,
 * PLANNING, IMPLEMENTING, BUILDING, TESTING, REWORKING, COMMITTING) as RECOVERY_REQUIRED.
 * Preserves tasks awaiting human-in-the-loop review (WAITING_FOR_REVIEW) and APPROVED tasks.
 */
public class TaskRecoveryService {

    private final TaskRepository taskRepository;
    private final TaskLifecycleManager lifecycleManager;

    public TaskRecoveryService(TaskRepository taskRepository, TaskLifecycleManager lifecycleManager) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
    }

    /**
     * Reconciles all active interrupted tasks upon application startup.
     *
     * @return List of tasks reconciled to RECOVERY_REQUIRED
     */
    public List<Task> reconcileInterruptedTasks() {
        List<Task> allTasks = taskRepository.findAllTasks();
        List<Task> recoveredTasks = new ArrayList<>();

        for (Task task : allTasks) {
            TaskStatus status = task.getStatus();
            if (status.isActive() || status == TaskStatus.QUEUED) {
                TrystLog.warn(TaskRecoveryService.class,
                        "Startup reconciliation: Interrupted task " + task.getId() +
                                " in status " + status + " -> RECOVERY_REQUIRED");

                lifecycleManager.transition(
                        task,
                        TaskStatus.RECOVERY_REQUIRED,
                        "Server restart interrupted execution in stage: " + status,
                        "Automatic startup task reconciliation detected interrupted execution."
                );
                recoveredTasks.add(task);
            }
        }

        return recoveredTasks;
    }

    /**
     * Resets a task in RECOVERY_REQUIRED status back to QUEUED for safe retry.
     */
    public boolean retryRecoveredTask(String taskId) {
        return taskRepository.findTaskById(taskId).map(task -> {
            if (task.getStatus() == TaskStatus.RECOVERY_REQUIRED) {
                lifecycleManager.transition(
                        task,
                        TaskStatus.QUEUED,
                        "Task queued for retry following recovery",
                        "Manual or automated restart recovery retry triggered."
                );
                return true;
            }
            return false;
        }).orElse(false);
    }
}
