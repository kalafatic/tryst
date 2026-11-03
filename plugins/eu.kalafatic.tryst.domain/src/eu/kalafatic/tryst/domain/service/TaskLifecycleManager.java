package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.TaskRepository;

import java.util.Objects;

/**
 * Coordinates persistent, transactional task lifecycle state transitions,
 * audit event recording, and EventBus notification dispatching.
 */
public class TaskLifecycleManager {

    private final TaskRepository taskRepository;
    private final EventBus eventBus;

    public TaskLifecycleManager(TaskRepository taskRepository, EventBus eventBus) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.eventBus = eventBus;
    }

    /**
     * Executes a validated state transition, saves the task, logs the TaskEvent, and publishes an event.
     */
    public synchronized void transition(Task task, TaskStatus targetStatus, String message, String details) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");

        TaskStatus previousStatus = task.getStatus();
        if (previousStatus == targetStatus) {
            return;
        }

        task.transitionTo(targetStatus, message);
        taskRepository.saveTask(task);

        TaskEvent event = new TaskEvent(task.getId(), targetStatus, message, details);
        taskRepository.saveTaskEvent(event);

        if (eventBus != null) {
            eventBus.publish(new ApplicationEvents.TaskStateChangedEvent(
                    task.getId(),
                    previousStatus.name(),
                    targetStatus.name(),
                    message != null ? message : ("Transitioned to " + targetStatus)
            ));
        }

        TrystLog.info(TaskLifecycleManager.class,
                "Task " + task.getId() + " transitioned: " + previousStatus + " -> " + targetStatus + " (" + message + ")");
    }

    public void logProgress(Task task, String message, String details) {
        Objects.requireNonNull(task, "task must not be null");
        TaskEvent event = new TaskEvent(task.getId(), task.getStatus(), message, details);
        taskRepository.saveTaskEvent(event);
    }
}
