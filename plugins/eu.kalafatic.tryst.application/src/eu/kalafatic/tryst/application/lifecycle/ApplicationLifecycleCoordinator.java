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
import eu.kalafatic.tryst.domain.sandbox.SandboxProvider;
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
import eu.kalafatic.tryst.core.server.TrystHttpService;
import eu.kalafatic.tryst.domain.contextual.ContextualActionRegistry;
import eu.kalafatic.tryst.core.server.TrystServerLifecycleService;
import eu.kalafatic.tryst.infrastructure.server.TrystTcpServer;
import eu.kalafatic.tryst.core.server.TrystTcpService;
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
public class ApplicationLifecycleCoordinator implements TrystServerLifecycleService,
        eu.kalafatic.tryst.infrastructure.server.AiServiceController {

    private final BundleContext bundleContext;
    private final List<ApplicationLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ServiceRegistration<?>> serviceRegistrations = new ArrayList<>();

    private ApplicationLifecycleStage currentStage = ApplicationLifecycleStage.APPLICATION_START;

    // Services
    private ConfigurationService configurationService;
    private ApplicationConfiguration configuration;
    private EventBus eventBus;
    private eu.kalafatic.tryst.core.lifecycle.ServerReadinessService serverReadinessService;
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
    private eu.kalafatic.tryst.domain.repository.GitCommitRepository gitCommitRepository;
    private eu.kalafatic.tryst.domain.repository.TaskInstructionRepository taskInstructionRepository;
    private eu.kalafatic.tryst.domain.intake.TaskIntakeService taskIntakeService;
    private eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository taskRecoveryCheckpointRepository;
    private eu.kalafatic.tryst.domain.service.DataRetentionService dataRetentionService;
    private eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService workspaceRecoveryService;

    private WorkspaceService workspaceService;
    private AsyncExecutionService asyncExecutionService;
    private PermissionManager permissionManager;
    private ToolRegistry toolRegistry;
    private AiContextManager aiContextManager;
    private volatile AiInferenceService aiInferenceService;
    private ServiceRegistration<AiInferenceService> aiInferenceServiceRegistration;
    private volatile boolean aiServiceRunning = false;
    private GitService gitService;
    private volatile ContextualActionRegistry contextualActionRegistry;
    private BuildAndTestService buildAndTestService;
    private CodeReviewService codeReviewService;
    private SandboxProvider sandboxProvider;
    private eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry connectedApplicationRegistry;
    private eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoIntegrationService;
    private eu.kalafatic.tryst.evo.integration.mcp.McpServer mcpServer;
    private SessionTaskOrchestrator sessionTaskOrchestrator;
    private AutonomousDevelopmentService autonomousDevelopmentService;
    private eu.kalafatic.tryst.domain.ai.AiModelRegistryService aiModelRegistryService;
    private eu.kalafatic.tryst.domain.ai.AiProviderRegistry aiProviderRegistry;
    private eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService
            executionConfigurationService;
    private eu.kalafatic.tryst.domain.service.TaskLifecycleManager taskLifecycleManager;
    private eu.kalafatic.tryst.domain.service.TaskRecoveryService taskRecoveryService;
    private eu.kalafatic.tryst.domain.service.TaskQueue taskQueue;
    private eu.kalafatic.tryst.domain.service.TaskOrchestrator taskOrchestrator;
    private TrystTcpService tcpService;
    private TrystHttpService httpService;

    // GitHub Integration Services & Repositories
    private eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager repositoryPoolManager;
    private eu.kalafatic.tryst.domain.github.GitHubCredentialRepository gitHubCredentialRepository;
    private eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService gitHubCredentialEncryptionService;
    private eu.kalafatic.tryst.domain.github.GitHubService gitHubService;

    // Phase 4 Services & Repositories
    private eu.kalafatic.tryst.domain.repository.UserRepository userRepository;
    private eu.kalafatic.tryst.domain.repository.UserSessionRepository userSessionRepository;
    private eu.kalafatic.tryst.domain.repository.AuditEventRepository auditEventRepository;
    private eu.kalafatic.tryst.domain.repository.NotificationRepository notificationRepository;
    private eu.kalafatic.tryst.domain.repository.NotificationPreferenceRepository notificationPreferenceRepository;
    private eu.kalafatic.tryst.domain.repository.TelemetryRepository telemetryRepository;
    private eu.kalafatic.tryst.domain.security.AuthenticationService authenticationService;
    private eu.kalafatic.tryst.domain.service.ServerMonitoringService serverMonitoringService;
    private eu.kalafatic.tryst.domain.service.TelemetryService telemetryService;
    private eu.kalafatic.tryst.domain.service.AiMonitoringService aiMonitoringService;
    private eu.kalafatic.tryst.domain.service.ProcessExecutionManager processExecutionManager;
    private eu.kalafatic.tryst.domain.service.NotificationService notificationService;
    private eu.kalafatic.tryst.domain.service.LogDiagnosticService logDiagnosticService;

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

    /**
     * Publishes a sub-milestone progress notification for the current lifecycle stage without
     * changing the stage itself. Consumers (e.g. the splash handler) use these notifications to
     * display meaningful startup messages connected to real initialization activities.
     */
    public synchronized void notifyStartupProgress(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        notifyListeners(ApplicationLifecycleEvent.of(currentStage, message));
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
        this.serverReadinessService = new eu.kalafatic.tryst.core.lifecycle.ServerReadinessService();
        this.serverReadinessService.registerComponent("Database", true);
        this.serverReadinessService.registerComponent("Authentication", true);
        this.serverReadinessService.registerComponent("HttpServer", true);
        this.serverReadinessService.registerComponent("TcpServer", false);
        this.serverReadinessService.registerComponent("AiProvider", false);
        TrystLog.info(ApplicationLifecycleCoordinator.class, "Platform initialization complete. EventBus and ServerReadinessService ready.");
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

        // Comprehensive database consistency and referential integrity check
        this.databaseManager.verifyDatabaseConsistency();

        if (serverReadinessService != null) {
            serverReadinessService.markHealthy("Database", java.util.Map.of("path", dbPath.toString()));
        }

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
        this.gitCommitRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitCommitRepository(databaseManager);
        this.taskInstructionRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskInstructionRepository(databaseManager);

        // Phase 4 Repositories
        this.userRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository(databaseManager);
        this.userSessionRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository(databaseManager);
        this.auditEventRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAuditEventRepository(databaseManager);
        this.notificationRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationRepository(databaseManager);
        this.notificationPreferenceRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteNotificationPreferenceRepository(databaseManager);
        this.telemetryRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTelemetryRepository(databaseManager);
        this.taskRecoveryCheckpointRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRecoveryCheckpointRepository(databaseManager);

        // Data Retention and Archival Service
        eu.kalafatic.tryst.infrastructure.persistence.SqliteRetentionStore retentionStore =
                new eu.kalafatic.tryst.infrastructure.persistence.SqliteRetentionStore(databaseManager);
        this.dataRetentionService = new eu.kalafatic.tryst.domain.service.DataRetentionService(retentionStore, databaseManager);

        // 4. Startup Task Reconciliation & Workspace Recovery
        this.taskLifecycleManager = new eu.kalafatic.tryst.domain.service.TaskLifecycleManager(this.taskRepository, this.eventBus);
        this.taskRecoveryService = new eu.kalafatic.tryst.domain.service.TaskRecoveryService(
                this.taskRepository, this.taskLifecycleManager, this.taskRecoveryCheckpointRepository);
        this.workspaceRecoveryService = new eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService(this.workspaceRepository);
        this.workspaceRecoveryService.recoverWorkspaces();
        this.taskRecoveryService.reconcileInterruptedTasks();

        this.taskQueue = new eu.kalafatic.tryst.domain.service.TaskQueue(this.taskRepository, this.taskLifecycleManager);
        notifyStartupProgress("Loading application modules...");

        // 5. Initialize Core domain & async services
        this.asyncExecutionService = new ManagedAsyncExecutionService();

        // 6. Initialize Security, Tools & AI Infrastructure
        this.permissionManager = new PermissionManager(this.auditEventRepository);
        this.workspaceService = new WorkspaceService(configuration.defaultWorkspaceDirectory(),
                workspaceRepository, projectRepository, taskRepository, this.permissionManager, this.eventBus);
        this.authenticationService = new eu.kalafatic.tryst.domain.security.AuthenticationService(
                this.userRepository, this.userSessionRepository, this.permissionManager);
        // Provision initial administrator if secret is provided via environment or system property;
        // otherwise initialize one-time setup token for interactive/web setup
        String envAdminPass = System.getenv("TRYST_ADMIN_PASSWORD");
        String propAdminPass = System.getProperty("tryst.admin.password");
        String adminPass = (propAdminPass != null && !propAdminPass.isBlank()) ? propAdminPass : envAdminPass;

        if (adminPass != null && !adminPass.isBlank()) {
            this.authenticationService.bootstrapAdmin("admin", adminPass);
        } else if (!this.authenticationService.hasAdministrator()) {
            String token = this.authenticationService.generateOneTimeSetupToken();
            TrystLog.warn(ApplicationLifecycleCoordinator.class,
                    "No administrator account detected and no bootstrap password supplied. Initial setup token: " + token);
        }
        if (serverReadinessService != null) {
            serverReadinessService.markHealthy("Authentication", java.util.Map.of(
                    "hasAdmin", Boolean.toString(this.authenticationService.hasAdministrator())
            ));
        }
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
        this.aiModelRegistryService = new eu.kalafatic.tryst.domain.ai.AiModelRegistryService(configuration.defaultModel());
        // Unified multi-provider dimension (Phase 9): one persisted provider lifecycle
        // registry wrapping the shared model catalog. Providers registered here are the
        // only source of provider descriptors, enablement and credentials.
        this.aiProviderRegistry = new eu.kalafatic.tryst.domain.ai.DefaultAiProviderRegistry(
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAiProviderRepository(
                        this.databaseManager,
                        new eu.kalafatic.tryst.infrastructure.persistence.SecretCodec(
                                configuration.configurationDirectory())),
                this.aiModelRegistryService);
        this.aiProviderRegistry.registerProvider("ollama-default", "Default Ollama Endpoint",
                "OLLAMA", configuration.ollamaEndpoint(), true,
                new eu.kalafatic.tryst.infrastructure.ai.OllamaProvider(
                        "ollama-default", configuration.ollamaEndpoint(),
                        Duration.ofSeconds(configuration.requestTimeoutSeconds())));
        try {
            this.aiProviderRegistry.discoverModels("ollama-default").join();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("AiProvider", java.util.Map.of("provider", "ollama-default"));
            }
        } catch (Exception e) {
            TrystLog.warn(ApplicationLifecycleCoordinator.class, "Initial AI model discovery deferred: " + e.getMessage());
            if (serverReadinessService != null) {
                serverReadinessService.markDegraded("AiProvider", e.getMessage(), java.util.Map.of("provider", "ollama-default"));
            }
        }
        // Phase 6: ENR is the default family inference engine. The ENR provider is
        // registered alongside the Ollama provider (alternative engines remain usable);
        // model discovery is best-effort so a missing ENR runtime never disables TRYST.
        try {
            eu.kalafatic.tryst.infrastructure.ai.EnrProvider enrProvider =
                    new eu.kalafatic.tryst.infrastructure.ai.EnrProvider();
            this.aiProviderRegistry.registerProvider(
                    eu.kalafatic.tryst.infrastructure.ai.EnrProvider.PROVIDER_ID,
                    "ENR Neural Runtime", "ENR", enrProvider.getEndpoint(), true, enrProvider);
            this.aiProviderRegistry.discoverModels(
                    eu.kalafatic.tryst.infrastructure.ai.EnrProvider.PROVIDER_ID).join();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("AiProvider", java.util.Map.of("provider", "enr-default"));
            }
        } catch (Exception e) {
            TrystLog.warn(ApplicationLifecycleCoordinator.class, "ENR model discovery deferred (ENR runtime not reachable): " + e.getMessage());
            if (serverReadinessService != null) {
                serverReadinessService.markDegraded("AiProvider", e.getMessage(), java.util.Map.of("provider", "enr-default"));
            }
        }
        // Unified execution configuration (Phase 9): GLOBAL -> PROJECT -> TASK resolution
        // with immutable per-task snapshots.
        this.executionConfigurationService =
                new eu.kalafatic.tryst.domain.ai.DefaultExecutionConfigurationService(
                        new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteExecutionConfigurationRepository(
                                this.databaseManager));
        this.gitService = new GitService();
        Path mirrorsDir = configuration.defaultWorkspaceDirectory().resolve("mirrors");
        this.repositoryPoolManager = new eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager(mirrorsDir, this.gitService);
        this.buildAndTestService = new BuildAndTestService();
        if (this.processExecutionManager != null) {
            this.buildAndTestService.setProcessExecutionManager(this.processExecutionManager);
        }
        this.codeReviewService = new CodeReviewService();

        // Sandbox Provider (OpenCode CLI adapter)
        if (bundleContext != null) {
            this.sandboxProvider = lookupSandboxProvider(bundleContext);
        }

        // GitHub Integration
        this.gitHubCredentialRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitHubCredentialRepository(databaseManager);
        this.gitHubCredentialEncryptionService = new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubCredentialEncryptionService();
        eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService ghService =
                new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService(
                        this.gitHubCredentialRepository,
                        this.gitHubCredentialEncryptionService,
                        this.gitService
                );
        if (this.repositoryPoolManager != null && this.databaseManager != null) {
            this.repositoryPoolManager.setDatabaseManager(this.databaseManager);
        }
        ghService.setRepositoryPoolManager(this.repositoryPoolManager);
        this.gitHubService = ghService;

        this.sessionTaskOrchestrator = new SessionTaskOrchestrator(
                sessionRepository,
                taskRepository,
                conversationMessageRepository,
                executionResultRepository,
                toolRegistry,
                permissionManager,
                eventBus,
                workspaceService,
                aiContextManager,
                sandboxProvider
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

        int taskConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.taskOrchestrator = new eu.kalafatic.tryst.domain.service.TaskOrchestrator(
                this.taskQueue,
                this.taskRepository,
                this.taskLifecycleManager,
                this.workspaceService,
                this.projectRepository,
                this.toolRegistry,
                this.permissionManager,
                this.aiInferenceService,
                this.aiContextManager,
                this.conversationMessageRepository,
                this.executionResultRepository,
                this.codeReviewRepository,
                this.codeReviewService,
                taskConcurrency
        );
        this.taskOrchestrator.setGitHubService(this.gitHubService);
        this.taskOrchestrator.setTaskRecoveryService(this.taskRecoveryService);
        this.taskOrchestrator.setBuildAndQualityService(this.buildAndTestService);
        this.taskOrchestrator.setTaskInstructionRepository(this.taskInstructionRepository);
        this.taskOrchestrator.setModelRegistry(this.aiModelRegistryService);
        this.taskOrchestrator.setEventBus(this.eventBus);

        // Studio Service
        if (this.databaseManager != null) {
            eu.kalafatic.tryst.domain.studio.StudioService studioService = new eu.kalafatic.tryst.domain.studio.DefaultStudioService(
                    this.taskRepository,
                    this.workspaceService,
                    this.projectRepository,
                    this.taskInstructionRepository,
                    this.codeReviewRepository,
                    this.taskOrchestrator,
                    this.notificationService,
                    this.eventBus,
                    () -> this.databaseManager.getConnection()
            );
            this.taskOrchestrator.setStudioService(studioService);
        }

        this.taskOrchestrator.start();

        // Task Intake Service
        this.taskIntakeService = new eu.kalafatic.tryst.domain.intake.DefaultTaskIntakeService(
                this.taskOrchestrator,
                this.taskRepository,
                this.taskInstructionRepository,
                this.projectRepository,
                this.workspaceService,
                this.gitHubService,
                this.gitHubCredentialRepository,
                this.permissionManager,
                this.sessionRepository,
                this.conversationMessageRepository
        );

        // TEIP 1.1 EVO Integration Repositories & Services
        eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository evoConfigRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoConfigurationRepository(databaseManager);
        eu.kalafatic.tryst.domain.repository.EvoExecutionRepository evoExecRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoExecutionRepository(databaseManager);
        this.evoIntegrationService =
                new eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService(evoConfigRepo, evoExecRepo, null);
        this.evoIntegrationService.reconcileUnfinishedExecutions();

        // Connected-application registry (Phase 3, A-14): product-local application
        // descriptors, coding-harness adapters, connection testing and selection.
        eu.kalafatic.tryst.domain.harness.ConnectedApplicationRepository connectedAppRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteConnectedApplicationRepository(databaseManager);
        eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry connectedApplicationRegistry =
                new eu.kalafatic.tryst.domain.harness.DefaultConnectedApplicationRegistry(connectedAppRepo);
        eu.kalafatic.tryst.evo.integration.service.EvoCodingHarnessAdapter evoHarnessAdapter =
                new eu.kalafatic.tryst.evo.integration.service.EvoCodingHarnessAdapter(this.evoIntegrationService);
        connectedApplicationRegistry.registerAdapter(evoHarnessAdapter);
        connectedApplicationRegistry.registerAdapter(new eu.kalafatic.tryst.infrastructure.cli.OpenCodeCodingHarnessAdapter());
        // Phase 4: DStudio Harness Platform connector (TEIP 1.1) — family harness provider.
        // Connection configuration is persisted via the dedicated repository (migration V18);
        // the TEIP auth token is runtime-only and never persisted.
        eu.kalafatic.tryst.domain.repository.DStudioConfigurationRepository dstudioConfigRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteDStudioConfigurationRepository(databaseManager);
        connectedApplicationRegistry.registerAdapter(
                new eu.kalafatic.tryst.evo.integration.service.DStudioCodingHarnessAdapter(
                        dstudioConfigRepo, executionConfigurationService));
        // Phase 6: DCode is the default family coding harness. Connection configuration is
        // persisted via the dedicated repository (migration V20); the DCode bearer token is
        // runtime-only and never persisted.
        eu.kalafatic.tryst.domain.repository.DCodeConfigurationRepository dcodeConfigRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteDCodeConfigurationRepository(databaseManager);
        connectedApplicationRegistry.registerAdapter(
                new eu.kalafatic.tryst.infrastructure.dcode.DCodeCodingHarnessAdapter(dcodeConfigRepo));
        // Preserve historical behavior: EVO delegation was active whenever the EVO
        // configuration was enabled. Since Phase 6 DCode is the family default harness —
        // when nothing has been explicitly selected, DCode is selected; an explicit EVO
        // selection (or any other operator choice) is always respected.
        if (connectedApplicationRegistry.getSelectedApplicationId().isEmpty()) {
            connectedApplicationRegistry.selectApplication(
                    eu.kalafatic.tryst.infrastructure.dcode.DCodeCodingHarnessAdapter.APPLICATION_ID);
        }

        // ExternalAiDelegator over the registry: TaskWorker's existing seam now routes the
        // implementation step through the selected connected coding harness. Task-level
        // execution configuration (harness/provider/model/agent overrides) is honored
        // through the unified execution-configuration service.
        this.taskOrchestrator.setExternalAiDelegator(
                new eu.kalafatic.tryst.domain.harness.HarnessAiDelegator(
                        connectedApplicationRegistry, executionConfigurationService));
        this.connectedApplicationRegistry = connectedApplicationRegistry;

        // Scoped MCP Server
        this.mcpServer = new eu.kalafatic.tryst.evo.integration.mcp.McpServer(this.permissionManager);
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.GetProjectMetadataTool(this.projectRepository));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ListWorkspaceFilesTool(this.workspaceRepository, this.permissionManager));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ReadWorkspaceFileTool(this.workspaceRepository, this.permissionManager));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.SearchWorkspaceCodeTool(this.workspaceRepository, this.permissionManager));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.GetTaskContextTool(this.taskRepository, this.taskInstructionRepository));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ProposePatchTool(this.workspaceRepository, this.permissionManager));
        this.mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.RunBuildAndTestTool(this.workspaceRepository, this.buildAndTestService));

        // Phase 4 Observability & Operational Services
        this.serverMonitoringService = new eu.kalafatic.tryst.domain.service.ServerMonitoringService(configuration.defaultWorkspaceDirectory());
        this.telemetryService = new eu.kalafatic.tryst.domain.service.TelemetryService(
                this.telemetryRepository, this.serverMonitoringService, this.userSessionRepository, this.taskRepository);
        this.telemetryService.start(30);

        if (this.aiInferenceService instanceof eu.kalafatic.tryst.infrastructure.ai.OllamaClient client) {
            this.aiMonitoringService = new eu.kalafatic.tryst.domain.service.AiMonitoringService(client);
        } else {
            this.aiMonitoringService = new eu.kalafatic.tryst.domain.service.AiMonitoringService(null);
        }
        // Real AI latency telemetry: snapshots record measured inference latency
        // (NaN when no inference has been recorded yet — never a fabricated zero).
        this.telemetryService.setAiMonitoringService(this.aiMonitoringService);
        this.processExecutionManager = new eu.kalafatic.tryst.domain.service.ProcessExecutionManager();
        this.notificationService = new eu.kalafatic.tryst.domain.service.NotificationService(
                this.notificationRepository,
                this.notificationPreferenceRepository,
                this.eventBus,
                this.userRepository
        );
               // this.notificationRepository, this.notificationPreferenceRepository, this.eventBus, this.userRepository);
        this.logDiagnosticService = new eu.kalafatic.tryst.domain.service.LogDiagnosticService();
        eu.kalafatic.tryst.domain.service.ManagedServiceController serviceController =
                new eu.kalafatic.tryst.domain.service.ManagedServiceController(this.telemetryService, this.dataRetentionService, this.taskOrchestrator);
        if (this.taskOrchestrator != null) {
            this.taskOrchestrator.setNotificationService(this.notificationService);
        }
        if (this.buildAndTestService != null) {
            this.buildAndTestService.setProcessExecutionManager(this.processExecutionManager);
        }

        // 7. Resolve port allocations coherently using PortAllocationManager
        notifyStartupProgress("Starting embedded services...");
        eu.kalafatic.tryst.core.port.PortRegistry portRegistry = this.configurationService.getPortRegistry();
        eu.kalafatic.tryst.core.port.PortAllocationManager portMgr = this.configurationService.getPortAllocationManager();
        eu.kalafatic.tryst.core.port.PortAllocationGroup resolvedGroup = null;

        if (portMgr != null) {
            try {
                boolean isHeadless = Boolean.getBoolean("tryst.headless")
                        || "true".equalsIgnoreCase(System.getProperty("tryst.headless"));
                eu.kalafatic.tryst.core.port.PortConflictPrompt prompt = isHeadless
                        ? new eu.kalafatic.tryst.application.port.HeadlessPortConflictPrompt()
                        : createUiPortConflictPrompt();
                resolvedGroup = portMgr.resolveAllocations(configuration.serverHost(), prompt);
                if (portRegistry != null && resolvedGroup != null) {
                    portRegistry.setAllocationGroup(resolvedGroup);
                }
            } catch (Exception pe) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Port resolution failed: " + pe.getMessage(), pe);
            }
        }

        int effectiveHttpPort = (resolvedGroup != null)
                ? resolvedGroup.getPort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE)
                : configuration.httpPort();
        int effectiveTcpPort = (resolvedGroup != null)
                ? resolvedGroup.getPort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.TCP_SERVICE)
                : configuration.tcpPort();

        // Initialize and start TCP and HTTP communication services
        try {
            this.tcpService = new TrystTcpServer("127.0.0.1", effectiveTcpPort, workspaceService, portRegistry);
            this.tcpService.start();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("TcpServer", java.util.Map.of("port", Integer.toString(this.tcpService.getPort())));
            }
        } catch (Exception e) {
            TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to start TCP service: " + e.getMessage(), e);
            if (serverReadinessService != null) {
                serverReadinessService.markDegraded("TcpServer", e.getMessage(), java.util.Map.of());
            }
        }

        try {
            String httpBindAddress = configuration.resolveEffectiveBindAddress();
            TrystHttpServer server = new TrystHttpServer(
                    httpBindAddress, effectiveHttpPort,
                    workspaceService, projectRepository, taskRepository,
                    codeReviewRepository, gitService, buildAndTestService, permissionManager
            );
            server.setPhase4Dependencies(
                    authenticationService, userRepository, userSessionRepository,
                    auditEventRepository, notificationRepository, telemetryRepository,
                    serverMonitoringService, telemetryService, aiMonitoringService,
                    processExecutionManager, notificationService, logDiagnosticService,
                    configurationService, databaseManager, backupService, agentRepository
            );
            server.setDataRetentionService(this.dataRetentionService);
            server.setDynamicPortConfiguration(
                    configuration.dynamicPortFallbackEnabled(),
                    configuration.portRangeStart(),
                    configuration.portRangeEnd()
            );
            server.setAiModelRegistryService(this.aiModelRegistryService);
            server.setAiManagementServices(this.aiProviderRegistry,
                    this.connectedApplicationRegistry, this.executionConfigurationService, this);
            server.setTaskOrchestrator(this.taskOrchestrator);
            server.setSchemaMigrationService(this.migrationService);
            server.setGitHubService(this.gitHubService, this.gitHubCredentialRepository, this.gitHubCredentialEncryptionService);
            server.setGitCommitRepository(this.gitCommitRepository);
            server.setManagedServiceController(serviceController);
            server.setTaskIntakeService(this.taskIntakeService);
            server.setRepositoryPoolManager(this.repositoryPoolManager);
            server.setPortRegistry(portRegistry);
            if (this.taskOrchestrator != null && this.taskOrchestrator.getStudioService() != null) {
                server.setStudioService(this.taskOrchestrator.getStudioService());
            }
            // Asynchronous Coder 2 wiring: coding sessions (continuation), execution
            // results (real console/build/test data), conversation history, task
            // instructions and the legacy inference service (real AI explanations).
            server.setCodingSessionRepository(this.sessionRepository);
            server.setExecutionResultRepository(this.executionResultRepository);
            server.setConversationMessageRepository(this.conversationMessageRepository);
            server.setTaskInstructionRepository(this.taskInstructionRepository);
            server.setAiInferenceService(this.aiInferenceService);

            // Wire workflow and scheduling services
            if (this.databaseManager != null) {
                var wfDefRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowDefinitionRepository(this.databaseManager);
                var wfExecRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowExecutionRepository(this.databaseManager);
                var wfEngine = new eu.kalafatic.tryst.domain.service.WorkflowEngine(wfDefRepo, wfExecRepo, this.taskOrchestrator);
                var schedRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskScheduleRepository(this.databaseManager);
                var schedService = new eu.kalafatic.tryst.domain.service.TaskSchedulerService(schedRepo, this.taskOrchestrator);
                schedService.start();
                server.setWorkflowDependencies(wfDefRepo, wfExecRepo, wfEngine, schedRepo, schedService);
            }

            // Register TEIP EVO Integration, MCP and connected-application routes
            registerIntegrationHttpHandlers(server);

            // Unified contextual actions (Darwin Family Contextual Action Contract
            // v1.0): one registry, task/session/project/repository providers, web
            // surface. RCP navigator menus and the web interface resolve and execute
            // through this single authority.
            registerContextualActions(server);

            this.httpService = server;
            if (!configuration.webServerEnabled()) {
                TrystLog.info(ApplicationLifecycleCoordinator.class,
                        "Web server disabled (webServer.enabled=false); HTTP service not started. "
                                + "Backend services keep running.");
                if (serverReadinessService != null) {
                    serverReadinessService.updateHealth("HttpServer",
                            eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.ComponentState.STOPPED,
                            null, java.util.Map.of("reason", "webServer.enabled=false"));
                }
            } else if (!configuration.webServerAutoStart()) {
                TrystLog.info(ApplicationLifecycleCoordinator.class,
                        "Web server auto-start disabled (webServer.autoStart=false); "
                                + "start it from the Web menu or the CLI.");
                if (serverReadinessService != null) {
                    serverReadinessService.updateHealth("HttpServer",
                            eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.ComponentState.STOPPED,
                            null, java.util.Map.of("reason", "webServer.autoStart=false"));
                }
            } else {
                this.httpService.start();
                if (serverReadinessService != null) {
                    serverReadinessService.markHealthy("HttpServer", java.util.Map.of(
                            "host", configuration.serverHost(),
                            "port", Integer.toString(this.httpService.getPort())
                    ));
                }
            }
        } catch (Exception e) {
            TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to start HTTP service: " + e.getMessage(), e);
            if (serverReadinessService != null) {
                serverReadinessService.markFailed("HttpServer", e.getMessage(), java.util.Map.of());
            }
        }

        // 8. Register into standard OSGi Service Registry if BundleContext is available
        if (bundleContext != null) {
            registerService(ApplicationConfiguration.class, this.configuration);
            registerService(ConfigurationService.class, this.configurationService);
            registerService(eu.kalafatic.tryst.core.config.RuntimeEndpointRegistry.class, this.configurationService.getEndpointRegistry());
            if (portRegistry != null) {
                registerService(eu.kalafatic.tryst.core.port.PortRegistry.class, portRegistry);
            }
            registerService(EventBus.class, this.eventBus);
            if (this.serverReadinessService != null) {
                registerService(eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.class, this.serverReadinessService);
            }
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
            registerService(eu.kalafatic.tryst.domain.repository.GitCommitRepository.class, this.gitCommitRepository);
            registerService(eu.kalafatic.tryst.domain.repository.TaskInstructionRepository.class, this.taskInstructionRepository);
            registerService(eu.kalafatic.tryst.domain.intake.TaskIntakeService.class, this.taskIntakeService);
            registerService(eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository.class, evoConfigRepo);
            registerService(eu.kalafatic.tryst.domain.repository.EvoExecutionRepository.class, evoExecRepo);
            registerService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class, this.evoIntegrationService);
            registerService(eu.kalafatic.tryst.evo.integration.mcp.McpServer.class, this.mcpServer);
            registerService(WorkspaceService.class, this.workspaceService);
            registerService(AsyncExecutionService.class, this.asyncExecutionService);
            registerService(PermissionManager.class, this.permissionManager);
            registerService(ToolRegistry.class, this.toolRegistry);
            registerService(AiContextManager.class, this.aiContextManager);
            if (this.aiInferenceService != null) {
                this.aiInferenceServiceRegistration = bundleContext.registerService(AiInferenceService.class, this.aiInferenceService, null);
                this.serviceRegistrations.add(this.aiInferenceServiceRegistration);
                this.aiServiceRunning = true;
            }
            registerService(eu.kalafatic.tryst.domain.ai.AiModelRegistryService.class, this.aiModelRegistryService);
            registerService(eu.kalafatic.tryst.domain.ai.AiProviderRegistry.class, this.aiProviderRegistry);
            registerService(eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService.class,
                    this.executionConfigurationService);
            registerService(GitService.class, this.gitService);
            registerService(BuildAndTestService.class, this.buildAndTestService);
            registerService(eu.kalafatic.tryst.domain.build.BuildAndQualityService.class, this.buildAndTestService);
            registerService(CodeReviewService.class, this.codeReviewService);
            registerService(eu.kalafatic.tryst.domain.github.GitHubCredentialRepository.class, this.gitHubCredentialRepository);
            registerService(eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService.class, this.gitHubCredentialEncryptionService);
            registerService(eu.kalafatic.tryst.domain.github.GitHubService.class, this.gitHubService);
            registerService(SessionTaskOrchestrator.class, this.sessionTaskOrchestrator);
            registerService(AutonomousDevelopmentService.class, this.autonomousDevelopmentService);
            registerService(eu.kalafatic.tryst.domain.service.TaskLifecycleManager.class, this.taskLifecycleManager);
            registerService(eu.kalafatic.tryst.domain.service.TaskRecoveryService.class, this.taskRecoveryService);
            registerService(eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService.class, this.workspaceRecoveryService);
            registerService(eu.kalafatic.tryst.domain.service.DataRetentionService.class, this.dataRetentionService);
            registerService(eu.kalafatic.tryst.domain.service.TaskQueue.class, this.taskQueue);
            registerService(eu.kalafatic.tryst.domain.service.TaskOrchestrator.class, this.taskOrchestrator);
            registerService(eu.kalafatic.tryst.domain.repository.UserRepository.class, this.userRepository);
            registerService(eu.kalafatic.tryst.domain.repository.UserSessionRepository.class, this.userSessionRepository);
            registerService(eu.kalafatic.tryst.domain.repository.AuditEventRepository.class, this.auditEventRepository);
            registerService(eu.kalafatic.tryst.domain.repository.NotificationRepository.class, this.notificationRepository);
            registerService(eu.kalafatic.tryst.domain.repository.NotificationPreferenceRepository.class, this.notificationPreferenceRepository);
            registerService(eu.kalafatic.tryst.domain.repository.TelemetryRepository.class, this.telemetryRepository);
            registerService(eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository.class, this.taskRecoveryCheckpointRepository);
            registerService(eu.kalafatic.tryst.domain.security.AuthenticationService.class, this.authenticationService);
            registerService(eu.kalafatic.tryst.domain.service.ServerMonitoringService.class, this.serverMonitoringService);
            registerService(eu.kalafatic.tryst.domain.service.TelemetryService.class, this.telemetryService);
            registerService(eu.kalafatic.tryst.domain.service.AiMonitoringService.class, this.aiMonitoringService);
            registerService(eu.kalafatic.tryst.domain.service.ProcessExecutionManager.class, this.processExecutionManager);
            registerService(eu.kalafatic.tryst.domain.service.NotificationService.class, this.notificationService);
            registerService(eu.kalafatic.tryst.domain.service.LogDiagnosticService.class, this.logDiagnosticService);
            registerService(eu.kalafatic.tryst.domain.service.ManagedServiceController.class, serviceController);

            registerService(TrystServerLifecycleService.class, this);

            if (tcpService != null) {
                registerService(TrystTcpService.class, this.tcpService);
            }
            if (httpService != null && httpService.isRunning()) {
                ensureHttpServiceRegistered();
            }

            TrystLog.info(ApplicationLifecycleCoordinator.class,
                    "Core, Persistence, Security, Observability, AI, Orchestration, Review, and Communication OSGi services registered successfully.");
        } else {
            TrystLog.warn(ApplicationLifecycleCoordinator.class,
                    "BundleContext unavailable; services initialized in standalone/test mode.");
        }
    }

    private eu.kalafatic.tryst.core.port.PortConflictPrompt createUiPortConflictPrompt() {
        try {
            Class<?> clazz = Class.forName("eu.kalafatic.tryst.ui.port.SwtPortConflictPrompt");
            return (eu.kalafatic.tryst.core.port.PortConflictPrompt) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return new eu.kalafatic.tryst.application.port.HeadlessPortConflictPrompt();
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
        if (httpServiceRegistration != null) {
            try {
                httpServiceRegistration.unregister();
            } catch (Exception ignored) {
            }
            serviceRegistrations.remove(httpServiceRegistration);
            httpServiceRegistration = null;
        }

        // 2.5 Stop TaskOrchestrator cleanly
        if (taskOrchestrator != null) {
            try {
                taskOrchestrator.shutdown();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error shutting down TaskOrchestrator", e);
            }
            taskOrchestrator = null;
        }

        // 3. Controlled disposal of thread pool / async execution service
        if (telemetryService != null) {
            try {
                telemetryService.stop();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error stopping TelemetryService", e);
            }
            telemetryService = null;
        }

        if (processExecutionManager != null) {
            try {
                processExecutionManager.shutdown();
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error shutting down ProcessExecutionManager", e);
            }
            processExecutionManager = null;
        }

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
        this.gitCommitRepository = null;
        this.backupService = null;
        this.migrationService = null;
        this.workspaceService = null;
        this.configuration = null;
        this.configurationService = null;
        this.permissionManager = null;
        this.toolRegistry = null;
        this.aiContextManager = null;
        this.aiInferenceService = null;
        this.aiModelRegistryService = null;
        this.gitService = null;
        this.buildAndTestService = null;
        this.codeReviewService = null;
        this.sessionTaskOrchestrator = null;
        this.autonomousDevelopmentService = null;
        this.taskLifecycleManager = null;
        this.taskRecoveryService = null;
        this.taskQueue = null;
        this.serverReadinessService = null;
        this.gitHubService = null;
        this.gitHubCredentialRepository = null;
        this.gitHubCredentialEncryptionService = null;
        this.taskRecoveryCheckpointRepository = null;
        this.dataRetentionService = null;
        this.workspaceRecoveryService = null;

        TrystLog.info(ApplicationLifecycleCoordinator.class, "Service disposal complete.");
    }

    private void onApplicationTerminated() {
        TrystLog.info(ApplicationLifecycleCoordinator.class, "TRYST Application terminated cleanly.");
        TrystLog.shutdown();
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

    public eu.kalafatic.tryst.core.lifecycle.ServerReadinessService getServerReadinessService() {
        return serverReadinessService;
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

    public eu.kalafatic.tryst.domain.repository.GitCommitRepository getGitCommitRepository() {
        return gitCommitRepository;
    }

    public SqliteBackupService getBackupService() {
        return backupService;
    }

    /**
     * Returns the forward-only schema migration service holding the authoritative
     * applied database schema version (may be {@code null} before core initialization).
     */
    public SchemaMigrationService getSchemaMigrationService() {
        return migrationService;
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

    public synchronized boolean isAiServiceRunning() {
        return aiServiceRunning && aiInferenceService != null;
    }

    public synchronized void startAiService() {
        if (configuration == null) {
            onConfigurationInitialization();
        }
        if (this.aiInferenceService == null) {
            this.aiInferenceService = new OllamaClient(
                    configuration.ollamaEndpoint(),
                    Duration.ofSeconds(configuration.requestTimeoutSeconds())
            );
        }
        if (this.aiMonitoringService != null && this.aiInferenceService instanceof OllamaClient client) {
            this.aiMonitoringService.setActiveModel(configuration.defaultModel());
        }
        if (bundleContext != null && aiInferenceServiceRegistration == null && this.aiInferenceService != null) {
            aiInferenceServiceRegistration = bundleContext.registerService(AiInferenceService.class, this.aiInferenceService, null);
            serviceRegistrations.add(aiInferenceServiceRegistration);
        }
        this.aiServiceRunning = true;
        if (serverReadinessService != null) {
            serverReadinessService.markHealthy("AiProvider", java.util.Map.of("endpoint", configuration.ollamaEndpoint()));
        }
        TrystLog.info(ApplicationLifecycleCoordinator.class, "TRYST AI Inference Service started.");
    }

    public synchronized void stopAiService() {
        if (aiInferenceServiceRegistration != null) {
            try {
                aiInferenceServiceRegistration.unregister();
            } catch (Exception ignored) {
            }
            serviceRegistrations.remove(aiInferenceServiceRegistration);
            aiInferenceServiceRegistration = null;
        }
        this.aiServiceRunning = false;
        if (serverReadinessService != null) {
            serverReadinessService.updateHealth("AiProvider",
                    eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.ComponentState.STOPPED,
                    "AI service stopped by user", java.util.Map.of());
        }
        TrystLog.info(ApplicationLifecycleCoordinator.class, "TRYST AI Inference Service stopped.");
    }

    public eu.kalafatic.tryst.domain.ai.AiModelRegistryService getAiModelRegistryService() {
        return aiModelRegistryService;
    }

    public eu.kalafatic.tryst.domain.ai.AiProviderRegistry getAiProviderRegistry() {
        return aiProviderRegistry;
    }

    public eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry
            getConnectedApplicationRegistry() {
        return connectedApplicationRegistry;
    }

    public eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService
            getExecutionConfigurationService() {
        return executionConfigurationService;
    }

    public eu.kalafatic.tryst.domain.github.GitHubService getGitHubService() {
        return gitHubService;
    }

    public eu.kalafatic.tryst.domain.github.GitHubCredentialRepository getGitHubCredentialRepository() {
        return gitHubCredentialRepository;
    }

    public eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService getGitHubCredentialEncryptionService() {
        return gitHubCredentialEncryptionService;
    }

    public GitService getGitService() {
        return gitService;
    }

    public ContextualActionRegistry getContextualActionRegistry() {
        return contextualActionRegistry;
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

    public eu.kalafatic.tryst.domain.service.TaskLifecycleManager getTaskLifecycleManager() {
        return taskLifecycleManager;
    }

    public eu.kalafatic.tryst.domain.service.TaskRecoveryService getTaskRecoveryService() {
        return taskRecoveryService;
    }

    public eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository getTaskRecoveryCheckpointRepository() {
        return taskRecoveryCheckpointRepository;
    }

    public eu.kalafatic.tryst.domain.service.DataRetentionService getDataRetentionService() {
        return dataRetentionService;
    }

    public eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService getWorkspaceRecoveryService() {
        return workspaceRecoveryService;
    }

    public eu.kalafatic.tryst.domain.service.TaskQueue getTaskQueue() {
        return taskQueue;
    }

    public eu.kalafatic.tryst.domain.service.TaskOrchestrator getTaskOrchestrator() {
        return taskOrchestrator;
    }

    public TrystTcpService getTcpService() {
        return tcpService;
    }

    public TrystHttpService getHttpService() {
        return httpService;
    }

    private ServiceRegistration<TrystHttpService> httpServiceRegistration;

    @Override
    public synchronized boolean isHttpServerRunning() {
        return this.httpService != null && this.httpService.isRunning();
    }

    @Override
    public synchronized TrystHttpService startHttpServer() throws Exception {
        return startHttpService();
    }

    @Override
    public synchronized void stopHttpServer() throws Exception {
        stopHttpService();
    }

    @Override
    public synchronized TrystHttpService restartHttpServer() throws Exception {
        return restartHttpService();
    }

    /**
     * Starts or ensures running state of the embedded HTTP server.
     * Idempotent, thread-safe, and independent of UI lifecycle.
     */
    public synchronized TrystHttpService startHttpService() throws Exception {
        if (configuration != null && !configuration.webServerEnabled()) {
            throw new IllegalStateException("Web server disabled (webServer.enabled=false). "
                    + "Enable it in " + configuration.configurationDirectory()
                    + "/tryst.properties and restart.");
        }
        if (this.currentStage.ordinal() < ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION.ordinal()) {
            if (this.currentStage.ordinal() < ApplicationLifecycleStage.PLATFORM_INITIALIZATION.ordinal()) {
                transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            }
            if (this.currentStage.ordinal() < ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION.ordinal()) {
                transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            }
            transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        }

        if (this.httpService != null && this.httpService.isRunning()) {
            ensureHttpServiceRegistered();
            return this.httpService;
        }

        if (this.httpService == null) {
            initializeHttpServerInstance();
        }

        try {
            this.httpService.start();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("HttpServer", java.util.Map.of(
                        "host", this.httpService.getHost(),
                        "port", Integer.toString(this.httpService.getPort())
                ));
            }
            ensureHttpServiceRegistered();
            TrystLog.info(ApplicationLifecycleCoordinator.class, "HTTP Service started successfully on port " + this.httpService.getPort());
            return this.httpService;
        } catch (Exception e) {
            TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to start HTTP service: " + e.getMessage(), e);
            if (serverReadinessService != null) {
                serverReadinessService.markFailed("HttpServer", e.getMessage(), java.util.Map.of(
                        "exception", e.getClass().getName()
                ));
            }
            throw e;
        }
    }

    /**
     * Stops the embedded HTTP server safely.
     */
    public synchronized void stopHttpService() {
        if (this.httpService != null) {
            try {
                this.httpService.stop();
                if (serverReadinessService != null) {
                    serverReadinessService.updateHealth("HttpServer",
                            eu.kalafatic.tryst.core.lifecycle.ServerReadinessService.ComponentState.STOPPED,
                            null, java.util.Map.of());
                }
                TrystLog.info(ApplicationLifecycleCoordinator.class, "HTTP Service stopped successfully.");
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Error stopping HTTP service: " + e.getMessage(), e);
            }
            this.httpService = null;
        }
        if (httpServiceRegistration != null) {
            try {
                httpServiceRegistration.unregister();
            } catch (Exception ignored) {
            }
            serviceRegistrations.remove(httpServiceRegistration);
            httpServiceRegistration = null;
        }
    }

    /**
     * Restarts the embedded HTTP server safely.
     */
    public synchronized TrystHttpService restartHttpService() throws Exception {
        stopHttpService();
        return startHttpService();
    }

    /**
     * Human-readable web server lifecycle state for UI surfaces:
     * DISABLED / STOPPED / RUNNING / (readiness state).
     */
    public String webServerState() {
        if (configuration != null && !configuration.webServerEnabled()) {
            return "DISABLED";
        }
        if (httpService == null) {
            return "STOPPED";
        }
        if (httpService.isRunning()) {
            return "RUNNING";
        }
        if (serverReadinessService != null
                && serverReadinessService.getComponent("HttpServer") != null) {
            return String.valueOf(serverReadinessService.getComponent("HttpServer").state());
        }
        return "STOPPED";
    }

    /**
     * Whether the web server should be auto-started (config webServer.autoStart).
     */
    public boolean isWebServerAutoStart() {
        return configuration == null || configuration.webServerAutoStart();
    }

    private void ensureHttpServiceRegistered() {
        if (bundleContext != null && this.httpService != null && httpServiceRegistration == null) {
            try {
                httpServiceRegistration = bundleContext.registerService(TrystHttpService.class, this.httpService, null);
                serviceRegistrations.add(httpServiceRegistration);
            } catch (Exception e) {
                TrystLog.error(ApplicationLifecycleCoordinator.class, "Failed to register TrystHttpService in OSGi: " + e.getMessage(), e);
            }
        }
    }

    private void initializeHttpServerInstance() {
        if (configuration == null) {
            onConfigurationInitialization();
        }
        int effectiveHttpPort = (configurationService.getPortRegistry() != null && configurationService.getPortRegistry().getAllocationGroup() != null)
                ? configurationService.getPortRegistry().getAllocationGroup().getPort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE)
                : configuration.httpPort();

        String httpBindAddress = configuration.resolveEffectiveBindAddress();
        TrystHttpServer server = new TrystHttpServer(
                httpBindAddress, effectiveHttpPort,
                workspaceService, projectRepository, taskRepository,
                codeReviewRepository, gitService, buildAndTestService, permissionManager
        );
        server.setPhase4Dependencies(
                authenticationService, userRepository, userSessionRepository,
                auditEventRepository, notificationRepository, telemetryRepository,
                serverMonitoringService, telemetryService, aiMonitoringService,
                processExecutionManager, notificationService, logDiagnosticService,
                configurationService, databaseManager, backupService, agentRepository
        );
        server.setDataRetentionService(this.dataRetentionService);
        server.setDynamicPortConfiguration(
                configuration.dynamicPortFallbackEnabled(),
                configuration.portRangeStart(),
                configuration.portRangeEnd()
        );
        server.setAiModelRegistryService(this.aiModelRegistryService);
        server.setAiManagementServices(this.aiProviderRegistry,
                this.connectedApplicationRegistry, this.executionConfigurationService, this);
        server.setTaskOrchestrator(this.taskOrchestrator);
        server.setSchemaMigrationService(this.migrationService);
        server.setGitHubService(this.gitHubService, this.gitHubCredentialRepository, this.gitHubCredentialEncryptionService);
        server.setGitCommitRepository(this.gitCommitRepository);
        server.setTaskIntakeService(this.taskIntakeService);
        server.setRepositoryPoolManager(this.repositoryPoolManager);
        server.setPortRegistry(configurationService.getPortRegistry());

        if (this.databaseManager != null) {
            var wfDefRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowDefinitionRepository(this.databaseManager);
            var wfExecRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkflowExecutionRepository(this.databaseManager);
            var wfEngine = new eu.kalafatic.tryst.domain.service.WorkflowEngine(wfDefRepo, wfExecRepo, this.taskOrchestrator);
            var schedRepo = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskScheduleRepository(this.databaseManager);
            var schedService = new eu.kalafatic.tryst.domain.service.TaskSchedulerService(schedRepo, this.taskOrchestrator);
            server.setWorkflowDependencies(wfDefRepo, wfExecRepo, wfEngine, schedRepo, schedService);
        }

        // WP-D (Phase 3 finalization): the TEIP/MCP/connected-application routes must be
        // re-registered whenever a NEW server instance is created (initial start and every
        // restartHttpService() cycle). Services that are not yet initialized are skipped.
        registerIntegrationHttpHandlers(server);

        this.httpService = server;
    }

    /**
     * Registers the TEIP EVO integration, MCP and connected-application management
     * routes on the given server instance using the existing handler lifecycle.
     * The connected-applications route enforces WP-3.10 authorization
     * (Bearer token -> MANAGE_AGENTS permission).
     */
    private void registerIntegrationHttpHandlers(eu.kalafatic.tryst.infrastructure.server.TrystHttpServer server) {
        if (this.evoIntegrationService != null) {
            server.registerHandler("/api/integration/evo",
                    new eu.kalafatic.tryst.evo.integration.service.EvoHttpHandlers.EvoIntegrationHandler(
                            this.evoIntegrationService, this.permissionManager));
        }
        if (this.mcpServer != null && this.evoIntegrationService != null) {
            server.registerHandler("/api/mcp",
                    new eu.kalafatic.tryst.evo.integration.service.EvoHttpHandlers.McpHttpHandler(
                            this.mcpServer, this.evoIntegrationService));
        }
        if (connectedApplicationRegistry != null) {
            server.registerHandler("/api/integration/applications",
                    new eu.kalafatic.tryst.application.http.ConnectedApplicationsHttpHandler(
                            connectedApplicationRegistry, this.authenticationService, this.permissionManager));
        }
    }

    private void registerContextualActions(
            eu.kalafatic.tryst.infrastructure.server.TrystHttpServer server) {
        try {
            eu.kalafatic.tryst.domain.contextual.ContextualActionRegistry registry =
                    new eu.kalafatic.tryst.domain.contextual
                            .DefaultContextualActionRegistry();
            eu.kalafatic.tryst.domain.contextual.TrystContextualActions.install(registry,
                    this.taskOrchestrator, this.taskRepository, this.sessionRepository,
                    this.projectRepository);
            if (this.gitService != null) {
                eu.kalafatic.tryst.infrastructure.contextual.TrystRepositoryContextActions
                        .install(registry, this.gitService);
            }
            this.contextualActionRegistry = registry;
            if (server != null) {
                server.registerHandler(
                        eu.kalafatic.tryst.infrastructure.contextual.ContextualHttpHandler.PATH,
                        new eu.kalafatic.tryst.infrastructure.contextual
                                .ContextualHttpHandler(registry, exchange -> {
                                    String header = exchange.getRequestHeaders()
                                            .getFirst("Authorization");
                                    String token = header != null
                                            && header.startsWith("Bearer ")
                                                    ? header.substring("Bearer ".length())
                                                            .trim()
                                                    : "";
                                    return this.authenticationService == null
                                            || token.isBlank()
                                                    ? null
                                                    : this.authenticationService
                                                            .authenticateToken(token)
                                                            .orElse(null);
                                }));
            }
        } catch (Exception e) {
            System.err.println("[TRYST] Contextual action framework registration failed: "
                    + e.getMessage());
        }
    }

    public List<ServiceRegistration<?>> getServiceRegistrations() {
        return Collections.unmodifiableList(serviceRegistrations);
    }

    private SandboxProvider lookupSandboxProvider(BundleContext context) {
        try {
            if (context != null) {
                var ref = context.getServiceReference(SandboxProvider.class);
                if (ref != null) {
                    var provider = context.getService(ref);
                    if (provider != null) {
                        TrystLog.info(ApplicationLifecycleCoordinator.class, "SandboxProvider " + provider.getClass().getSimpleName() + " wired successfully");
                        return provider;
                    }
                }
            }
        } catch (Exception e) {
            TrystLog.warn(ApplicationLifecycleCoordinator.class, "Failed to wire SandboxProvider via OSGi: " + e.getMessage());
        }
        // SandboxProvider SPI wiring (Phase 3, WP-3.6): fall back to the built-in
        // process-isolation provider so the SPI is never an unused abstraction.
        eu.kalafatic.tryst.infrastructure.sandbox.DefaultProcessSandboxProvider fallback =
                new eu.kalafatic.tryst.infrastructure.sandbox.DefaultProcessSandboxProvider();
        TrystLog.info(ApplicationLifecycleCoordinator.class, "SandboxProvider wired to built-in " + fallback.getName());
        return fallback;
    }
}
