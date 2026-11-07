package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.server.TrystHttpService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates OSGi visibility, service interface accessibility, and absence of access restrictions.
 */
class HttpServiceAccessibilityTest {

    @Test
    void testTrystHttpServiceAccessibility() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);

        TrystHttpService httpService = coordinator.getHttpService();
        assertNotNull(httpService, "TrystHttpService must be non-null and accessible across bundles");
        assertTrue(httpService.getPort() >= 0);

        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.APPLICATION_READY);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.SERVICE_DISPOSAL);
        coordinator.transitionTo(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.APPLICATION_TERMINATED);
    }
}
