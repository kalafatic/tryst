package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.*;
import eu.kalafatic.tryst.domain.env.EnvironmentEvents;
import eu.kalafatic.tryst.domain.env.ExecutionEnvironmentMonitor;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.UserRepository;
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
import java.util.function.Consumer;

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
    private ModelSelectionService modelSelectionService;
    private eu.kalafatic.tryst.domain.ai.ModelRegistry modelRegistry;
    private eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService executionConfigurationService;
    private eu.kalafatic.tryst.domain.ai.AiProviderRegistry aiProviderRegistry;
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
    private eu.kalafatic.tryst.domain.repository.TaskInstructionRepository taskInstructionRepository;
    private eu.kalafatic.tryst.domain.ai.ExternalAiDelegator externalAiDelegator;
    private eu.kalafatic.tryst.domain.studio.StudioService studioService;
    private EventBus eventBus;
    private ExecutionEnvironmentMonitor executionEnvironmentMonitor;
    private UserRepository userRepository;
    private Consumer<EnvironmentEvents.ReadinessChangedEvent> environmentReadinessListener;
    private volatile Boolean environmentLastReady = null;

    private final int concurrency;
    private final ExecutorService workerExecutor;
    private final ExecutorService dispatchExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Map<String, TaskExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeFutures = new ConcurrentHashMap<>();
    private final Map<String, WorkerRuntime> workerRuntimes = new ConcurrentHashMap<>();

    /**
     * Runtime accounting for a worker execution slot. Workers are ephemeral per task claim;
     * this record tracks the live state and outcome of each claimed slot. Historical
     * aggregates are computed from persisted task rows (tasks.worker_id).
     */
    public static final class WorkerRuntime {
        private final String workerId;
        private volatile String taskId;
        private volatile boolean busy;
        private volatile long startedAtMs;
        private volatile int completedTasks;
        private volatile int failedTasks;
        private volatile long totalDurationMs;

        WorkerRuntime(String workerId) {
            this.workerId = workerId;
        }

        public String getWorkerId() { return workerId; }
        public String getTaskId() { return taskId; }
        public boolean isBusy() { return busy; }
        public long getStartedAtMs() { return startedAtMs; }
        public int getCompletedTasks() { return completedTasks; }
        public int getFailedTasks() { return failedTasks; }
        public long getTotalDurationMs() { return totalDurationMs; }
    }

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
        AiModelRegistryService defaultReg = new AiModelRegistryService();
        this.modelRegistry = defaultReg;
        this.modelSelectionService = new ModelSelectionService(defaultReg);
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
                null,
                0,
                null,
                java.time.Instant.now(),
                null
        );

        return submitTask(task, userId, context);
    }

    /**
     * Submits a pre-created task. Enables intake flows that must allocate the task's
     * isolated workspace BEFORE the dispatch loop can claim the task (removes the
     * submit-then-allocate race, Phase 3 finalization WP-B).
     */
    public Task submitTask(Task task, String userId, eu.kalafatic.tryst.domain.security.SecurityContext context) {
        if (task == null) {
            throw new TrystDomainException("Task must not be null");
        }

        if (task.getProjectId() != null && projectRepository != null) {
            projectRepository.findById(task.getProjectId()).ifPresent(p -> {
                permissionManager.validateUserProjectAccess(userId, p, context);
            });
        }

        taskRepository.saveTask(task);

        // Execution-environment readiness gate: the task is accepted and persisted even
        // when the environment is unavailable, but it must not be enqueued for execution
        // until all required components are ready.
        if (executionEnvironmentMonitor != null && !executionEnvironmentMonitor.isReady()) {
            blockTaskForEnvironment(task, userId, executionEnvironmentMonitor.getSnapshot());
            return task;
        }

        taskQueue.enqueue(task);

        TrystLog.info(TaskOrchestrator.class,
                "Task submitted and queued: [" + task.getId() + "] " + task.getTitle() + " (Priority: " + task.getPriority() + ")");

        // Publish the dedicated task-created/accepted event so the Navigator (and any other
        // subscriber) can reflect the newly accepted task immediately, independent of the
        // CREATED->QUEUED state-transition event emitted by the queue.
        if (eventBus != null) {
            eventBus.publish(new ApplicationEvents.TaskAddedEvent(
                    task.getId(), task.getProjectId(), task.getTitle(), task.getDescription()));
        }

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
     * Parks an accepted, persisted task in {@link TaskStatus#BLOCKED} because the
     * execution environment is not ready. The submitting user is informed immediately;
     * the server administrator receives one notification per readiness episode
     * (deduplicated through the notification dedup key).
     */
    private void blockTaskForEnvironment(Task task, String userId,
                                         ExecutionEnvironmentMonitor.EnvironmentSnapshot snapshot) {
        String reason = snapshot.readyMessage();
        lifecycleManager.transition(task, TaskStatus.BLOCKED,
                "Blocked: execution environment not ready", reason);
        task.setFailureReason(reason);
        lifecycleManager.persist(task);

        String problemList = formatProblemList(snapshot.problems());

        TrystLog.info(TaskOrchestrator.class,
                "Task blocked (execution environment not ready): [" + task.getId() + "] "
                        + task.getTitle() + " reason=" + reason);

        if (eventBus != null) {
            eventBus.publish(new ApplicationEvents.TaskAddedEvent(
                    task.getId(), task.getProjectId(), task.getTitle(), task.getDescription()));
            eventBus.publish(new EnvironmentEvents.TaskBlockedEvent(
                    task.getId(), task.getTitle(), reason, snapshot.problems(), snapshot.episodeKey()));
        }

        if (notificationService != null && userId != null && !userId.isBlank()) {
            notificationService.notifyUser(
                    userId,
                    "TASK_BLOCKED",
                    "Task accepted — execution delayed: " + task.getTitle(),
                    "Your request was successfully received and stored by TRYST.\n"
                            + "Execution cannot currently start because the server environment is unavailable:\n"
                            + problemList
                            + "\nThe server administrator has been notified.\n"
                            + "Your task remains safely queued and will become eligible for execution once the environment is restored.",
                    "task",
                    task.getId(),
                    userId + ":TASK_BLOCKED:" + task.getId() + ":" + snapshot.episodeKey()
            );
        }

        notifyAdministrators("TASK_BLOCKED", "Task delayed — execution environment not ready",
                "A new task \"" + task.getTitle() + "\" (id " + task.getId()
                        + ") has arrived, but execution cannot start.\n\nMissing dependency:\n"
                        + problemList
                        + "\nThe task remains safely queued and will start automatically once the environment is restored.",
                "task", task.getId(), snapshot.episodeKey());

        // Notify dispatcher (it will not claim while the environment is unavailable)
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * Called by the EventBus when execution readiness changes. On recovery every blocked
     * task is released back into the queue in FIFO order and its submitting user is
     * notified; on degradation tasks still waiting in the queue are parked as BLOCKED so
     * the Navigator and Dashboard show the real state. Administrators receive one
     * notification per degradation episode and one per restoration.
     */
    private void onEnvironmentReadinessChanged(EnvironmentEvents.ReadinessChangedEvent event) {
        Boolean previous = environmentLastReady;
        environmentLastReady = event.ready();
        if (event.ready()) {
            releaseBlockedTasks();
            // Notify administrators only on a genuine recovery (known-unready -> ready),
            // not on the startup assessment that follows an application restart.
            if (Boolean.FALSE.equals(previous)) {
                notifyAdministrators("ENV_RESTORED",
                        "Execution environment restored",
                        "The TRYST execution environment has been restored.\n"
                                + "Eligible blocked tasks have been released and execution is starting.",
                        "environment", null, "READY:" + event.eventId());
            }
        } else {
            blockQueuedTasks(event.problems());
            String problemList = formatProblemList(event.problems());
            notifyAdministrators("ENV_DEGRADED",
                    "Execution environment unavailable",
                    "The TRYST execution environment is no longer ready:\n"
                            + problemList
                            + "\nNew tasks will be safely queued (BLOCKED) until the environment is restored.",
                    "environment", null, event.episodeKey());
        }
    }

    /**
     * Releases every BLOCKED task back into the execution queue (FIFO order) and informs
     * the submitting users that the environment has been restored. Safe to call multiple
     * times: tasks already released are not re-notified.
     */
    public int releaseBlockedTasks() {
        List<Task> blocked = taskRepository.findTasksByTaskStatus(TaskStatus.BLOCKED);
        int released = 0;
        for (Task task : blocked) {
            try {
                lifecycleManager.transition(task, TaskStatus.QUEUED,
                        "Execution environment restored; task released for execution", null);
                taskQueue.enqueue(task);
                released++;
                TrystLog.info(TaskOrchestrator.class,
                        "Task released after environment recovery: [" + task.getId() + "] " + task.getTitle());
                if (notificationService != null) {
                    String ownerId = resolveTaskOwner(task);
                    if (ownerId != null) {
                        notificationService.notifyUser(
                                ownerId,
                                "TASK_RELEASED",
                                "Execution environment restored — task is starting: " + task.getTitle(),
                                "The TRYST execution environment has been restored. "
                                        + "Your task \"" + task.getTitle() + "\" has been released from the blocked queue and is starting.",
                                "task",
                                task.getId(),
                                ownerId + ":TASK_RELEASED:" + task.getId()
                        );
                    }
                }
            } catch (Exception e) {
                TrystLog.error(TaskOrchestrator.class,
                        "Failed to release blocked task " + task.getId() + ": " + e.getMessage(), e);
            }
        }
        if (released > 0) {
            TrystLog.info(TaskOrchestrator.class,
                    "Execution environment restored: released " + released + " blocked task(s)");
            synchronized (this) {
                notifyAll();
            }
        }
        return released;
    }

    /**
     * Parks queued (not yet claimed) tasks as BLOCKED when the execution environment
     * degrades, preserving queue order for the later release.
     */
    public int blockQueuedTasks(List<String> problems) {
        if (taskRepository == null) {
            return 0;
        }
        String reason = problems == null || problems.isEmpty()
                ? "Execution environment became unavailable."
                : "Execution environment became unavailable: " + String.join(" ", problems);
        List<Task> queued = taskRepository.findTasksByTaskStatus(TaskStatus.QUEUED);
        int blocked = 0;
        for (Task task : queued) {
            try {
                lifecycleManager.transition(task, TaskStatus.BLOCKED,
                        "Blocked: execution environment not ready", reason);
                task.setFailureReason(reason);
                lifecycleManager.persist(task);
                blocked++;
                TrystLog.info(TaskOrchestrator.class,
                        "Task parked as BLOCKED (environment degraded): [" + task.getId() + "] " + task.getTitle());
                if (notificationService != null) {
                    String ownerId = resolveTaskOwner(task);
                    if (ownerId != null) {
                        notificationService.notifyUser(
                                ownerId,
                                "TASK_BLOCKED",
                                "Task delayed — execution environment unavailable: " + task.getTitle(),
                                "Execution of your task \"" + task.getTitle()
                                        + "\" cannot continue because the server environment became unavailable:\n"
                                        + formatProblemList(problems)
                                        + "\nThe server administrator has been notified. Your task remains safely queued "
                                        + "and will start once the environment is restored.",
                                "task",
                                task.getId(),
                                ownerId + ":TASK_BLOCKED:" + task.getId()
                        );
                    }
                }
            } catch (Exception e) {
                TrystLog.error(TaskOrchestrator.class,
                        "Failed to park queued task " + task.getId() + " as BLOCKED: " + e.getMessage(), e);
            }
        }
        if (blocked > 0) {
            TrystLog.info(TaskOrchestrator.class,
                    "Execution environment degraded: parked " + blocked + " queued task(s) as BLOCKED");
        }
        return blocked;
    }

    /**
     * Resolves the best-effort owner id for a task (project owner, otherwise the default
     * dev user id). Returns {@code null} when no owner can be determined.
     */
    private String resolveTaskOwner(Task task) {
        if (task.getProjectId() != null && projectRepository != null) {
            try {
                Optional<Project> project = projectRepository.findById(task.getProjectId());
                if (project.isPresent() && project.get().getOwnerId() != null
                        && !project.get().getOwnerId().isBlank()) {
                    return project.get().getOwnerId();
                }
            } catch (Exception ignored) {}
        }
        if (eu.kalafatic.tryst.core.config.ApplicationConfiguration.isDevelopmentMode()) {
            return eu.kalafatic.tryst.domain.security.SecurityContext.DEV.user().getId();
        }
        return "default-user";
    }

    /**
     * Sends one notification per readiness episode to every administrator account
     * (deduplicated through the shared dedup key). Never notifies the same episode twice.
     */
    private void notifyAdministrators(String type, String title, String message,
                                      String refType, String refId, String episodeKey) {
        if (notificationService == null || userRepository == null) {
            TrystLog.warn(TaskOrchestrator.class,
                    "Administrator notification skipped (notification service or user repository unavailable)");
            return;
        }
        List<User> admins;
        try {
            admins = userRepository.findAll().stream()
                    .filter(u -> u.getRole() == UserRole.ADMIN)
                    .toList();
        } catch (Exception e) {
            TrystLog.warn(TaskOrchestrator.class,
                    "Could not resolve administrator accounts: " + e.getMessage());
            return;
        }
        if (admins.isEmpty()) {
            TrystLog.info(TaskOrchestrator.class,
                    "No administrator account configured; environment blocking notification logged only");
            return;
        }
        String dedupKey = "admin:" + type + ":" + episodeKey;
        for (User admin : admins) {
            notificationService.notifyUser(
                    admin.getId(),
                    type,
                    title,
                    message,
                    refType,
                    refId,
                    dedupKey
            );
        }
        TrystLog.info(TaskOrchestrator.class,
                "Administrators notified: " + admins.size() + " recipient(s), episode=" + episodeKey);
    }

    private static String formatProblemList(List<String> problems) {
        if (problems == null || problems.isEmpty()) {
            return "  - Execution environment is not ready.";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : problems) {
            sb.append("  - ").append(p).append("\n");
        }
        return sb.toString();
    }

    /**
     * Explicitly triggers one round of task claiming and execution.
     * Useful for synchronous execution in tests or on demand.
     */
    public boolean processNextTask() {
        if (!running.get()) {
            return false;
        }

        // Execution-environment gate: never claim work while the environment is not
        // ready. Blocked tasks are parked outside the queue; this guard additionally
        // protects tasks that were queued while the environment was ready.
        if (executionEnvironmentMonitor != null && !executionEnvironmentMonitor.isReady()) {
            return false;
        }

        String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<Task> optTask = taskQueue.claimNext(workerId);
        if (optTask.isEmpty()) {
            return false;
        }

        Task task = optTask.get();
        TrystLog.info(TaskOrchestrator.class,
                "Worker " + workerId + " claimed task: [" + task.getId() + "] " + task.getTitle());
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

        // Execution-environment gate for rework: a task requested for rework while the
        // environment is unavailable is parked as BLOCKED instead of being enqueued.
        if (executionEnvironmentMonitor != null && !executionEnvironmentMonitor.isReady()) {
            var snapshot = executionEnvironmentMonitor.getSnapshot();
            lifecycleManager.transition(task, TaskStatus.BLOCKED,
                    "Blocked: execution environment not ready", snapshot.readyMessage());
            task.setFailureReason(snapshot.readyMessage());
            lifecycleManager.persist(task);
            if (notificationService != null && userId != null && !userId.isBlank()) {
                notificationService.notifyUser(
                        userId,
                        "TASK_BLOCKED",
                        "Task delayed — execution environment unavailable: " + task.getTitle(),
                        "Your task \"" + task.getTitle() + "\" was accepted for rework, but execution "
                                + "cannot start because the server environment is unavailable.\n"
                                + formatProblemList(snapshot.problems())
                                + "It will start automatically once the environment is restored.",
                        "task",
                        task.getId(),
                        userId + ":TASK_BLOCKED:" + task.getId() + ":" + snapshot.episodeKey()
                );
            }
            synchronized (this) {
                notifyAll();
            }
            return true;
        }

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

    /**
     * Safely pauses a task at the nearest execution boundary, preserving recoverable state.
     */
    public boolean pauseTask(String taskId, String userId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> optTask = taskRepository.findTaskById(taskId);
        if (optTask.isEmpty()) return false;
        Task task = optTask.get();

        TaskExecutionContext ctx = activeContexts.get(taskId);
        if (ctx != null) {
            ctx.pause();
            TrystLog.info(TaskOrchestrator.class, "Requested pause for active task " + taskId + " (takes effect at next stage boundary)");
            return true;
        }

        // If queued/created, transition to PAUSED so claimNext won't pick it up
        if (task.getStatus() == TaskStatus.QUEUED || task.getStatus() == TaskStatus.CREATED) {
            lifecycleManager.transition(task, TaskStatus.PAUSED, "Paused in queue", null);
            if (notificationService != null && userId != null) {
                notificationService.notifyUser(userId, "TASK_PAUSED", "Task Paused: " + task.getTitle(),
                        "Task execution paused in queue.", "task", taskId, userId + ":TASK_PAUSED:" + taskId);
            }
            return true;
        }
        return false;
    }

    /**
     * Resumes a previously paused task, continuing from its last recoverable state.
     */
    public boolean resumeTask(String taskId, String userId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> optTask = taskRepository.findTaskById(taskId);
        if (optTask.isEmpty()) return false;
        Task task = optTask.get();

        // Active context: resume the suspended worker at its stage boundary.
        TaskExecutionContext ctx = activeContexts.get(taskId);
        if (ctx != null) {
            if (!ctx.isPaused()) {
                return false;
            }
            ctx.resume();
            TrystLog.info(TaskOrchestrator.class, "Resumed active task " + taskId);
            if (notificationService != null && userId != null) {
                notificationService.notifyUser(userId, "TASK_RESUMED", "Task Resumed: " + task.getTitle(),
                        "Task execution resumed from paused boundary.", "task", taskId, userId + ":TASK_RESUMED:" + taskId);
            }
            return true;
        }

        if (task.getStatus() == TaskStatus.PAUSED) {
            lifecycleManager.transition(task, TaskStatus.QUEUED, "Resuming paused task", null);
            taskQueue.enqueue(task);
            if (notificationService != null && userId != null) {
                notificationService.notifyUser(userId, "TASK_RESUMED", "Task Resumed: " + task.getTitle(),
                        "Task queued for execution resumption.", "task", taskId, userId + ":TASK_RESUMED:" + taskId);
            }
            synchronized (this) {
                notifyAll();
            }
            return true;
        }
        return false;
    }

    /**
     * Re-queues a task parked in RECOVERY_REQUIRED after a server restart, honoring its
     * recovery decision. Tasks requiring explicit operator confirmation need forceRetry.
     */
    public boolean retryRecoveredTask(String taskId, String operatorId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskRecoveryService == null) {
            return false;
        }
        boolean retried = taskRecoveryService.retryRecoveredTask(taskId);
        if (retried) {
            synchronized (this) {
                notifyAll();
            }
        }
        if (notificationService != null && operatorId != null && retried) {
            notificationService.notifyUser(operatorId, "TASK_RETRIED", "Task Re-Queued After Recovery",
                    "Task " + taskId + " was re-queued for execution after recovery reconciliation.", "task", taskId,
                    operatorId + ":TASK_RETRIED:" + taskId);
        }
        return retried;
    }

    /**
     * Forces a recovered task back into the queue after explicit operator confirmation,
     * even when the recovery decision requires user intervention.
     */
    public boolean forceRetryRecoveredTask(String taskId, String operatorId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (taskRecoveryService == null) {
            return false;
        }
        boolean retried = taskRecoveryService.forceRetryRecoveredTask(taskId, operatorId);
        if (retried) {
            synchronized (this) {
                notifyAll();
            }
        }
        return retried;
    }

    /**
     * Live per-worker runtime accounting for the current server session.
     */
    public List<WorkerRuntime> getWorkerRuntimes() {
        return List.copyOf(workerRuntimes.values());
    }

    /**
     * Archives a completed, cancelled or failed task while preserving all history, logs, and artifacts.
     */
    public boolean archiveTask(String taskId, String userId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> optTask = taskRepository.findTaskById(taskId);
        if (optTask.isEmpty()) return false;
        Task task = optTask.get();

        lifecycleManager.transition(task, TaskStatus.ARCHIVED, "Task archived", null);
        if (notificationService != null && userId != null) {
            notificationService.notifyUser(userId, "TASK_ARCHIVED", "Task Archived: " + task.getTitle(),
                    "Task moved to archive; history and logs preserved.", "task", taskId, userId + ":TASK_ARCHIVED:" + taskId);
        }
        return true;
    }

    /**
     * Deletes task according to policy: cancels if running, deletes metadata and records audit log.
     */
    public boolean deleteTask(String taskId, boolean deleteWorkspace, String userId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Optional<Task> optTask = taskRepository.findTaskById(taskId);
        if (optTask.isEmpty()) return false;

        cancelTask(taskId, "Task deleted by user");
        if (deleteWorkspace && workspaceService != null) {
            try {
                var ws = workspaceService.getWorkspaceForTask(taskId);
                if (ws != null) {
                    workspaceService.cleanupWorkspace(ws.getId(), true);
                }
            } catch (Exception ignored) {}
        }
        return taskRepository.delete(taskId);
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

    public boolean moveTaskUp(String taskId) {
        return taskQueue.moveUp(taskId);
    }

    public boolean moveTaskDown(String taskId) {
        return taskQueue.moveDown(taskId);
    }

    public Map<String, TaskExecutionContext> getActiveContexts() {
        return java.util.Collections.unmodifiableMap(activeContexts);
    }

    public Optional<TaskExecutionContext> getExecutionContext(String taskId) {
        return Optional.ofNullable(activeContexts.get(taskId));
    }

    public void setGitHubService(eu.kalafatic.tryst.domain.github.GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        if (eventBus != null) {
            // Subscribe to readiness changes so blocked tasks are released the moment the
            // environment recovers, and queued tasks are parked when it degrades.
            this.environmentReadinessListener = this::onEnvironmentReadinessChanged;
            eventBus.subscribe(EnvironmentEvents.ReadinessChangedEvent.class, environmentReadinessListener);
        }
    }

    /**
     * Wires the authoritative execution environment monitor. When wired, incoming tasks
     * are blocked (not enqueued) while the environment is not ready, the dispatcher
     * refrains from claiming tasks while the environment is unavailable, and blocked
     * tasks are released automatically when readiness is restored.
     */
    public void setExecutionEnvironmentMonitor(ExecutionEnvironmentMonitor executionEnvironmentMonitor) {
        this.executionEnvironmentMonitor = executionEnvironmentMonitor;
    }

    public ExecutionEnvironmentMonitor getExecutionEnvironmentMonitor() {
        return executionEnvironmentMonitor;
    }

    /**
     * Wires the user repository used to resolve administrator accounts for
     * execution-environment blocking notifications.
     */
    public void setUserRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
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

    public void setTaskInstructionRepository(eu.kalafatic.tryst.domain.repository.TaskInstructionRepository taskInstructionRepository) {
        this.taskInstructionRepository = taskInstructionRepository;
    }

    public void setModelRegistry(eu.kalafatic.tryst.domain.ai.ModelRegistry registry) {
        if (registry != null) {
            this.modelRegistry = registry;
            rebuildModelSelectionService();
        }
    }

    /**
     * Wires the unified execution-configuration service (GLOBAL → PROJECT → TASK) so
     * task model resolution follows the single authoritative configuration chain.
     */
    public void setExecutionConfigurationService(
            eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService executionConfigurationService) {
        this.executionConfigurationService = executionConfigurationService;
        rebuildModelSelectionService();
    }

    /**
     * Wires the provider registry so the provider default model is the last permitted
     * fallback level (never an implicit hardcoded model).
     */
    public void setAiProviderRegistry(eu.kalafatic.tryst.domain.ai.AiProviderRegistry aiProviderRegistry) {
        this.aiProviderRegistry = aiProviderRegistry;
        rebuildModelSelectionService();
    }

    private void rebuildModelSelectionService() {
        this.modelSelectionService = new eu.kalafatic.tryst.domain.ai.ModelSelectionService(
                this.modelRegistry, this.executionConfigurationService, this.aiProviderRegistry);
    }

    public eu.kalafatic.tryst.domain.ai.ModelRegistry getModelRegistry() {
        return this.modelRegistry;
    }

    public void setExternalAiDelegator(eu.kalafatic.tryst.domain.ai.ExternalAiDelegator externalAiDelegator) {
        this.externalAiDelegator = externalAiDelegator;
    }

    public void setStudioService(eu.kalafatic.tryst.domain.studio.StudioService studioService) {
        this.studioService = studioService;
    }

    public eu.kalafatic.tryst.domain.studio.StudioService getStudioService() {
        return this.studioService;
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
                workspace = workspaceService.getWorkspaceForTask(task.getId());
                if (workspace == null) {
                    workspace = workspaceService.getWorkspaceForProject(task.getProjectId());
                }
            }
        } else if (workspaceService != null) {
            workspace = workspaceService.getWorkspaceForTask(task.getId());
            if (workspace == null) {
                workspace = workspaceService.getCurrentWorkspace();
            }
            if (workspace != null) {
                project = workspace.getActiveProject().orElse(null);
            }
        }

        // Project-level build/test commands override auto-detection (persisted project
        // configuration). Empty values leave the existing technology auto-detection active.
        if (project != null && workspace != null) {
            if (project.getBuildCommand() != null && !project.getBuildCommand().isBlank()) {
                workspace.setMetadata("build_command", project.getBuildCommand().trim());
            }
            if (project.getTestCommand() != null && !project.getTestCommand().isBlank()) {
                workspace.setMetadata("test_command", project.getTestCommand().trim());
            }
        }

        String userId = project != null ? project.getOwnerId() : "default-user";
        String resolvedModel = modelSelectionService.selectModel(task, null, eu.kalafatic.tryst.domain.model.UserRole.USER);
        if (resolvedModel == null || resolvedModel.isBlank()) {
            // Single-source-of-truth contract: when no model is selected, the task fails
            // with an explicit configuration error. Never substitute a hardcoded model.
            String message = "No AI model is selected for execution. Configure the execution "
                    + "environment (AI Environment page: provider + model) and re-submit the task.";
            lifecycleManager.transition(task, TaskStatus.FAILED, "Configuration error: no model selected", message);
            task.setFailureReason(message);
            lifecycleManager.persist(task);
            if (notificationService != null && userId != null && !userId.isBlank()) {
                notificationService.notifyUser(
                        userId,
                        eu.kalafatic.tryst.domain.model.NotificationType.TASK_FAILED.name(),
                        "Task Failed — no AI model selected: " + task.getTitle(),
                        "Task " + task.getId() + " cannot execute because no AI model is selected. "
                                + "Configure the execution environment (provider and model) and re-submit the task.",
                        "task",
                        task.getId(),
                        userId + ":TASK_FAILED:NO_MODEL:" + task.getId()
                );
            }
            TrystLog.error(TaskOrchestrator.class,
                    "Task " + task.getId() + " failed: no AI model selected (execution configuration unresolved)");
            return;
        }
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
        worker.setTaskInstructionRepository(this.taskInstructionRepository);
        worker.setExternalAiDelegator(this.externalAiDelegator);
        worker.setStudioService(this.studioService);

        WorkerRuntime runtime = new WorkerRuntime(workerId);
        runtime.busy = true;
        runtime.taskId = task.getId();
        runtime.startedAtMs = System.currentTimeMillis();
        workerRuntimes.put(workerId, runtime);

        Future<?> future = workerExecutor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                worker.call();
            } finally {
                runtime.totalDurationMs += (System.currentTimeMillis() - start);
                runtime.busy = false;
                runtime.taskId = null;
                if (task.getStatus() == TaskStatus.WAITING_FOR_REVIEW || task.getStatus() == TaskStatus.COMPLETED) {
                    runtime.completedTasks++;
                } else if (task.getStatus() == TaskStatus.FAILED) {
                    runtime.failedTasks++;
                }
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
