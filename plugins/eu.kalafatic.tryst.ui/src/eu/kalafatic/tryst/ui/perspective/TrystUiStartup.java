package eu.kalafatic.tryst.ui.perspective;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

/**
 * Standard Eclipse RCP early startup hook for the TRYST UI bundle.
 * Executes automatically after the Workbench and its SWT Display are fully created,
 * safely attaching perspective and window listeners and auto-opening the central
 * MultiPage Editor without reflection or premature Display initialization.
 */
public class TrystUiStartup implements IStartup {

    @Override
    public void earlyStartup() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }

        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            try {
                TrystUiPlugin plugin = TrystUiPlugin.getDefault();
                if (plugin != null) {
                    plugin.registerPerspectiveLifecycleHook(workbench);
                }
                TrystLog.info(TrystUiStartup.class, "TRYST UI early startup completed successfully.");
            } catch (Throwable t) {
                TrystLog.error(TrystUiStartup.class, "Error during TRYST UI early startup: " + t.getMessage(), t);
            }
        });
    }
}
