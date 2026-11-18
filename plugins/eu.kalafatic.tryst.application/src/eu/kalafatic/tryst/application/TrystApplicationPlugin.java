package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

/**
 * Activator for the TRYST application bundle controlling OSGi lifecycle and
 * lifecycle coordinator access.
 */
public class TrystApplicationPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "eu.kalafatic.tryst.application";

    private static TrystApplicationPlugin plugin;
    private ApplicationLifecycleCoordinator lifecycleCoordinator;
    private eu.kalafatic.tryst.application.help.TrystUpdateRepositoryRegistrar updateRepositoryRegistrar;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        publishBrandingSystemProperties();
        this.lifecycleCoordinator = new ApplicationLifecycleCoordinator(context);
        // Register the local update repository (<install>/updates) when present.
        this.updateRepositoryRegistrar =
                new eu.kalafatic.tryst.application.help.TrystUpdateRepositoryRegistrar(context);
        this.updateRepositoryRegistrar.start();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (updateRepositoryRegistrar != null) {
            updateRepositoryRegistrar.stop();
            updateRepositoryRegistrar = null;
        }
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

    /**
     * Publishes the dynamically resolved TRYST product version, build id and Eclipse platform
     * version as system properties. The About dialog resolves these values through the
     * {@code about.mappings} mechanism so no version information is ever hardcoded in the
     * branding text.
     */
    private static void publishBrandingSystemProperties() {
        try {
            String bundleVersion = null;
            try {
                Bundle bundle = Platform.getBundle(PLUGIN_ID);
                if (bundle != null) {
                    bundleVersion = bundle.getHeaders().get("Bundle-Version");
                    if (bundleVersion == null || bundleVersion.isBlank()) {
                        Version v = bundle.getVersion();
                        if (v != null) {
                            bundleVersion = v.toString();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            if (bundleVersion != null && !bundleVersion.isBlank()) {
                String productVersion = bundleVersion;
                String buildId = "unknown";
                if (productVersion.endsWith(".qualifier")) {
                    productVersion = productVersion.substring(0, productVersion.length() - ".qualifier".length());
                } else {
                    int lastDot = productVersion.lastIndexOf('.');
                    if (lastDot > 0) {
                        buildId = productVersion.substring(lastDot + 1);
                        productVersion = productVersion.substring(0, lastDot);
                    }
                }
                System.setProperty("tryst.product.version", productVersion);
                System.setProperty("tryst.build.id", buildId);
            }

            try {
                Bundle runtime = Platform.getBundle("org.eclipse.core.runtime");
                if (runtime != null && runtime.getVersion() != null) {
                    System.setProperty("tryst.eclipse.version", runtime.getVersion().toString());
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // Branding properties are informational; never break bundle activation.
        }
    }
}
