package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.TrystException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DomainModelTest {

    @Test
    void testAgentTaskLifecycle() {
        AgentTask task = new AgentTask("Feature A", "Implement feature");
        assertEquals("Feature A", task.getTitle());
        assertEquals("Implement feature", task.getDescription());
        assertEquals(ExecutionStatus.IDLE, task.getStatus());
        assertNull(task.getCompletedAt());

        task.start();
        assertEquals(ExecutionStatus.RUNNING, task.getStatus());

        task.complete();
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getCompletedAt());

        assertThrows(TrystException.class, task::start);
    }

    @Test
    void testAgentTaskFailure() {
        AgentTask task = new AgentTask("Task F", "Failure flow");
        task.start();
        task.fail();
        assertEquals(ExecutionStatus.FAILED, task.getStatus());
        assertNotNull(task.getCompletedAt());
        assertThrows(TrystException.class, task::complete);
    }

    @Test
    void testAgentTaskCancellation() {
        AgentTask task = new AgentTask("Task C", "Cancellation flow");
        task.cancel();
        assertEquals(ExecutionStatus.CANCELLED, task.getStatus());
        assertNotNull(task.getCompletedAt());
    }

    @Test
    void testProjectAndWorkspace() {
        Path root = Path.of("/tmp/tryst-ws");
        Workspace ws = new Workspace(root);
        assertEquals(root, ws.getRootPath());
        assertTrue(ws.getProjects().isEmpty());

        Project p1 = new Project("Proj1", Path.of("/tmp/proj1"));
        Project p2 = new Project("Proj2", Path.of("/tmp/proj2"));
        ws.addProject(p1);
        ws.addProject(p2);
        assertEquals(2, ws.getProjects().size());

        ws.setActiveProject(p1.getId());
        assertTrue(ws.getActiveProject().isPresent());
        assertEquals(p1.getId(), ws.getActiveProject().get().getId());
        assertTrue(p1.isActive());
        assertFalse(p2.isActive());

        ws.removeProject(p1.getId());
        assertEquals(1, ws.getProjects().size());
        assertFalse(ws.getActiveProject().isPresent());
    }

    @Test
    void testConsoleMessageCreation() {
        ConsoleMessage info = ConsoleMessage.info("Test", "Info message");
        assertEquals("INFO", info.level());
        assertEquals("Test", info.source());
        assertEquals("Info message", info.message());
        assertNotNull(info.timestamp());

        ConsoleMessage warn = ConsoleMessage.warn("Test", "Warn message");
        assertEquals("WARN", warn.level());

        ConsoleMessage error = ConsoleMessage.error("Test", "Error message");
        assertEquals("ERROR", error.level());
    }
}
