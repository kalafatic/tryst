package eu.kalafatic.tryst.ui;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Activator for TRYST UI plugin. Discovers OSGi application/domain services from
 * the OSGi Service Registry rather than instantiating them within UI components.
 */
public class TrystUiPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "eu.kalafatic.tryst.ui";

    private static TrystUiPlugin plugin;
    private BundleContext bundleContext;
    private ServiceTracker<WorkspaceService, WorkspaceService> workspaceServiceTracker;
    private ServiceTracker<AsyncExecutionService, AsyncExecutionService> asyncServiceTracker;

    // Fallback instance for test/standalone environments where OSGi service registry is offline
    private WorkspaceService fallbackWorkspaceService;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        this.bundleContext = context;

        this.workspaceServiceTracker = new ServiceTracker<>(context, WorkspaceService.class, null);
        this.workspaceServiceTracker.open();

        this.asyncServiceTracker = new ServiceTracker<>(context, AsyncExecutionService.class, null);
        this.asyncServiceTracker.open();

        TrystLog.info(TrystUiPlugin.class, "TrystUiPlugin activated with OSGi ServiceTrackers.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (workspaceServiceTracker != null) {
            workspaceServiceTracker.close();
            workspaceServiceTracker = null;
        }
        if (asyncServiceTracker != null) {
            asyncServiceTracker.close();
            asyncServiceTracker = null;
        }
        this.fallbackWorkspaceService = null;
        this.bundleContext = null;
        plugin = null;
        super.stop(context);
    }

    public static TrystUiPlugin getDefault() {
        return plugin;
    }

    /**
     * Obtains the registered WorkspaceService from the OSGi service registry.
     */
    public WorkspaceService getWorkspaceService() {
        if (workspaceServiceTracker != null) {
            WorkspaceService service = workspaceServiceTracker.getService();
            if (service != null) {
                return service;
            }
        }

        // Direct ServiceReference lookup attempt
        if (bundleContext != null) {
            ServiceReference<WorkspaceService> ref = bundleContext.getServiceReference(WorkspaceService.class);
            if (ref != null) {
                return bundleContext.getService(ref);
            }
        }

        // Standalone or testing fallback
        if (fallbackWorkspaceService == null) {
            synchronized (this) {
                if (fallbackWorkspaceService == null) {
                    TrystLog.warn(TrystUiPlugin.class,
                            "WorkspaceService not yet found in OSGi registry; initializing local fallback.");
                    fallbackWorkspaceService = new WorkspaceService();
                }
            }
        }
        return fallbackWorkspaceService;
    }

    /**
     * Obtains the registered AsyncExecutionService from the OSGi service registry.
     */
    public AsyncExecutionService getAsyncExecutionService() {
        if (asyncServiceTracker != null) {
            AsyncExecutionService service = asyncServiceTracker.getService();
            if (service != null) {
                return service;
            }
        }
        if (bundleContext != null) {
            ServiceReference<AsyncExecutionService> ref = bundleContext.getServiceReference(AsyncExecutionService.class);
            if (ref != null) {
                return bundleContext.getService(ref);
            }
        }
        return null;
    }
}
