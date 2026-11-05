package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.*;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central Task Orchestrator managing persistent task execution,
 * configurable worker thread pools, dispatching, monitoring, cancellation, and graceful shutdown.
 */
public class TaskOrchestrator {

    private final TaskQueue taskQueue;
    private final TaskRepository taskRepository;
    private final TaskLifecycleManager lifecycleManager;
    private final WorkspaceService workspaceService;
    private final ProjectRepository projectRepository;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final AIProvider aiProvider;
    private final AiInferenceService aiClient;
    private final AiContextManager aiContextManager;
    private final ModelSelectionService modelSelectionService;
    private final PromptContextBuilder promptContextBuilder;
    private final TaskAnalyzer taskAnalyzer;
    private final ImplementationPlanner implementationPlanner;
    private final CodeChangeExecutor codeChangeExecutor;
    private final ResultValidator resultValidator;
    private final ConversationMessageRepository messageRepository;
    private eu.kalafatic.tryst.domain.build.BuildAndQualityService buildAndQualityService;
    private final ExecutionResultRepository executionResultRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final CodeReviewService codeReviewService;
    private eu.kalafatic.tryst.domain.github.GitHubService gitHubService;
    private NotificationService notificationService;
    private TaskRecoveryService taskRecoveryService;

    private final int concurrency;
    private final ExecutorService workerExecutor;
    private final ExecutorService dispatchExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, TaskExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeFutures = new ConcurrentHashMap<>();

    public TaskOrchestrator(
            TaskQueue taskQueue,
            TaskRepository taskRepository,
            TaskLifecycleManager lifecycleManager,
            WorkspaceService workspaceService,
            ProjectRepository projectRepository,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            AiInferenceService aiClient,
            AiContextManager aiContextManager,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            CodeReviewService codeReviewService,
            int concurrency
    ) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        this.aiClient = aiClient;
        if (aiClient instanceof AIProvider provider) {
            this.aiProvider = provider;
        } else if (aiClient != null) {
            this.aiProvider = new LegacyAiProviderAdapter(aiClient);
        } else {
            this.aiProvider = null;
        }
        this.aiContextManager = aiContextManager;
        this.modelSelectionService = new ModelSelectionService(new AiModelRegistryService());
        this.promptContextBuilder = new PromptContextBuilder(4096);
        this.taskAnalyzer = new TaskAnalyzer(this.permissionManager);
        this.implementationPlanner = new ImplementationPlanner();
        this.codeChangeExecutor = new CodeChangeExecutor(toolRegistry, this.permissionManager);
        this.resultValidator = new ResultValidator(toolRegistry, this.permissionManager);
        this.messageRepository = messageRepository;
        this.executionResultRepository = executionResultRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.codeReviewService = codeReviewService;
        this.concurrency = Math.max(1, concurrency);

        this.workerExecutor = Executors.newFixedThreadPool(this.concurrency, r -> {
            Thread t = new Thread(r, "tryst-task-worker-" + UUID.randomUUID().toString().substring(0, 8));
            t.setDaemon(true);
            return t;
        });

        this.dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tryst-task-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the orchestrator background dispatch loop.
     */
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            dispatchExecutor.submit(this::dispatchLoop);
            TrystLog.info(TaskOrchestrator.class, "TaskOrchestrator started with concurrency " + concurrency);
        }
    }

    /**
     * Submits a new development task to the orchestrator for asynchronous background execution.
     * The HTTP tier or client receives immediately the queued task identifier.
     */
    public Task submitTask(String projectId, String sessionId, String title, String description,
                           TaskPriority priority, String userId, String modelName) {
        return submitTask(projectId, sessionId, title, description, priority, userId, modelName, null);
    }

    public Task submitTask(String projectId, String sessionId, String title, String description,
                           TaskPriority priority, String userId, String modelName,
                           eu.kalafatic.tryst.domain.security.SecurityContext context) {
        if (title == null || title.isBlank()) {
            throw new TrystDomainException("Task title must not be blank");
        }

        if (projectId != null && projectRepository != null) {
            projectRepository.findById(projectId).ifPresent(p -> {
                permissionManager.validateUserProjectAccess(userId, p, context);
            });
        }

        Task task = new Task(
                UUID.randomUUID().toString(),
                projectId,
                sessionId,
                title,
                description,
                TaskStatus.CREATED,
                priority != null ? priority : TaskPriority.NORMAL,
                null,
                null,
                null,
                null,
                0,
                null,
                java.time.Instant.now(),
                null
        );

        taskRepository.saveTask(task);
        taskQueue.enqueue(task);

        if (notificationService != null && userId != null && !userId.isBlank()) {
            notificationService.notifyUser(
                    userId,
                    eu.kalafatic.tryst.domain.model.NotificationType.TASK_ACCEPTED.name(),
                    "Task Accepted: " + task.getTitle(),
                    "Task " + task.getId() + " has been accepted and queued for execution.",
                    "task",
                    task.getId(),
                    userId + ":TASK_ACCEPTED:" + task.getId()
            );
        }

        // Notify dispatcher
        synchronized (this) {
            notifyAll();
        }

        return task;
    }

    /**
     * Explicitly triggers one round of task claiming and execution.
     * Useful for synchronous execution in tests or on demand.
     */
    public boolean processNextTask() {
        if (!running.get()) {
            return false;
        }

        String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<Task> optTask = taskQueue.claimNext(workerId);
        if (optTask.isEmpty()) {
            return false;
        }

        Task task = optTask.get();
        executeTaskAsync(task, workerId);
        return true;
    }

    /**
     * Submits unresolved review comments and requests rework for a task in WAITING_FOR_REVIEW.
     */
    public boolean requestRework(String taskId, String reason) {
        return requestRework(taskId, reason, null, null);
    }

    public boolean requestRework(String taskId, String reason, String userId, eu.kalafatic.tryst.domain.security.SecurityContext context) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> optTask = taskRepository.findTaskById(taskId);
        if (optTask.isEmpty()) {
            return false;
        }
        Task task = optTask.get();

        if (userId != null && !userId.isBlank()) {
            permissionManager.validateTaskAccess(userId, task, context, eu.kalafatic.tryst.domain.model.Permission.EDIT_PROPOSED_CHANGES);
        }

        if (task.getStatus() != TaskStatus.WAITING_FOR_REVIEW) {
            return false;
        }

        task.transitionTo(TaskStatus.REWORK_REQUESTED);
        task.incrementReworkCount();
        task.transitionTo(TaskStatus.QUEUED);
        taskRepository.saveTask(task);

        taskQueue.enqueue(task);
        TrystLog.info(TaskOrchestrator.class, "Task " + taskId + " queued for rework: " + reason);

        synchronized (this) {
            notifyAll();
        }
        return true;
    }

    /**
     * Cancels an active or queued task.
     */
    public boolean cancelTask(String taskId, String reason) {
        return cancelTask(taskId, reason, null, null);
    }

    public boolean cancelTask(String taskId, String reason, String userId, eu.kalafatic.tryst.domain.security.SecurityContext context) {
        Objects.requireNonNull(taskId, "taskId must not be null");

        if (userId != null && !userId.isBlank()) {
            taskRepository.findTaskById(taskId).ifPresent(t -> {
                permissionManager.validateTaskAccess(userId, t, context, eu.kalafatic.tryst.domain.model.Permission.CANCEL_TASKS);
            });
        }

        TaskExecutionContext ctx = activeContexts.get(taskId);
        if (ctx != null) {
            ctx.cancel();
        }

        Future<?> f = activeFutures.get(taskId);
        if (f != null) {
            f.cancel(true);
        }

        return taskQueue.cancel(taskId, reason);
    }

    public Optional<Task> getTask(String taskId) {
        return taskRepository.findTaskById(taskId);
    }

    public List<Task> getAllTasks() {
        return taskRepository.findAllTasks();
    }

    public int getActiveTaskCount() {
        return activeContexts.size();
    }

    public int getQueueDepth() {
        return taskQueue.getQueueDepth();
    }

    public void setGitHubService(eu.kalafatic.tryst.domain.github.GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setTaskRecoveryService(TaskRecoveryService taskRecoveryService) {
        this.taskRecoveryService = taskRecoveryService;
    }

    public void setBuildAndQualityService(eu.kalafatic.tryst.domain.build.BuildAndQualityService buildAndQualityService) {
        this.buildAndQualityService = buildAndQualityService;
    }

    public TaskRecoveryService getTaskRecoveryService() {
        return taskRecoveryService;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Gracefully shuts down the task orchestrator and waits for active tasks to conclude.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            TrystLog.info(TaskOrchestrator.class, "Shutting down TaskOrchestrator...");
            dispatchExecutor.shutdownNow();

            // Signal cancellation to active in-flight contexts
            for (TaskExecutionContext ctx : activeContexts.values()) {
                ctx.cancel();
            }

            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            TrystLog.info(TaskOrchestrator.class, "TaskOrchestrator shutdown complete.");
        }
    }

    private void dispatchLoop() {
        while (running.get()) {
            try {
                while (activeContexts.size() < concurrency && running.get()) {
                    boolean processed = processNextTask();
                    if (!processed) {
                        break;
                    }
                }

                synchronized (this) {
                    if (running.get() && (activeContexts.size() >= concurrency || taskQueue.getQueueDepth() == 0)) {
                        wait(500);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                TrystLog.error(TaskOrchestrator.class, "Error in dispatch loop: " + e.getMessage(), e);
            }
        }
    }

    private void executeTaskAsync(Task task, String workerId) {
        Project project = null;
        Workspace workspace = null;

        if (task.getProjectId() != null) {
            if (projectRepository != null) {
                project = projectRepository.findById(task.getProjectId()).orElse(null);
            }
            if (workspaceService != null) {
                workspace = workspaceService.getWorkspaceForProject(task.getProjectId());
            }
        } else if (workspaceService != null) {
            workspace = workspaceService.getCurrentWorkspace();
            if (workspace != null) {
                project = workspace.getActiveProject().orElse(null);
            }
        }

        String userId = project != null ? project.getOwnerId() : "default-user";
        String resolvedModel = modelSelectionService.selectModel(task, null, eu.kalafatic.tryst.domain.model.UserRole.USER);
        TaskExecutionContext context = new TaskExecutionContext(task, project, workspace, userId, workerId, resolvedModel);
        activeContexts.put(task.getId(), context);

        CodeAgent codeAgent = new CodeAgent(aiProvider, promptContextBuilder, codeChangeExecutor);

        ResultValidator effectiveValidator = (buildAndQualityService != null)
                ? new ResultValidator(toolRegistry, permissionManager, buildAndQualityService)
                : this.resultValidator;

        TaskWorker worker = new TaskWorker(
                context,
                lifecycleManager,
                toolRegistry,
                permissionManager,
                aiProvider,
                promptContextBuilder,
                taskAnalyzer,
                implementationPlanner,
                codeChangeExecutor,
                effectiveValidator,
                codeAgent,
                messageRepository,
                executionResultRepository,
                codeReviewRepository,
                codeReviewService,
                gitHubService,
                notificationService
        );
        worker.setTaskRecoveryService(this.taskRecoveryService);

        Future<?> future = workerExecutor.submit(() -> {
            try {
                worker.call();
            } finally {
                activeContexts.remove(task.getId());
                activeFutures.remove(task.getId());
                synchronized (TaskOrchestrator.this) {
                    TaskOrchestrator.this.notifyAll();
                }
            }
        });

        activeFutures.put(task.getId(), future);
    }

    private static class LegacyAiProviderAdapter implements AIProvider {
        private final AiInferenceService delegate;

        LegacyAiProviderAdapter(AiInferenceService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getProviderId() {
            return "legacy-orchestrator-adapter";
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> checkConnection() {
            return delegate.checkConnection();
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<ProviderModelMetadata>> listAvailableModels() {
            return delegate.listModels().thenApply(list -> list.stream()
                    .map(m -> new ProviderModelMetadata(m.name(), m.sizeBytes(), m.modifiedAt(), m.format(), m.family(), m.parameterSize()))
                    .toList());
        }

        @Override
        public java.util.concurrent.CompletableFuture<InferenceResponse> generateResponse(InferenceRequest request) {
            List<AiInferenceService.ChatMessage> msgs = request.messages().stream()
                    .map(m -> new AiInferenceService.ChatMessage(m.role(), m.content()))
                    .toList();
            AiInferenceService.InferenceRequest req = new AiInferenceService.InferenceRequest(
                    request.model(), msgs, request.temperature(), request.maxTokens(), request.extraParameters()
            );
            return delegate.generateResponse(req).thenApply(res ->
                    new InferenceResponse(res.content(), res.finishReason(), res.durationMs(), res.promptEvalCount(), res.evalCount()));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> streamResponse(InferenceRequest request, java.util.function.Consumer<String> chunkConsumer) {
            List<AiInferenceService.ChatMessage> msgs = request.messages().stream()
                    .map(m -> new AiInferenceService.ChatMessage(m.role(), m.content()))
                    .toList();
            AiInferenceService.InferenceRequest req = new AiInferenceService.InferenceRequest(
                    request.model(), msgs, request.temperature(), request.maxTokens(), request.extraParameters()
            );
            return delegate.streamResponse(req, chunkConsumer);
        }
    }
}
