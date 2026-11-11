package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleNotificationTest {

    @Test
    void testLifecycleEventsPublished() {
        EventBus bus = new EventBus();
        List<ApplicationEvents.TaskStateChangedEvent> events = new ArrayList<>();
        bus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, events::add);

        eu.kalafatic.tryst.domain.repository.TaskRepository mockRepo = new eu.kalafatic.tryst.domain.repository.TaskRepository() {
            @Override public void save(eu.kalafatic.tryst.domain.model.AgentTask task) {}
            @Override public void saveTask(Task task) {}
            @Override public Optional<eu.kalafatic.tryst.domain.model.AgentTask> findById(String id) { return Optional.empty(); }
            @Override public Optional<Task> findTaskById(String id) { return Optional.empty(); }
            @Override public List<eu.kalafatic.tryst.domain.model.AgentTask> findByProjectId(String projectId) { return List.of(); }
            @Override public List<eu.kalafatic.tryst.domain.model.AgentTask> findBySessionId(String sessionId) { return List.of(); }
            @Override public List<eu.kalafatic.tryst.domain.model.AgentTask> findTasksByStatus(eu.kalafatic.tryst.domain.model.ExecutionStatus status) { return List.of(); }
            @Override public List<Task> findTasksByTaskStatus(TaskStatus status) { return List.of(); }
            @Override public List<eu.kalafatic.tryst.domain.model.AgentTask> findAll() { return List.of(); }
            @Override public List<Task> findAllTasks() { return List.of(); }
            @Override public boolean delete(String id) { return false; }
            @Override public Optional<Task> claimNextQueuedTask(String workerId) { return Optional.empty(); }
            @Override public boolean claimTask(String taskId, String workerId) { return false; }
            @Override public void saveTaskEvent(eu.kalafatic.tryst.domain.model.TaskEvent event) {}
            @Override public List<eu.kalafatic.tryst.domain.model.TaskEvent> findEventsByTaskId(String taskId) { return List.of(); }
        };

        TaskLifecycleManager lm = new TaskLifecycleManager(mockRepo, bus);
        Task t = new Task("t-1", "p-1", "s-1", "Title", "Desc", TaskStatus.CREATED, TaskPriority.NORMAL,
                null, null, null, null, 0, null, java.time.Instant.now(), null);

        lm.transition(t, TaskStatus.QUEUED, "Queued", null);
        lm.transition(t, TaskStatus.PAUSED, "Paused", null);
        lm.transition(t, TaskStatus.QUEUED, "Resumed", null);

        assertEquals(3, events.size());
        assertEquals(TaskStatus.QUEUED.name(), events.get(0).newState());
        assertEquals(TaskStatus.PAUSED.name(), events.get(1).newState());
        assertEquals(TaskStatus.QUEUED.name(), events.get(2).newState());
    }
}
