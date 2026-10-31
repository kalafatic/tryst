package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.controller.ConsoleOutputController;
import eu.kalafatic.tryst.ui.controller.ProjectExplorerController;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies MVC separation: Controllers interact with application services/domain
 * without depending directly on SWT widgets.
 */
class MvcSeparationTest {

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(Path.of("/tmp/mvc-test-workspace"));
    }

    @Test
    void testProjectExplorerController() {
        ProjectExplorerController controller = new ProjectExplorerController(workspaceService);
        AtomicBoolean refreshed = new AtomicBoolean(false);
        controller.setViewCallback(() -> refreshed.set(true));

        controller.handleAddProject("MvcProject", Path.of("/tmp/mvc-project"));
        assertTrue(refreshed.get());
        assertEquals(1, controller.getProjects().size());
        assertEquals("MvcProject", controller.getProjects().get(0).getName());

        Project p = controller.getProjects().get(0);
        controller.handleProjectSelection(p);
        assertTrue(p.isActive());

        controller.dispose();
    }

    @Test
    void testWorkspaceController() {
        WorkspaceController controller = new WorkspaceController(workspaceService);
        AtomicBoolean refreshed = new AtomicBoolean(false);
        controller.setViewCallback(() -> refreshed.set(true));

        controller.handleCreateTask("Controller Task", "Task via controller");
        assertTrue(refreshed.get());
        assertEquals(1, controller.getTasks().size());
        assertEquals("Controller Task", controller.getTasks().get(0).getTitle());

        controller.dispose();
    }

    @Test
    void testConsoleOutputController() {
        ConsoleOutputController controller = new ConsoleOutputController(workspaceService);
        AtomicReference<ConsoleMessage> received = new AtomicReference<>();
        controller.setViewCallback(received::set);

        controller.log("INFO", "ControllerSource", "Message from controller");
        assertNotNull(received.get());
        assertEquals("ControllerSource", received.get().source());
        assertEquals("Message from controller", received.get().message());

        controller.dispose();
    }
}
