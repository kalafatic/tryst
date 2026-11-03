package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
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
    private final AiInferenceService aiClient;
    private final AiContextManager aiContextManager;
    private final ConversationMessageRepository messageRepository;
    private final ExecutionResultRepository executionResultRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final CodeReviewService codeReviewService;

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
        this.permissionManager = permissionManager;
        this.aiClient = aiClient;
        this.aiContextManager = aiContextManager;
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
        if (title == null || title.isBlank()) {
            throw new TrystDomainException("Task title must not be blank");
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
     * Cancels an active or queued task.
     */
    public boolean cancelTask(String taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");

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
        TaskExecutionContext context = new TaskExecutionContext(task, project, workspace, userId, workerId, "deepseek-coder:6.7b");
        activeContexts.put(task.getId(), context);

        TaskWorker worker = new TaskWorker(
                context,
                lifecycleManager,
                toolRegistry,
                permissionManager,
                aiClient,
                aiContextManager,
                messageRepository,
                executionResultRepository,
                codeReviewRepository,
                codeReviewService
        );

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
}
