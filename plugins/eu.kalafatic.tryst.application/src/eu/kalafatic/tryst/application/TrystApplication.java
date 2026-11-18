package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
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

    private static volatile TrystApplication instance;
    private static volatile ApplicationLifecycleCoordinator coordinator;

    private final TrystServerApplication headlessDelegate = new TrystServerApplication();
    private volatile boolean isHeadlessMode = false;

    public static TrystApplication getInstance() {
        return instance;
    }

    public static ApplicationLifecycleCoordinator getCoordinator() {
        if (coordinator == null) {
            TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
            if (plugin != null) {
                coordinator = plugin.getLifecycleCoordinator();
            }
        }
        return coordinator;
    }

    public static eu.kalafatic.tryst.core.server.TrystHttpService getHttpService() {
        ApplicationLifecycleCoordinator coord = getCoordinator();
        return coord != null ? coord.getHttpService() : null;
    }

    public static void setCoordinator(ApplicationLifecycleCoordinator coord) {
        coordinator = coord;
    }

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
        instance = this;
        this.isHeadlessMode = isHeadlessRequested(context);

        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        ApplicationLifecycleCoordinator coord = (plugin != null) ? plugin.getLifecycleCoordinator() : null;
        if (coord == null) {
            coord = new ApplicationLifecycleCoordinator();
        }
        coordinator = coord;

        if (isHeadlessMode) {
            TrystLog.info(TrystApplication.class, "Startup Mode: HEADLESS (bypassing SWT Display and Workbench)");
            return headlessDelegate.runHeadless(context, coordinator);
        }

        String launchId = java.util.UUID.randomUUID().toString().substring(0, 8);
        TrystLog.info(TrystApplication.class, "=======================================================");
        TrystLog.info(TrystApplication.class, " TRYST APPLICATION ENTRY POINT [AppId: eu.kalafatic.tryst.application.application]");
        TrystLog.info(TrystApplication.class, " Launch ID    : " + launchId);
        TrystLog.info(TrystApplication.class, " Startup Mode : STANDARD ECLIPSE RCP");
        TrystLog.info(TrystApplication.class, "=======================================================");

        try {
            // The splash handler is created by the Workbench during its initialization, before
            // WorkbenchAdvisor.initialize() runs. The lifecycle coordinator startup stages are
            // therefore advanced from ApplicationWorkbenchAdvisor.initialize() so that every
            // startup milestone is reported as real progress on the splash screen.
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
            eu.kalafatic.tryst.application.splash.TrystSplashHandler.closeSplashIfVisible();
            throw t;
        } finally {
            if (coordinator != null && coordinator.isHttpServerRunning()) {
                try {
                    coordinator.stopHttpServer();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void stop() {
        if (isHeadlessMode) {
            headlessDelegate.stop();
            return;
        }
        if (coordinator != null && coordinator.isHttpServerRunning()) {
            try {
                coordinator.stopHttpServer();
            } catch (Throwable ignored) {
            }
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
