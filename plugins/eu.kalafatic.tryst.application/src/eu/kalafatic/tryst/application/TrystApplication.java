package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

/**
 * Main application entry point for the TRYST Eclipse RCP Desktop Application.
 * Drives the initial startup lifecycle phases and safely closes the Workbench.
 */
public class TrystApplication implements IApplication {

    @Override
    public Object start(IApplicationContext context) throws Exception {
        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        ApplicationLifecycleCoordinator coordinator = (plugin != null) ? plugin.getLifecycleCoordinator() : null;

        try {
            if (coordinator != null) {
                coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
                coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            }

            Display display = PlatformUI.createDisplay();
            try {
                int returnCode = PlatformUI.createAndRunWorkbench(display, new ApplicationWorkbenchAdvisor(coordinator));
                if (returnCode == PlatformUI.RETURN_RESTART) {
                    return IApplication.EXIT_RESTART;
                }
                return IApplication.EXIT_OK;
            } finally {
                display.dispose();
            }
        } catch (Throwable t) {
            TrystLog.error(TrystApplication.class, "Fatal error during TRYST startup: " + t.getMessage(), t);
            throw t;
        }
    }

    @Override
    public void stop() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        final IWorkbench workbench = PlatformUI.getWorkbench();
        final Display display = workbench.getDisplay();
        display.syncExec(() -> {
            if (!display.isDisposed()) {
                workbench.close();
            }
        });
    }
}
