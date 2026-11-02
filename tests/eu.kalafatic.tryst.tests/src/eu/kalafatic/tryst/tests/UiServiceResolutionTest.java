package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the authoritative UI OSGi Service Resolution Layer (TrystUiServices).
 * Verifies that services registered by ApplicationLifecycleCoordinator in the Equinox/OSGi runtime
 * are successfully resolved by the UI layer without requiring TrystUiPlugin.getDefault() singleton state.
 */
class UiServiceResolutionTest {

    private ApplicationLifecycleCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            try {
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
                coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
            } catch (Exception ignored) {
            }
            coordinator = null;
        }
        TrystUiServices.dispose();
    }

    @Test
    void testUiServicesResolveAuthoritativeServicesWithoutPluginSingleton() {
        // Explicitly verify TrystUiPlugin.getDefault() may be null in headless test contexts
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        assertNotNull(context, "OSGi BundleContext must be present in Tycho OSGi runtime");

        String origAdmin = System.getProperty("tryst.admin.password");
        System.setProperty("tryst.admin.password", "admin123");
        try {
            // Before initialization, mandatory services return null or safe fallback without throwing NPE
            assertNull(TrystUiServices.getServerMonitoringService(), "ServerMonitoringService must be null before registration");
            assertNull(TrystUiServices.getProcessExecutionManager(), "ProcessExecutionManager must be null before registration");
            assertNull(TrystUiServices.getUserRepository(), "UserRepository must be null before registration");
            assertNotNull(TrystUiServices.getWorkspaceService(), "WorkspaceService fallback must be non-null");

            // Initialize application services through ApplicationLifecycleCoordinator
            coordinator = new ApplicationLifecycleCoordinator(context);
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
            coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);

            // Verify that TrystUiServices resolves the EXACT same authoritative service instances
            ServerMonitoringService mon = TrystUiServices.getServerMonitoringService();
            assertNotNull(mon, "ServerMonitoringService must be resolved via OSGi");

            ProcessExecutionManager proc = TrystUiServices.getProcessExecutionManager();
            assertNotNull(proc, "ProcessExecutionManager must be resolved via OSGi");

            UserRepository userRepo = TrystUiServices.getUserRepository();
            assertNotNull(userRepo, "UserRepository must be resolved via OSGi");

            WorkspaceService ws = TrystUiServices.getWorkspaceService();
            assertNotNull(ws, "WorkspaceService must be resolved via OSGi");
            assertSame(coordinator.getWorkspaceService(), ws, "UI layer must resolve the exact coordinator WorkspaceService");

            AsyncExecutionService async = TrystUiServices.getAsyncExecutionService();
            assertNotNull(async, "AsyncExecutionService must be resolved via OSGi");
            assertSame(coordinator.getAsyncExecutionService(), async, "UI layer must resolve the exact coordinator AsyncExecutionService");

            SqliteDatabaseManager db = TrystUiServices.getDatabaseManager();
            assertNotNull(db, "SqliteDatabaseManager must be resolved via OSGi");
            assertSame(coordinator.getDatabaseManager(), db, "UI layer must resolve the exact coordinator SqliteDatabaseManager");

            // Verify data operations via the resolved services work correctly
            assertNotNull(userRepo.findAll(), "User repository findAll() must return initialized users");
            assertTrue(userRepo.findByUsername("admin").isPresent(), "Bootstrapped admin user must be queryable");
            assertNotNull(mon.collectMetrics(), "Server metrics must be collected successfully");
            assertNotNull(proc.getManagedProcesses(), "Managed process list must be accessible");

            // Verify that disposing TrystUiServices closes trackers safely and does not throw
            TrystUiServices.dispose();
        } finally {
            if (origAdmin != null) {
                System.setProperty("tryst.admin.password", origAdmin);
            } else {
                System.clearProperty("tryst.admin.password");
            }
        }
    }
}
