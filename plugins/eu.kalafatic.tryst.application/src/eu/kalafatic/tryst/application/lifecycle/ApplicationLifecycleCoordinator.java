package eu.kalafatic.tryst.application.lifecycle;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.concurrent.ManagedAsyncExecutionService;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleEvent;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleListener;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.UnitOfWork;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.AutonomousDevelopmentService;
import eu.kalafatic.tryst.domain.service.SessionTaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.domain.tool.FileTools;
import eu.kalafatic.tryst.domain.tool.GitTools;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;
import eu.kalafatic.tryst.infrastructure.ai.OllamaClient;
import eu.kalafatic.tryst.infrastructure.build.BuildAndTestService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteBackupService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAgentRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConversationMessageRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionResultRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpService;
import eu.kalafatic.tryst.infrastructure.server.TrystTcpServer;
import eu.kalafatic.tryst.infrastructure.server.TrystTcpService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic lifecycle coordinator driving startup, configuration,
 * persistence, core/domain/AI services, OSGi registration, reconciliation, and clean shutdown.
 */
public class ApplicationLifecycleCoordinator {

    private final BundleContext bundleContext;
    private final List<ApplicationLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ServiceRegistration<?>> serviceRegistrations = new ArrayList<>();

    private ApplicationLifecycleStage currentStage = ApplicationLifecycleStage.APPLICATION_START;

    // Services
    private ConfigurationService configurationService;
    private ApplicationConfiguration configuration;
    private EventBus eventBus;
    private SqliteDatabaseManager databaseManager;
    private SchemaMigrationService migrationService;
    private SqliteBackupService backupService;

    private ProjectRepository projectRepository;
    private TaskRepository taskRepository;
    private WorkspaceRepository workspaceRepository;
    private SessionRepository sessionRepository;
    private ConversationMessageRepository conversationMessageRepository;
    private ExecutionResultRepository executionResultRepository;
    private AgentRepository agentRepository;
    private CodeReviewRepository codeReviewRepository;

    private WorkspaceService workspaceService;
    private AsyncExecutionService asyncExecutionService;
    private PermissionManager permissionManager;
    private ToolRegistry toolRegistry;
    private AiContextManager aiContextManager;
    private AiInferenceService aiInferenceService;
    private GitService gitService;
    private BuildAndTestService buildAndTestService;
    private CodeReviewService codeReviewService;
    private SessionTaskOrchestrator sessionTaskOrchestrator;
    private AutonomousDevelopmentService autonomousDevelopmentService;
    private TrystTcpService tcpService;
    private TrystHttpService httpService;

    public ApplicationLifecycleCoordinator() {
        this(null);
    }

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
        this.eventBus = new EventBus();
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Platform initialization complete. EventBus ready.");
    }

    private void onConfigurationInitialization() {
        if (eventBus == null) {
            this.eventBus = new EventBus();
        }
        Path configDir = Path.of(System.getProperty("user.home", ".")).resolve(".tryst").normalize();
        this.configurationService = new ConfigurationService(configDir);
        this.configuration = configurationService.getConfiguration();
        TrystLog.info(ApplicationLifecycleCoordinator.class,
                "Configuration initialized for application: " + configuration.applicationName() +
                        " (workspace: " + configuration.defaultWorkspaceDirectory() + ")");
    }

    private void onCoreServiceInitialization() {
        if (configuration == null) {
            onConfigurationInitialization();
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
        this.codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);

        // 4. Startup Task Reconciliation: detect interrupted tasks and mark them RECOVERY_REQUIRED
        reconcileInterruptedTasks();

        // 5. Initialize Core domain & async services
        this.workspaceService = new WorkspaceService(configuration.defaultWorkspaceDirectory(),
                workspaceRepository, projectRepository, taskRepository);
        this.asyncExecutionService = new ManagedAsyncExecutionService();

        // 6. Initialize Security, Tools & AI Infrastructure
        this.permissionManager = new PermissionManager();
        this.toolRegistry = new ToolRegistry();
        this.toolRegistry.register(new FileTools.ReadFileTool());
        this.toolRegistry.register(new FileTools.WriteFileTool());
        this.toolRegistry.register(new FileTools.ListDirectoryTool());
        this.toolRegistry.register(new FileTools.CreateDirectoryTool());
        this.toolRegistry.register(new GitTools.GitDiffTool());

        this.aiContextManager = new AiContextManager(configuration.maxContextTokens());
        this.aiInferenceService = new OllamaClient(
                configuration.ollamaEndpoint(),
                Duration.ofSeconds(configuration.requestTimeoutSeconds())
        );
        this.gitService = new GitService();
        this.buildAndTestService = new BuildAndTestService();
        this.codeReviewService = new CodeReviewService();

        this.sessionTaskOrchestrator = new SessionTaskOrchestrator(
                sessionRepository,
                taskRepository,
                conversationMessageRepository,
                executionResultRepository,
                toolRegistry,
                permissionManager,
                eventBus,
                workspaceService,
                aiContextManager
        );

        this.autonomousDevelopmentService = new AutonomousDevelopmentService(
                sessionTaskOrchestrator,
                workspaceService,
                taskRepository,
                projectRepository,
                executionResultRepository,
                codeReviewRepository,
                toolRegistry,
                permissionManager,
                codeReviewService,
                eventBus
        );

        // 7. Initialize and start TCP and HTTP communication services
        try {
            this.tcpService = new TrystTcpServer(configuration.serverHost(), configuration.tcpPort(), workspaceService);
            this.tcpService.start();
        } catch (Exception e) {
            TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to start TCP service: " + e.getMessage(), e);
        }

        try {
            this.httpService = new TrystHttpServer(
                    configuration.serverHost(), configuration.httpPort(),
                    workspaceService, projectRepository, taskRepository,
                    codeReviewRepository, gitService, buildAndTestService, permissionManager
            );
            this.httpService.start();
        } catch (Exception e) {
            TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to start HTTP service: " + e.getMessage(), e);
        }

        // 8. Register into standard OSGi Service Registry if BundleContext is available
        if (bundleContext != null) {
            registerService(ApplicationConfiguration.class, this.configuration);
            registerService(ConfigurationService.class, this.configurationService);
            registerService(EventBus.class, this.eventBus);
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
            registerService(CodeReviewRepository.class, this.codeReviewRepository);
            registerService(WorkspaceService.class, this.workspaceService);
            registerService(AsyncExecutionService.class, this.asyncExecutionService);
            registerService(PermissionManager.class, this.permissionManager);
            registerService(ToolRegistry.class, this.toolRegistry);
            registerService(AiContextManager.class, this.aiContextManager);
            registerService(AiInferenceService.class, this.aiInferenceService);
            registerService(GitService.class, this.gitService);
            registerService(BuildAndTestService.class, this.buildAndTestService);
            registerService(CodeReviewService.class, this.codeReviewService);
            registerService(SessionTaskOrchestrator.class, this.sessionTaskOrchestrator);
            registerService(AutonomousDevelopmentService.class, this.autonomousDevelopmentService);
            if (tcpService != null) {
                registerService(TrystTcpService.class, this.tcpService);
            }
            if (httpService != null) {
                registerService(TrystHttpService.class, this.httpService);
            }

            TrystLog.info(ApplicationLifecycleCoordinator.class,
                    "Core, Persistence, AI, Orchestration, Review, and Communication OSGi services registered successfully.");
        } else {
            TrystLog.warn(ApplicationLifecycleCoordinator.class,
                    "BundleContext unavailable; services initialized in standalone/test mode.");
        }
    }

    private void reconcileInterruptedTasks() {
        if (taskRepository == null) return;
        List<AgentTask> allTasks = taskRepository.findAll();
        for (AgentTask task : allTasks) {
            if (task.getTaskStatus() == TaskStatus.RUNNING || task.getTaskStatus() == TaskStatus.CLAIMED || task.getTaskStatus() == TaskStatus.COMMITTING) {
                TrystLog.warn(ApplicationLifecycleCoordinator.class,
                        "Reconciling interrupted task " + task.getId() + " from status " + task.getTaskStatus() + " to RECOVERY_REQUIRED");
                task.markRecoveryRequired();
                taskRepository.save(task);
            }
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

        // 2. Stop communication services cleanly
        if (tcpService != null) {
            try {
                tcpService.stop();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error stopping TCP service", e);
            }
            tcpService = null;
        }

        if (httpService != null) {
            try {
                httpService.stop();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error stopping HTTP service", e);
            }
            httpService = null;
        }

        // 3. Controlled disposal of thread pool / async execution service
        if (asyncExecutionService != null) {
            try {
                asyncExecutionService.shutdown();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error shutting down AsyncExecutionService", e);
            }
            asyncExecutionService = null;
        }

        // 4. Close SQLite Database Manager cleanly
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error closing SqliteDatabaseManager", e);
            }
            databaseManager = null;
        }

        if (eventBus != null) {
            eventBus.clear();
            eventBus = null;
        }

        this.projectRepository = null;
        this.taskRepository = null;
        this.workspaceRepository = null;
        this.sessionRepository = null;
        this.conversationMessageRepository = null;
        this.executionResultRepository = null;
        this.agentRepository = null;
        this.codeReviewRepository = null;
        this.backupService = null;
        this.migrationService = null;
        this.workspaceService = null;
        this.configuration = null;
        this.configurationService = null;
        this.permissionManager = null;
        this.toolRegistry = null;
        this.aiContextManager = null;
        this.aiInferenceService = null;
        this.gitService = null;
        this.buildAndTestService = null;
        this.codeReviewService = null;
        this.sessionTaskOrchestrator = null;
        this.autonomousDevelopmentService = null;

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

    public ConfigurationService getConfigurationService() {
        return configurationService;
    }

    public EventBus getEventBus() {
        return eventBus;
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

    public CodeReviewRepository getCodeReviewRepository() {
        return codeReviewRepository;
    }

    public SqliteBackupService getBackupService() {
        return backupService;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public AiInferenceService getAiInferenceService() {
        return aiInferenceService;
    }

    public GitService getGitService() {
        return gitService;
    }

    public BuildAndTestService getBuildAndTestService() {
        return buildAndTestService;
    }

    public CodeReviewService getCodeReviewService() {
        return codeReviewService;
    }

    public SessionTaskOrchestrator getSessionTaskOrchestrator() {
        return sessionTaskOrchestrator;
    }

    public AutonomousDevelopmentService getAutonomousDevelopmentService() {
        return autonomousDevelopmentService;
    }

    public TrystTcpService getTcpService() {
        return tcpService;
    }

    public TrystHttpService getHttpService() {
        return httpService;
    }

    public List<ServiceRegistration<?>> getServiceRegistrations() {
        return Collections.unmodifiableList(serviceRegistrations);
    }
}
