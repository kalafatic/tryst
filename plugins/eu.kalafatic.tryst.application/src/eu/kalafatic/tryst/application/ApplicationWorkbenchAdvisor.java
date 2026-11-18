package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/**
 * Workbench advisor managing application lifecycle, window advisors, and default perspective.
 * Orchestrates deterministic lifecycle transitions in sync with Workbench events.
 */
public class ApplicationWorkbenchAdvisor extends WorkbenchAdvisor {

    public static final String PERSPECTIVE_ID = "eu.kalafatic.tryst.ui.perspective.main";

    private ApplicationLifecycleCoordinator coordinator;

    public ApplicationWorkbenchAdvisor() {
        this(resolveCoordinator());
    }

    public ApplicationWorkbenchAdvisor(ApplicationLifecycleCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public static ApplicationLifecycleCoordinator resolveCoordinator() {
        ApplicationLifecycleCoordinator coord = TrystApplication.getCoordinator();
        if (coord != null) {
            return coord;
        }
        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        if (plugin != null && plugin.getLifecycleCoordinator() != null) {
            return plugin.getLifecycleCoordinator();
        }
        return null;
    }

    @Override
    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        if (coordinator == null) {
            coordinator = resolveCoordinator();
        }
        return new ApplicationWorkbenchWindowAdvisor(configurer, coordinator);
    }

    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
        configurer.setSaveAndRestore(true);

        // The Workbench creates the splash handler before invoking this advisor, so the
        // deterministic TRYST lifecycle startup runs here: every milestone transition is
        // reported as real progress on the splash screen while the services come up before
        // the first workbench window opens.
        if (coordinator == null) {
            ApplicationLifecycleCoordinator resolved = resolveCoordinator();
            if (resolved != null) {
                this.coordinator = resolved;
            }
        }

        if (coordinator != null) {
            try {
                if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.PLATFORM_INITIALIZATION.ordinal()) {
                    coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
                }
                if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION.ordinal()) {
                    coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
                }
                if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION.ordinal()) {
                    coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
                }
                if (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.APPLICATION_READY.ordinal()) {
                    coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
                }
                if (!coordinator.isHttpServerRunning()) {
                    try {
                        coordinator.startHttpServer();
                    } catch (Exception e) {
                        TrystLog.error(ApplicationWorkbenchAdvisor.class, "Failed to start HTTP server during initialization: " + e.getMessage(), e);
                    }
                }
                TrystLog.info(ApplicationWorkbenchAdvisor.class, "TRYST core initialization complete. Application is READY.");
            } catch (Throwable t) {
                // WorkbenchAdvisor.initialize() runs inside StartupThreading, which swallows
                // exceptions. A failed core initialization must never leave the application in
                // a half-initialized state with a frozen splash screen.
                TrystLog.error(ApplicationWorkbenchAdvisor.class, "Fatal error during TRYST core initialization: " + t.getMessage(), t);
                eu.kalafatic.tryst.application.splash.TrystSplashHandler.closeSplashIfVisible();
                try {
                    configurer.emergencyClose();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void postStartup() {
        super.postStartup();
        if (coordinator != null) {
            safeTransition(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        }
    }

    @Override
    public boolean preShutdown() {
        if (coordinator != null) {
            safeTransition(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        }
        return super.preShutdown();
    }

    @Override
    public void postShutdown() {
        if (coordinator != null) {
            safeTransition(ApplicationLifecycleStage.SERVICE_DISPOSAL);
            safeTransition(ApplicationLifecycleStage.APPLICATION_TERMINATED);
        }
        super.postShutdown();
    }

    private void safeTransition(ApplicationLifecycleStage target) {
        try {
            coordinator.transitionTo(target);
        } catch (Throwable t) {
            TrystLog.error(ApplicationWorkbenchAdvisor.class,
                    "Lifecycle transition to " + target + " failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void eventLoopException(Throwable exception) {
        TrystLog.error(ApplicationWorkbenchAdvisor.class, "Unhandled Workbench UI exception: " + exception.getMessage(), exception);
        super.eventLoopException(exception);
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return PERSPECTIVE_ID;
    }
}
