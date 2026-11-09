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
import eu.kalafatic.tryst.core.server.TrystHttpService;
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
public class ApplicationLifecycleCoordinator implements TrystServerLifecycleService {

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
    private AiInferenceService aiInferenceService;
    private GitService gitService;
    private BuildAndTestService buildAndTestService;
    private CodeReviewService codeReviewService;
    private SessionTaskOrchestrator sessionTaskOrchestrator;
    private AutonomousDevelopmentService autonomousDevelopmentService;
    private eu.kalafatic.tryst.domain.ai.AiModelRegistryService aiModelRegistryService;
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

        // 5. Initialize Core domain & async services
        this.workspaceService = new WorkspaceService(configuration.defaultWorkspaceDirectory(),
                workspaceRepository, projectRepository, taskRepository);
        this.asyncExecutionService = new ManagedAsyncExecutionService();

        // 6. Initialize Security, Tools & AI Infrastructure
        this.permissionManager = new PermissionManager(this.auditEventRepository);
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
        this.aiModelRegistryService.registerProvider("ollama-default", "Default Ollama Endpoint", "OLLAMA", configuration.ollamaEndpoint(), true);
        try {
            this.aiModelRegistryService.discoverModelsFromInferenceService("ollama-default", this.aiInferenceService).join();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("AiProvider", java.util.Map.of("provider", "ollama-default"));
            }
        } catch (Exception e) {
            TrystLog.warn(ApplicationLifecycleCoordinator.class, "Initial AI model discovery deferred: " + e.getMessage());
            if (serverReadinessService != null) {
                serverReadinessService.markDegraded("AiProvider", e.getMessage(), java.util.Map.of("provider", "ollama-default"));
            }
        }
        this.gitService = new GitService();
        Path mirrorsDir = configuration.defaultWorkspaceDirectory().resolve("mirrors");
        this.repositoryPoolManager = new eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager(mirrorsDir, this.gitService);
        this.buildAndTestService = new BuildAndTestService();
        if (this.processExecutionManager != null) {
            this.buildAndTestService.setProcessExecutionManager(this.processExecutionManager);
        }
        this.codeReviewService = new CodeReviewService();

        // GitHub Integration
        this.gitHubCredentialRepository = new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteGitHubCredentialRepository(databaseManager);
        this.gitHubCredentialEncryptionService = new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubCredentialEncryptionService();
        eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService ghService =
                new eu.kalafatic.tryst.infrastructure.github.DefaultGitHubService(
                        this.gitHubCredentialRepository,
                        this.gitHubCredentialEncryptionService,
                        this.gitService
                );
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
                this.permissionManager
        );

        // TEIP 1.0 EVO Integration Repositories & Services
        eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository evoConfigRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoConfigurationRepository(databaseManager);
        eu.kalafatic.tryst.domain.repository.EvoExecutionRepository evoExecRepo =
                new eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteEvoExecutionRepository(databaseManager);
        eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoService =
                new eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService(evoConfigRepo, evoExecRepo, null);
        evoService.reconcileUnfinishedExecutions();
        this.taskOrchestrator.setExternalAiDelegator(new eu.kalafatic.tryst.evo.integration.service.EvoAiDelegator(evoService));

        // Scoped MCP Server
        eu.kalafatic.tryst.evo.integration.mcp.McpServer mcpServer =
                new eu.kalafatic.tryst.evo.integration.mcp.McpServer(this.permissionManager);
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.GetProjectMetadataTool(this.projectRepository));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ListWorkspaceFilesTool(this.workspaceRepository, this.permissionManager));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ReadWorkspaceFileTool(this.workspaceRepository, this.permissionManager));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.SearchWorkspaceCodeTool(this.workspaceRepository, this.permissionManager));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.GetTaskContextTool(this.taskRepository, this.taskInstructionRepository));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.ProposePatchTool(this.workspaceRepository, this.permissionManager));
        mcpServer.registerTool(new eu.kalafatic.tryst.evo.integration.mcp.ScopedMcpTools.RunBuildAndTestTool(this.workspaceRepository, this.buildAndTestService));

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
        this.processExecutionManager = new eu.kalafatic.tryst.domain.service.ProcessExecutionManager();
        this.notificationService = new eu.kalafatic.tryst.domain.service.NotificationService(
                this.notificationRepository,
                this.notificationPreferenceRepository,
                this.eventBus,
                this.userRepository
        );
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
            this.tcpService = new TrystTcpServer(configuration.serverHost(), effectiveTcpPort, workspaceService, portRegistry);
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
            TrystHttpServer server = new TrystHttpServer(
                    configuration.serverHost(), effectiveHttpPort,
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
            server.setTaskOrchestrator(this.taskOrchestrator);
            server.setGitHubService(this.gitHubService, this.gitHubCredentialRepository, this.gitHubCredentialEncryptionService);
            server.setGitCommitRepository(this.gitCommitRepository);
            server.setManagedServiceController(serviceController);
            server.setTaskIntakeService(this.taskIntakeService);
            server.setRepositoryPoolManager(this.repositoryPoolManager);
            server.setPortRegistry(portRegistry);

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

            // Register TEIP EVO Integration and MCP routes
            server.registerHandler("/api/integration/evo", new eu.kalafatic.tryst.evo.integration.service.EvoHttpHandlers.EvoIntegrationHandler(evoService, this.permissionManager));
            server.registerHandler("/api/mcp", new eu.kalafatic.tryst.evo.integration.service.EvoHttpHandlers.McpHttpHandler(mcpServer));

            this.httpService = server;
            this.httpService.start();
            if (serverReadinessService != null) {
                serverReadinessService.markHealthy("HttpServer", java.util.Map.of(
                        "host", configuration.serverHost(),
                        "port", Integer.toString(this.httpService.getPort())
                ));
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
            registerService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class, evoService);
            registerService(eu.kalafatic.tryst.evo.integration.mcp.McpServer.class, mcpServer);
            registerService(WorkspaceService.class, this.workspaceService);
            registerService(AsyncExecutionService.class, this.asyncExecutionService);
            registerService(PermissionManager.class, this.permissionManager);
            registerService(ToolRegistry.class, this.toolRegistry);
            registerService(AiContextManager.class, this.aiContextManager);
            registerService(AiInferenceService.class, this.aiInferenceService);
            registerService(eu.kalafatic.tryst.domain.ai.AiModelRegistryService.class, this.aiModelRegistryService);
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

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public AiInferenceService getAiInferenceService() {
        return aiInferenceService;
    }

    public eu.kalafatic.tryst.domain.ai.AiModelRegistryService getAiModelRegistryService() {
        return aiModelRegistryService;
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

        TrystHttpServer server = new TrystHttpServer(
                configuration.serverHost(), effectiveHttpPort,
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
        server.setTaskOrchestrator(this.taskOrchestrator);
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

        this.httpService = server;
    }

    public List<ServiceRegistration<?>> getServiceRegistrations() {
        return Collections.unmodifiableList(serviceRegistrations);
    }
}
