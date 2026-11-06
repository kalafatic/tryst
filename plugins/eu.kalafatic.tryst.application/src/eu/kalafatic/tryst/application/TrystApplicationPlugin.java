package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Activator for the TRYST application bundle controlling OSGi lifecycle and
 * lifecycle coordinator access.
 */
public class TrystApplicationPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "eu.kalafatic.tryst.application";

    private static TrystApplicationPlugin plugin;
    private ApplicationLifecycleCoordinator lifecycleCoordinator;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        this.lifecycleCoordinator = new ApplicationLifecycleCoordinator(context);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (lifecycleCoordinator != null) {
            lifecycleCoordinator = null;
        }
        plugin = null;
        eu.kalafatic.tryst.core.log.TrystLog.shutdown();
        super.stop(context);
    }

    public static TrystApplicationPlugin getDefault() {
        return plugin;
    }

    public ApplicationLifecycleCoordinator getLifecycleCoordinator() {
        return lifecycleCoordinator;
    }
}
