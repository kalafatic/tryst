package eu.kalafatic.tryst.application.lifecycle;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.concurrent.ManagedAsyncExecutionService;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleEvent;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleListener;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Authoritative coordinator for the TRYST application lifecycle and OSGi service management.
 * Enforces the deterministic startup and shutdown sequence:
 *
 * APPLICATION_START -> PLATFORM_INITIALIZATION -> CONFIGURATION_INITIALIZATION ->
 * CORE_SERVICE_INITIALIZATION -> APPLICATION_READY -> WORKBENCH_RUNNING ->
 * APPLICATION_SHUTDOWN -> SERVICE_DISPOSAL -> APPLICATION_TERMINATED
 */
public class ApplicationLifecycleCoordinator {

    private final BundleContext bundleContext;
    private ApplicationLifecycleStage currentStage = ApplicationLifecycleStage.APPLICATION_START;
    private final List<ApplicationLifecycleListener> listeners = new CopyOnWriteArrayList<>();

    // Registered core services and their OSGi registrations
    private ApplicationConfiguration configuration;
    private WorkspaceService workspaceService;
    private AsyncExecutionService asyncExecutionService;
    private final List<ServiceRegistration<?>> serviceRegistrations = new ArrayList<>();

    public ApplicationLifecycleCoordinator(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public synchronized ApplicationLifecycleStage getCurrentStage() {
        return currentStage;
    }

    public void addLifecycleListener(ApplicationLifecycleListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeLifecycleListener(ApplicationLifecycleListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized void transitionTo(ApplicationLifecycleStage targetStage) {
        Objects.requireNonNull(targetStage, "targetStage must not be null");
        validateTransition(currentStage, targetStage);

        TrystLog.info(ApplicationLifecycleCoordinator.class,
                "Lifecycle transition: " + currentStage + " -> " + targetStage);
        this.currentStage = targetStage;
        notifyListeners(ApplicationLifecycleEvent.of(targetStage));

        executeStageActions(targetStage);
    }

    private void executeStageActions(ApplicationLifecycleStage stage) {
        switch (stage) {
            case PLATFORM_INITIALIZATION -> onPlatformInitialization();
            case CONFIGURATION_INITIALIZATION -> onConfigurationInitialization();
            case CORE_SERVICE_INITIALIZATION -> onCoreServiceInitialization();
            case APPLICATION_READY -> onApplicationReady();
            case WORKBENCH_RUNNING -> onWorkbenchRunning();
            case APPLICATION_SHUTDOWN -> onApplicationShutdown();
            case SERVICE_DISPOSAL -> onServiceDisposal();
            case APPLICATION_TERMINATED -> onApplicationTerminated();
            default -> {}
        }
    }

    private void onPlatformInitialization() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Platform initialization complete.");
    }

    private void onConfigurationInitialization() {
        this.configuration = ApplicationConfiguration.createDefault();
        TrystLog.info(ApplicationLifecycleCoordinator.class,
                "Configuration initialized for application: " + configuration.applicationName() +
                        " (workspace: " + configuration.defaultWorkspaceDirectory() + ")");
    }

    private void onCoreServiceInitialization() {
        if (configuration == null) {
            this.configuration = ApplicationConfiguration.createDefault();
        }

        // Initialize core application & domain services
        this.workspaceService = new WorkspaceService(configuration.defaultWorkspaceDirectory());
        this.asyncExecutionService = new ManagedAsyncExecutionService();

        // Register into standard OSGi Service Registry if BundleContext is available
        if (bundleContext != null) {
            registerService(ApplicationConfiguration.class, this.configuration);
            registerService(WorkspaceService.class, this.workspaceService);
            registerService(AsyncExecutionService.class, this.asyncExecutionService);
            TrystLog.info(ApplicationLifecycleCoordinator.class,
                    "Core OSGi services registered in BundleContext: ApplicationConfiguration, WorkspaceService, AsyncExecutionService");
        } else {
            TrystLog.warn(ApplicationLifecycleCoordinator.class,
                    "BundleContext unavailable; services initialized in standalone/test mode.");
        }
    }

    private <S> void registerService(Class<S> serviceClass, S serviceInstance) {
        try {
            ServiceRegistration<S> reg = bundleContext.registerService(serviceClass, serviceInstance, null);
            serviceRegistrations.add(reg);
        } catch (Exception e) {
            throw new TrystApplicationException("Failed to register OSGi service " + serviceClass.getName(), e);
        }
    }

    private void onApplicationReady() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "TRYST Application is ready.");
    }

    private void onWorkbenchRunning() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Workbench UI running.");
    }

    private void onApplicationShutdown() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Application shutdown initiated.");
    }

    private void onServiceDisposal() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Disposing core application services...");

        // Unregister OSGi services in reverse order
        for (int i = serviceRegistrations.size() - 1; i >= 0; i--) {
            try {
                serviceRegistrations.get(i).unregister();
            } catch (Exception e) {
                TrystLog.warn(ApplicationLifecycleCoordinator.class, "Error unregistering service: " + e.getMessage());
            }
        }
        serviceRegistrations.clear();

        // Controlled disposal of thread pool / async execution service
        if (asyncExecutionService != null) {
            try {
                asyncExecutionService.shutdown();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error shutting down AsyncExecutionService", e);
            }
            asyncExecutionService = null;
        }

        this.workspaceService = null;
        this.configuration = null;
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Service disposal complete.");
    }

    private void onApplicationTerminated() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "TRYST Application terminated cleanly.");
    }

    private void notifyListeners(ApplicationLifecycleEvent event) {
        for (ApplicationLifecycleListener listener : listeners) {
            try {
                listener.onLifecycleEvent(event);
            } catch (Throwable t) {
                TrystLog.error(ApplicationLifecycleCoordinator.class,
                        "Lifecycle listener threw exception: " + t.getMessage(), t);
            }
        }
    }

    private void validateTransition(ApplicationLifecycleStage from, ApplicationLifecycleStage to) {
        int fromOrdinal = from.ordinal();
        int toOrdinal = to.ordinal();

        if (toOrdinal < fromOrdinal) {
            throw new TrystApplicationException(
                    "Illegal reverse lifecycle transition from " + from + " to " + to);
        }
        if (toOrdinal > fromOrdinal + 1) {
            throw new TrystApplicationException(
                    "Illegal skipped lifecycle transition from " + from + " to " + to);
        }
    }

    public ApplicationConfiguration getConfiguration() {
        return configuration;
    }

    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    public AsyncExecutionService getAsyncExecutionService() {
        return asyncExecutionService;
    }

    public List<ServiceRegistration<?>> getServiceRegistrations() {
        return Collections.unmodifiableList(serviceRegistrations);
    }
}
