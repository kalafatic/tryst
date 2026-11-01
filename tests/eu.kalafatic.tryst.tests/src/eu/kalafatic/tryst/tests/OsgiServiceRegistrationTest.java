package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import static org.junit.jupiter.api.Assertions.*;

class OsgiServiceRegistrationTest {

    @Test
    void testServiceRegistrationInOsgiRuntime() {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        assertNotNull(context, "OSGi BundleContext must be present in Tycho Surefire OSGi runtime");

        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator(context);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);

        // Verify services are present and registered in the OSGi registry
        ServiceReference<WorkspaceService> wsRef = context.getServiceReference(WorkspaceService.class);
        assertNotNull(wsRef, "WorkspaceService should be registered in OSGi");
        WorkspaceService ws = context.getService(wsRef);
        assertNotNull(ws);
        assertSame(coordinator.getWorkspaceService(), ws);

        ServiceReference<AsyncExecutionService> asyncRef = context.getServiceReference(AsyncExecutionService.class);
        assertNotNull(asyncRef, "AsyncExecutionService should be registered in OSGi");
        AsyncExecutionService async = context.getService(asyncRef);
        assertNotNull(async);
        assertSame(coordinator.getAsyncExecutionService(), async);

        ServiceReference<ApplicationConfiguration> configRef = context.getServiceReference(ApplicationConfiguration.class);
        assertNotNull(configRef, "ApplicationConfiguration should be registered in OSGi");
        ApplicationConfiguration config = context.getService(configRef);
        assertNotNull(config);
        assertSame(coordinator.getConfiguration(), config);

        // Advance to disposal
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);

        // After disposal, services must be unregistered
        ServiceReference<WorkspaceService> wsRefAfter = context.getServiceReference(WorkspaceService.class);
        assertNull(wsRefAfter, "WorkspaceService must be unregistered after service disposal");

        ServiceReference<AsyncExecutionService> asyncRefAfter = context.getServiceReference(AsyncExecutionService.class);
        assertNull(asyncRefAfter, "AsyncExecutionService must be unregistered after service disposal");

        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
    }
}
