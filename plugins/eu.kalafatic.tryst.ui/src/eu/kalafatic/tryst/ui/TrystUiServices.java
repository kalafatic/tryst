package eu.kalafatic.tryst.ui;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
import eu.kalafatic.tryst.domain.repository.AuditEventRepository;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.NotificationRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.TelemetryRepository;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.AiMonitoringService;
import eu.kalafatic.tryst.domain.service.AutonomousDevelopmentService;
import eu.kalafatic.tryst.domain.service.LogDiagnosticService;
import eu.kalafatic.tryst.domain.service.NotificationService;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.domain.service.SessionTaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TelemetryService;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative OSGi Service Resolution Layer for the TRYST UI bundle.
 * Discovers and tracks services directly from the Equinox/OSGi Service Registry
 * without requiring reliance on {@code TrystUiPlugin.getDefault()} static activator lifecycle.
 */
public final class TrystUiServices {

    private static final Map<Class<?>, ServiceTracker<?, ?>> TRACKERS = new ConcurrentHashMap<>();
    private static volatile WorkspaceService fallbackWorkspaceService;

    private TrystUiServices() {
    }

    /**
     * Obtains the authoritative OSGi {@link BundleContext} for the TRYST UI bundle.
     * Evaluates {@link FrameworkUtil#getBundle(Class)} dynamically, with fallback to
     * {@link TrystUiPlugin#getDefault()}'s bundle context if available.
     *
     * @return an {@link Optional} containing the {@link BundleContext}, or empty if running outside OSGi.
     */
    public static Optional<BundleContext> getBundleContext() {
        Bundle bundle = FrameworkUtil.getBundle(TrystUiServices.class);
        if (bundle != null && bundle.getBundleContext() != null) {
            return Optional.of(bundle.getBundleContext());
        }
        TrystUiPlugin plugin = TrystUiPlugin.getDefault();
        if (plugin != null && plugin.getBundle() != null && plugin.getBundle().getBundleContext() != null) {
            return Optional.of(plugin.getBundle().getBundleContext());
        }
        // Fallback check: if FrameworkUtil on plugin class yields context
        Bundle pluginBundle = FrameworkUtil.getBundle(TrystUiPlugin.class);
        if (pluginBundle != null && pluginBundle.getBundleContext() != null) {
            return Optional.of(pluginBundle.getBundleContext());
        }
        return Optional.empty();
    }

    /**
     * Resolves a service of type {@code S} directly from the OSGi Service Registry
     * via an open {@link ServiceTracker}.
     *
     * @param serviceClass the class representing the required OSGi service
     * @param <S> the service type
     * @return the service instance, or {@code null} if not registered or if outside OSGi runtime
     */
    @SuppressWarnings("unchecked")
    public static <S> S getService(Class<S> serviceClass) {
        if (serviceClass == null) {
            return null;
        }

        Optional<BundleContext> ctxOpt = getBundleContext();
        if (ctxOpt.isEmpty()) {
            return null;
        }

        ServiceTracker<S, S> tracker = (ServiceTracker<S, S>) TRACKERS.computeIfAbsent(serviceClass, clazz -> {
            ServiceTracker<S, S> st = new ServiceTracker<>(ctxOpt.get(), (Class<S>) clazz, null);
            st.open();
            return st;
        });

        S service = tracker.getService();
        if (service != null) {
            return service;
        }

        // Direct lookup fallback if tracker hasn't caught up
        BundleContext context = ctxOpt.get();
        ServiceReference<S> ref = context.getServiceReference(serviceClass);
        if (ref != null) {
            return context.getService(ref);
        }

        return null;
    }

    /**
     * Closes all active service trackers and cleans up cached state.
     */
    public static synchronized void dispose() {
        for (ServiceTracker<?, ?> tracker : TRACKERS.values()) {
            try {
                tracker.close();
            } catch (Exception e) {
                TrystLog.warn(TrystUiServices.class, "Error closing ServiceTracker: " + e.getMessage());
            }
        }
        TRACKERS.clear();
        fallbackWorkspaceService = null;
    }

    // --- Strongly typed accessor convenience methods ---

    public static WorkspaceService getWorkspaceService() {
        WorkspaceService ws = getService(WorkspaceService.class);
        if (ws != null) {
            return ws;
        }
        if (fallbackWorkspaceService == null) {
            synchronized (TrystUiServices.class) {
                if (fallbackWorkspaceService == null) {
                    fallbackWorkspaceService = new WorkspaceService();
                }
            }
        }
        return fallbackWorkspaceService;
    }

    public static AsyncExecutionService getAsyncExecutionService() {
        return getService(AsyncExecutionService.class);
    }

    public static SessionTaskOrchestrator getSessionTaskOrchestrator() {
        return getService(SessionTaskOrchestrator.class);
    }

    public static AutonomousDevelopmentService getAutonomousDevelopmentService() {
        return getService(AutonomousDevelopmentService.class);
    }

    public static AiInferenceService getAiInferenceService() {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            var ai = appCoord.getAiInferenceService();
            if (ai != null) {
                return ai;
            }
        }
        return getService(AiInferenceService.class);
    }

    public static eu.kalafatic.tryst.domain.ai.AiModelRegistryService getAiModelRegistryService() {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            var reg = appCoord.getAiModelRegistryService();
            if (reg != null) {
                return reg;
            }
        }
        return getService(eu.kalafatic.tryst.domain.ai.AiModelRegistryService.class);
    }

    public static EventBus getEventBus() {
        return getService(EventBus.class);
    }

    public static GitService getGitService() {
        return getService(GitService.class);
    }

    public static eu.kalafatic.tryst.domain.github.GitHubService getGitHubService() {
        return getService(eu.kalafatic.tryst.domain.github.GitHubService.class);
    }

    public static ServerMonitoringService getServerMonitoringService() {
        return getService(ServerMonitoringService.class);
    }

    public static TelemetryService getTelemetryService() {
        return getService(TelemetryService.class);
    }

    public static ProcessExecutionManager getProcessExecutionManager() {
        return getService(ProcessExecutionManager.class);
    }

    public static UserRepository getUserRepository() {
        return getService(UserRepository.class);
    }

    public static UserSessionRepository getUserSessionRepository() {
        return getService(UserSessionRepository.class);
    }

    public static AuditEventRepository getAuditEventRepository() {
        return getService(AuditEventRepository.class);
    }

    public static SqliteDatabaseManager getDatabaseManager() {
        return getService(SqliteDatabaseManager.class);
    }

    public static AiMonitoringService getAiMonitoringService() {
        return getService(AiMonitoringService.class);
    }

    public static NotificationService getNotificationService() {
        return getService(NotificationService.class);
    }

    public static LogDiagnosticService getLogDiagnosticService() {
        return getService(LogDiagnosticService.class);
    }

    public static AuthenticationService getAuthenticationService() {
        return getService(AuthenticationService.class);
    }

    public static PermissionManager getPermissionManager() {
        return getService(PermissionManager.class);
    }

    public static ProjectRepository getProjectRepository() {
        return getService(ProjectRepository.class);
    }

    public static TaskRepository getTaskRepository() {
        return getService(TaskRepository.class);
    }

    public static WorkspaceRepository getWorkspaceRepository() {
        return getService(WorkspaceRepository.class);
    }

    public static SessionRepository getSessionRepository() {
        return getService(SessionRepository.class);
    }

    public static ConversationMessageRepository getConversationMessageRepository() {
        return getService(ConversationMessageRepository.class);
    }

    public static ExecutionResultRepository getExecutionResultRepository() {
        return getService(ExecutionResultRepository.class);
    }

    public static AgentRepository getAgentRepository() {
        return getService(AgentRepository.class);
    }

    public static CodeReviewRepository getCodeReviewRepository() {
        return getService(CodeReviewRepository.class);
    }

    public static TelemetryRepository getTelemetryRepository() {
        return getService(TelemetryRepository.class);
    }

    public static NotificationRepository getNotificationRepository() {
        return getService(NotificationRepository.class);
    }

    public static CodeReviewService getCodeReviewService() {
        return getService(CodeReviewService.class);
    }

    public static ApplicationConfiguration getConfiguration() {
        return getService(ApplicationConfiguration.class);
    }

    public static ConfigurationService getConfigurationService() {
        return getService(ConfigurationService.class);
    }

    public static AiContextManager getAiContextManager() {
        return getService(AiContextManager.class);
    }

    public static eu.kalafatic.tryst.core.lifecycle.ServerReadinessService getServerReadinessService() {
        return getService(eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.class);
    }

    public static eu.kalafatic.tryst.domain.service.ManagedServiceController getManagedServiceController() {
        return getService(eu.kalafatic.tryst.domain.service.ManagedServiceController.class);
    }

    public static eu.kalafatic.tryst.core.server.TrystServerLifecycleService getServerLifecycleService() {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            return appCoord;
        }
        return getService(eu.kalafatic.tryst.core.server.TrystServerLifecycleService.class);
    }

    public static eu.kalafatic.tryst.core.server.TrystHttpService getHttpService() {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            var s = appCoord.getHttpService();
            if (s != null && s.isRunning()) {
                return s;
            }
        }
        var lifecycle = getServerLifecycleService();
        if (lifecycle != null) {
            var s = lifecycle.getHttpService();
            if (s != null && s.isRunning()) {
                return s;
            }
        }
        var s = getService(eu.kalafatic.tryst.core.server.TrystHttpService.class);
        if (s != null) {
            return s;
        }
        return null;
    }

    public static eu.kalafatic.tryst.core.server.TrystTcpService getTcpService() {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null && appCoord.getTcpService() != null) {
            return appCoord.getTcpService();
        }
        return getService(eu.kalafatic.tryst.core.server.TrystTcpService.class);
    }

    /**
     * Triggers real HTTP server startup through the authoritative server lifecycle service.
     */
    public static synchronized eu.kalafatic.tryst.core.server.TrystHttpService startHttpServer() throws Exception {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            return appCoord.startHttpServer();
        }

        var lifecycle = getServerLifecycleService();
        if (lifecycle != null) {
            return lifecycle.startHttpServer();
        }

        var current = getHttpService();
        if (current != null) {
            if (!current.isRunning()) {
                current.start();
            }
            return current;
        }

        throw new IllegalStateException("Server lifecycle service is not available. Cannot start HTTP server.");
    }

    /**
     * Triggers real HTTP server shutdown through the authoritative server lifecycle service.
     */
    public static synchronized void stopHttpServer() throws Exception {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            appCoord.stopHttpServer();
            return;
        }

        var lifecycle = getServerLifecycleService();
        if (lifecycle != null) {
            lifecycle.stopHttpServer();
            return;
        }
        var current = getHttpService();
        if (current != null && current.isRunning()) {
            current.stop();
        }
    }

    /**
     * Triggers real HTTP server restart through the authoritative server lifecycle service.
     */
    public static synchronized eu.kalafatic.tryst.core.server.TrystHttpService restartHttpServer() throws Exception {
        var appCoord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (appCoord != null) {
            return appCoord.restartHttpServer();
        }

        var lifecycle = getServerLifecycleService();
        if (lifecycle != null) {
            return lifecycle.restartHttpServer();
        }
        stopHttpServer();
        return startHttpServer();
    }
}
