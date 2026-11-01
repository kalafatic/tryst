package eu.kalafatic.tryst.ui;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.service.AutonomousDevelopmentService;
import eu.kalafatic.tryst.domain.service.SessionTaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
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
    private ServiceTracker<SessionTaskOrchestrator, SessionTaskOrchestrator> orchestratorTracker;
    private ServiceTracker<AutonomousDevelopmentService, AutonomousDevelopmentService> autonomousTracker;
    private ServiceTracker<AiInferenceService, AiInferenceService> aiTracker;
    private ServiceTracker<EventBus, EventBus> eventBusTracker;
    private ServiceTracker<GitService, GitService> gitTracker;

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

        this.orchestratorTracker = new ServiceTracker<>(context, SessionTaskOrchestrator.class, null);
        this.orchestratorTracker.open();

        this.autonomousTracker = new ServiceTracker<>(context, AutonomousDevelopmentService.class, null);
        this.autonomousTracker.open();

        this.aiTracker = new ServiceTracker<>(context, AiInferenceService.class, null);
        this.aiTracker.open();

        this.eventBusTracker = new ServiceTracker<>(context, EventBus.class, null);
        this.eventBusTracker.open();

        this.gitTracker = new ServiceTracker<>(context, GitService.class, null);
        this.gitTracker.open();

        TrystLog.info(TrystUiPlugin.class, "TrystUiPlugin activated with OSGi ServiceTrackers.");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (workspaceServiceTracker != null) { workspaceServiceTracker.close(); workspaceServiceTracker = null; }
        if (asyncServiceTracker != null) { asyncServiceTracker.close(); asyncServiceTracker = null; }
        if (orchestratorTracker != null) { orchestratorTracker.close(); orchestratorTracker = null; }
        if (autonomousTracker != null) { autonomousTracker.close(); autonomousTracker = null; }
        if (aiTracker != null) { aiTracker.close(); aiTracker = null; }
        if (eventBusTracker != null) { eventBusTracker.close(); eventBusTracker = null; }
        if (gitTracker != null) { gitTracker.close(); gitTracker = null; }

        this.fallbackWorkspaceService = null;
        this.bundleContext = null;
        plugin = null;
        super.stop(context);
    }

    public static TrystUiPlugin getDefault() {
        return plugin;
    }

    public WorkspaceService getWorkspaceService() {
        if (workspaceServiceTracker != null) {
            WorkspaceService service = workspaceServiceTracker.getService();
            if (service != null) return service;
        }
        if (fallbackWorkspaceService == null) {
            synchronized (this) {
                if (fallbackWorkspaceService == null) {
                    fallbackWorkspaceService = new WorkspaceService();
                }
            }
        }
        return fallbackWorkspaceService;
    }

    public AsyncExecutionService getAsyncExecutionService() {
        if (asyncServiceTracker != null) {
            return asyncServiceTracker.getService();
        }
        return null;
    }

    public SessionTaskOrchestrator getSessionTaskOrchestrator() {
        return orchestratorTracker != null ? orchestratorTracker.getService() : null;
    }

    public AutonomousDevelopmentService getAutonomousDevelopmentService() {
        return autonomousTracker != null ? autonomousTracker.getService() : null;
    }

    public AiInferenceService getAiInferenceService() {
        return aiTracker != null ? aiTracker.getService() : null;
    }

    public EventBus getEventBus() {
        return eventBusTracker != null ? eventBusTracker.getService() : null;
    }

    public GitService getGitService() {
        return gitTracker != null ? gitTracker.getService() : null;
    }
}
