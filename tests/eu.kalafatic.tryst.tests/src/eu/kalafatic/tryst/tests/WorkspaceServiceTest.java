package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceServiceTest {

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(Path.of("/tmp/test-workspace"));
    }

    @Test
    void testAddProjectNotifiesListener() {
        AtomicReference<Project> added = new AtomicReference<>();
        service.addChangeListener(new WorkspaceChangeListener() {
            @Override
            public void onProjectAdded(Project project) {
                added.set(project);
            }
        });

        Project p = service.addProject("TestProject", Path.of("/tmp/test-project"));
        assertNotNull(added.get());
        assertEquals(p.getId(), added.get().getId());
        assertEquals("TestProject", added.get().getName());
    }

    @Test
    void testCreateTaskNotifiesListener() {
        AtomicReference<AgentTask> created = new AtomicReference<>();
        service.addChangeListener(new WorkspaceChangeListener() {
            @Override
            public void onTaskAdded(AgentTask task) {
                created.set(task);
            }
        });

        AgentTask task = service.createTask("Task 1", "Desc 1");
        assertNotNull(created.get());
        assertEquals(task.getId(), created.get().getId());
    }

    @Test
    void testLogging() {
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        service.addChangeListener(new WorkspaceChangeListener() {
            @Override
            public void onMessageLogged(ConsoleMessage message) {
                if ("LogTest".equals(message.source())) {
                    messageReceived.set(true);
                }
            }
        });

        service.logMessage(ConsoleMessage.info("LogTest", "Sample log"));
        assertTrue(messageReceived.get());
        assertTrue(service.getConsoleMessages().stream().anyMatch(m -> "LogTest".equals(m.source())));
    }
}
