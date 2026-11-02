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

    private final TrystServerApplication headlessDelegate = new TrystServerApplication();
    private volatile boolean isHeadlessMode = false;

    public static boolean isHeadlessRequested(IApplicationContext context) {
        // 1. Check system properties
        String sysProp = System.getProperty("tryst.headless");
        if (sysProp != null && (sysProp.equalsIgnoreCase("true") || sysProp.equalsIgnoreCase("yes") || sysProp.equals("1"))) {
            return true;
        }

        // 2. Check application arguments from context
        if (context != null && context.getArguments() != null) {
            Object argsObj = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
            if (argsObj instanceof String[] args) {
                for (String arg : args) {
                    if ("-headless".equalsIgnoreCase(arg) || "--headless".equalsIgnoreCase(arg)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        this.isHeadlessMode = isHeadlessRequested(context);

        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        ApplicationLifecycleCoordinator coordinator = (plugin != null) ? plugin.getLifecycleCoordinator() : null;

        if (isHeadlessMode) {
            TrystLog.info(TrystApplication.class, "Startup Mode: HEADLESS (bypassing SWT Display and Workbench)");
            return headlessDelegate.runHeadless(context, coordinator);
        }

        TrystLog.info(TrystApplication.class, "Startup Mode: STANDARD ECLIPSE RCP (initializing SWT Display and Workbench)");
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
        if (isHeadlessMode) {
            headlessDelegate.stop();
            return;
        }
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
