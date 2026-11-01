package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleEvent;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationLifecycleTest {

    @Test
    void testCompleteLifecycleSequence() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator(null);
        List<ApplicationLifecycleStage> stagesVisited = new ArrayList<>();

        coordinator.addLifecycleListener(event -> stagesVisited.add(event.stage()));

        assertEquals(ApplicationLifecycleStage.APPLICATION_START, coordinator.getCurrentStage());

        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);

        assertEquals(ApplicationLifecycleStage.APPLICATION_TERMINATED, coordinator.getCurrentStage());

        assertEquals(List.of(
                ApplicationLifecycleStage.PLATFORM_INITIALIZATION,
                ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION,
                ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION,
                ApplicationLifecycleStage.APPLICATION_READY,
                ApplicationLifecycleStage.WORKBENCH_RUNNING,
                ApplicationLifecycleStage.APPLICATION_SHUTDOWN,
                ApplicationLifecycleStage.SERVICE_DISPOSAL,
                ApplicationLifecycleStage.APPLICATION_TERMINATED
        ), stagesVisited);
    }

    @Test
    void testInvalidSkippedTransitionFails() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator(null);
        // Attempting to skip directly from APPLICATION_START to APPLICATION_READY
        assertThrows(TrystApplicationException.class, () ->
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY));
    }

    @Test
    void testInvalidReverseTransitionFails() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator(null);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);

        // Attempting to reverse back to APPLICATION_START
        assertThrows(TrystApplicationException.class, () ->
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_START));
    }

    @Test
    void testServiceDisposalCleansResources() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator(null);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);

        assertNotNull(coordinator.getConfiguration());
        assertNotNull(coordinator.getWorkspaceService());
        assertNotNull(coordinator.getAsyncExecutionService());
        assertFalse(coordinator.getAsyncExecutionService().isShutdown());

        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);

        assertNull(coordinator.getWorkspaceService());
        assertNull(coordinator.getConfiguration());
        assertNull(coordinator.getAsyncExecutionService());
    }
}
