package eu.kalafatic.tryst.ui;

import eu.kalafatic.tryst.domain.service.WorkspaceService;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Activator for TRYST UI plugin, managing UI-scoped services and lifecycle.
 */
public class TrystUiPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "eu.kalafatic.tryst.ui";

    private static TrystUiPlugin plugin;
    private WorkspaceService workspaceService;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        this.workspaceService = new WorkspaceService();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        this.workspaceService = null;
        plugin = null;
        super.stop(context);
    }

    public static TrystUiPlugin getDefault() {
        return plugin;
    }

    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }
}
