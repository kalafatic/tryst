package eu.kalafatic.tryst.application;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Activator for the TRYST application bundle controlling OSGi lifecycle.
 */
public class TrystApplicationPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "eu.kalafatic.tryst.application";

    private static TrystApplicationPlugin plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static TrystApplicationPlugin getDefault() {
        return plugin;
    }
}
