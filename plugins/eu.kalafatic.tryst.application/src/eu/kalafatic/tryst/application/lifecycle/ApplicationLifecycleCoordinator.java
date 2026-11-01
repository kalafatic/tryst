package eu.kalafatic.tryst.application.lifecycle;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.concurrent.ManagedAsyncExecutionService;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleEvent;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleListener;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.UnitOfWork;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteBackupService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAgentRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConversationMessageRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.nio.file.Path;
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
    private SqliteDatabaseManager databaseManager;
    private SchemaMigrationService migrationService;
    private SqliteBackupService backupService;

    // Repositories
    private ProjectRepository projectRepository;
    private TaskRepository taskRepository;
    private WorkspaceRepository workspaceRepository;
    private SessionRepository sessionRepository;
    private ConversationMessageRepository conversationMessageRepository;
    private ExecutionResultRepository executionResultRepository;
    private AgentRepository agentRepository;

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

        // 1. Resolve authoritative database location
        Path dbPath = configuration.configurationDirectory().resolve("tryst.db");

        // 2. Initialize Database Manager & apply migrations
        this.databaseManager = new SqliteDatabaseManager(dbPath);
        this.databaseManager.initialize();

        this.migrationService = new SchemaMigrationService(databaseManager);
        this.migrationService.migrate();

        this.backupService = new SqliteBackupService(databaseManager);

        // 3. Initialize Repositories
        this.projectRepository = new SqliteProjectRepository(databaseManager);
        this.taskRepository = new SqliteTaskRepository(databaseManager);
        this.workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);
        this.sessionRepository = new SqliteSessionRepository(databaseManager);
        this.conversationMessageRepository = new SqliteConversationMessageRepository(databaseManager);
        this.executionResultRepository = new SqliteExecutionResultRepository(databaseManager);
        this.agentRepository = new SqliteAgentRepository(databaseManager);

        // 4. Initialize Core domain & async services
        this.workspaceService = new WorkspaceService(configuration.defaultWorkspaceDirectory(),
                workspaceRepository, projectRepository, taskRepository);
        this.asyncExecutionService = new ManagedAsyncExecutionService();

        // 5. Register into standard OSGi Service Registry if BundleContext is available
        if (bundleContext != null) {
            registerService(ApplicationConfiguration.class, this.configuration);
            registerService(UnitOfWork.class, this.databaseManager);
            registerService(SqliteDatabaseManager.class, this.databaseManager);
            registerService(SqliteBackupService.class, this.backupService);
            registerService(ProjectRepository.class, this.projectRepository);
            registerService(TaskRepository.class, this.taskRepository);
            registerService(WorkspaceRepository.class, this.workspaceRepository);
            registerService(SessionRepository.class, this.sessionRepository);
            registerService(ConversationMessageRepository.class, this.conversationMessageRepository);
            registerService(ExecutionResultRepository.class, this.executionResultRepository);
            registerService(AgentRepository.class, this.agentRepository);
            registerService(WorkspaceService.class, this.workspaceService);
            registerService(AsyncExecutionService.class, this.asyncExecutionService);
            TrystLog.info(ApplicationLifecycleCoordinator.class,
                    "Core and Persistence OSGi services registered successfully.");
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

        // 1. Unregister OSGi services in reverse order
        for (int i = serviceRegistrations.size() - 1; i >= 0; i--) {
            try {
                serviceRegistrations.get(i).unregister();
            } catch (Exception e) {
                TrystLog.warn(ApplicationLifecycleCoordinator.class, "Error unregistering service: " + e.getMessage());
            }
        }
        serviceRegistrations.clear();

        // 2. Controlled disposal of thread pool / async execution service
        if (asyncExecutionService != null) {
            try {
                asyncExecutionService.shutdown();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error shutting down AsyncExecutionService", e);
            }
            asyncExecutionService = null;
        }

        // 3. Close SQLite Database Manager cleanly
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error closing SqliteDatabaseManager", e);
            }
            databaseManager = null;
        }

        this.projectRepository = null;
        this.taskRepository = null;
        this.workspaceRepository = null;
        this.sessionRepository = null;
        this.conversationMessageRepository = null;
        this.executionResultRepository = null;
        this.agentRepository = null;
        this.backupService = null;
        this.migrationService = null;
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

    public SqliteDatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ProjectRepository getProjectRepository() {
        return projectRepository;
    }

    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    public WorkspaceRepository getWorkspaceRepository() {
        return workspaceRepository;
    }

    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }

    public ConversationMessageRepository getConversationMessageRepository() {
        return conversationMessageRepository;
    }

    public ExecutionResultRepository getExecutionResultRepository() {
        return executionResultRepository;
    }

    public AgentRepository getAgentRepository() {
        return agentRepository;
    }

    public SqliteBackupService getBackupService() {
        return backupService;
    }

    public List<ServiceRegistration<?>> getServiceRegistrations() {
        return Collections.unmodifiableList(serviceRegistrations);
    }
}
