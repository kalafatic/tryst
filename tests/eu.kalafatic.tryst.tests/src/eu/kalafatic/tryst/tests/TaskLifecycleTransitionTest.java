package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.service.TaskStateTransitionValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TaskLifecycleTransitionTest {

    @Test
    public void testValidLifecycleProgression() {
        Task task = new Task("Test Task", "Lifecycle validation");
        assertEquals(TaskStatus.CREATED, task.getStatus());

        task.transitionTo(TaskStatus.QUEUED);
        assertEquals(TaskStatus.QUEUED, task.getStatus());

        task.transitionTo(TaskStatus.CLAIMED);
        assertEquals(TaskStatus.CLAIMED, task.getStatus());

        task.transitionTo(TaskStatus.CLONING);
        assertEquals(TaskStatus.CLONING, task.getStatus());

        task.transitionTo(TaskStatus.ANALYZING);
        assertEquals(TaskStatus.ANALYZING, task.getStatus());

        task.transitionTo(TaskStatus.PLANNING);
        assertEquals(TaskStatus.PLANNING, task.getStatus());

        task.transitionTo(TaskStatus.IMPLEMENTING);
        assertEquals(TaskStatus.IMPLEMENTING, task.getStatus());

        task.transitionTo(TaskStatus.BUILDING);
        assertEquals(TaskStatus.BUILDING, task.getStatus());

        task.transitionTo(TaskStatus.TESTING);
        assertEquals(TaskStatus.TESTING, task.getStatus());

        task.transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, task.getStatus());

        task.transitionTo(TaskStatus.APPROVED);
        assertEquals(TaskStatus.APPROVED, task.getStatus());

        task.transitionTo(TaskStatus.COMMITTING);
        assertEquals(TaskStatus.COMMITTING, task.getStatus());

        task.transitionTo(TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertTrue(task.getStatus().isTerminal());
        assertNotNull(task.getCompletedAt());
    }

    @Test
    public void testReworkCycle() {
        Task task = new Task("Rework Task", "Rework flow");
        task.transitionTo(TaskStatus.QUEUED);
        task.transitionTo(TaskStatus.CLAIMED);
        task.transitionTo(TaskStatus.IMPLEMENTING);
        task.transitionTo(TaskStatus.WAITING_FOR_REVIEW);

        task.transitionTo(TaskStatus.REWORK_REQUESTED, "Fix formatting issues");
        assertEquals(TaskStatus.REWORK_REQUESTED, task.getStatus());

        task.transitionTo(TaskStatus.REWORKING);
        assertEquals(TaskStatus.REWORKING, task.getStatus());

        task.transitionTo(TaskStatus.BUILDING);
        task.transitionTo(TaskStatus.TESTING);
        task.transitionTo(TaskStatus.WAITING_FOR_REVIEW);
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, task.getStatus());
    }

    @Test
    public void testIllegalTransitions() {
        Task task = new Task("Illegal Task", "Check exceptions");
        assertEquals(TaskStatus.CREATED, task.getStatus());

        // Cannot jump directly from CREATED to COMPLETED
        assertThrows(TrystDomainException.class, () -> task.transitionTo(TaskStatus.COMPLETED));

        // Cannot jump from CREATED to COMMITTING
        assertThrows(TrystDomainException.class, () -> task.transitionTo(TaskStatus.COMMITTING));

        task.transitionTo(TaskStatus.CANCELLED);
        assertTrue(task.getStatus().isTerminal());

        // Cannot transition from terminal state
        assertThrows(TrystDomainException.class, () -> task.transitionTo(TaskStatus.QUEUED));
    }

    @Test
    public void testValidatorDirectly() {
        assertTrue(TaskStateTransitionValidator.isValidTransition(TaskStatus.CREATED, TaskStatus.QUEUED));
        assertTrue(TaskStateTransitionValidator.isValidTransition(TaskStatus.WAITING_FOR_REVIEW, TaskStatus.APPROVED));
        assertTrue(TaskStateTransitionValidator.isValidTransition(TaskStatus.APPROVED, TaskStatus.COMMITTING));
        assertTrue(TaskStateTransitionValidator.isValidTransition(TaskStatus.COMMITTING, TaskStatus.COMPLETED));

        assertFalse(TaskStateTransitionValidator.isValidTransition(TaskStatus.COMPLETED, TaskStatus.RUNNING));
        assertFalse(TaskStateTransitionValidator.isValidTransition(TaskStatus.FAILED, TaskStatus.QUEUED));
    }
}
