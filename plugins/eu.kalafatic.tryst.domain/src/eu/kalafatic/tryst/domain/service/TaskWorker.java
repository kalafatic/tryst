package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.*;
import eu.kalafatic.tryst.domain.github.GitHubService;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Worker execution engine executing long-running autonomous development tasks.
 * Follows the 10-step autonomous development workflow:
 * 1. Analyze the user request (TaskAnalyzer).
 * 2. Inspect repository structure & identify source files.
 * 3. Analyze dependencies and existing conventions.
 * 4. Generate an implementation plan (ImplementationPlanner).
 * 5. Execute changes incrementally (CodeAgent, CodeChangeExecutor).
 * 6. Track every modified file.
 * 7. Run build verification (ResultValidator).
 * 8. Run relevant test verification (ResultValidator).
 * 9. Persist analysis, implementation plans, execution events and results.
 * 10. Produce a reviewable result with diff for WAITING_FOR_REVIEW.
 *
 * Enforces workspace isolation, timeouts, streaming diagnostics, cancellation,
 * and retry policies.
 */
public class TaskWorker implements Callable<ExecutionResult> {

    private final TaskExecutionContext context;
    private final TaskLifecycleManager lifecycleManager;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final AIProvider aiProvider;
    private final PromptContextBuilder promptContextBuilder;
    private final TaskAnalyzer taskAnalyzer;
    private final ImplementationPlanner implementationPlanner;
    private final CodeChangeExecutor codeChangeExecutor;
    private final ResultValidator resultValidator;
    private final CodeAgent codeAgent;
    private final ConversationMessageRepository messageRepository;
    private final ExecutionResultRepository executionResultRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final CodeReviewService codeReviewService;
    private GitHubService gitHubService;
    private NotificationService notificationService;
    private TaskRecoveryService taskRecoveryService;

    public TaskWorker(
            TaskExecutionContext context,
            TaskLifecycleManager lifecycleManager,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            AIProvider aiProvider,
            PromptContextBuilder promptContextBuilder,
            TaskAnalyzer taskAnalyzer,
            ImplementationPlanner implementationPlanner,
            CodeChangeExecutor codeChangeExecutor,
            ResultValidator resultValidator,
            CodeAgent codeAgent,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            CodeReviewService codeReviewService,
            GitHubService gitHubService,
            NotificationService notificationService
    ) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        this.aiProvider = aiProvider;
        this.promptContextBuilder = promptContextBuilder != null ? promptContextBuilder : new PromptContextBuilder();
        this.taskAnalyzer = taskAnalyzer != null ? taskAnalyzer : new TaskAnalyzer(this.permissionManager);
        this.implementationPlanner = implementationPlanner != null ? implementationPlanner : new ImplementationPlanner();
        this.codeChangeExecutor = codeChangeExecutor != null ? codeChangeExecutor : new CodeChangeExecutor(this.toolRegistry, this.permissionManager);
        this.resultValidator = resultValidator != null ? resultValidator : new ResultValidator(this.toolRegistry, this.permissionManager);
        this.codeAgent = codeAgent != null ? codeAgent : new CodeAgent(this.aiProvider, this.promptContextBuilder, this.codeChangeExecutor);
        this.messageRepository = messageRepository;
        this.executionResultRepository = executionResultRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.codeReviewService = codeReviewService;
        this.gitHubService = gitHubService;
        this.notificationService = notificationService;
    }

    public TaskWorker(
            TaskExecutionContext context,
            TaskLifecycleManager lifecycleManager,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            AIProvider aiProvider,
            PromptContextBuilder promptContextBuilder,
            TaskAnalyzer taskAnalyzer,
            ImplementationPlanner implementationPlanner,
            CodeChangeExecutor codeChangeExecutor,
            ResultValidator resultValidator,
            CodeAgent codeAgent,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            CodeReviewService codeReviewService,
            GitHubService gitHubService
    ) {
        this(context, lifecycleManager, toolRegistry, permissionManager, aiProvider, promptContextBuilder,
                taskAnalyzer, implementationPlanner, codeChangeExecutor, resultValidator, codeAgent,
                messageRepository, executionResultRepository, codeReviewRepository, codeReviewService,
                gitHubService, null);
    }

    /**
     * Backward-compatible constructor for existing callers and legacy tests.
     */
    public TaskWorker(
            TaskExecutionContext context,
            TaskLifecycleManager lifecycleManager,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            AiInferenceService aiClient,
            AiContextManager aiContextManager,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            CodeReviewService codeReviewService,
            GitHubService gitHubService
    ) {
        this(
                context,
                lifecycleManager,
                toolRegistry,
                permissionManager,
                (aiClient instanceof AIProvider provider) ? provider : (aiClient != null ? new LegacyAiProviderAdapter(aiClient) : null),
                aiContextManager != null ? new PromptContextBuilder(4096) : null,
                new TaskAnalyzer(permissionManager != null ? permissionManager : new PermissionManager()),
                new ImplementationPlanner(),
                new CodeChangeExecutor(toolRegistry, permissionManager != null ? permissionManager : new PermissionManager()),
                new ResultValidator(toolRegistry, permissionManager != null ? permissionManager : new PermissionManager()),
                null,
                messageRepository,
                executionResultRepository,
                codeReviewRepository,
                codeReviewService,
                gitHubService
        );
    }

    public TaskWorker(
            TaskExecutionContext context,
            TaskLifecycleManager lifecycleManager,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            AiInferenceService aiClient,
            AiContextManager aiContextManager,
            ConversationMessageRepository messageRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            CodeReviewService codeReviewService
    ) {
        this(context, lifecycleManager, toolRegistry, permissionManager, aiClient, aiContextManager,
                messageRepository, executionResultRepository, codeReviewRepository, codeReviewService, null);
    }

    public void setGitHubService(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void setTaskRecoveryService(TaskRecoveryService taskRecoveryService) {
        this.taskRecoveryService = taskRecoveryService;
    }

    private void recordRecoveryCheckpoint(Task task, TaskStatus stage, String data) {
        if (taskRecoveryService != null) {
            taskRecoveryService.recordCheckpoint(task.getId(), stage, data);
        }
    }

    @Override
    public ExecutionResult call() {
        Task task = context.getTask();
        long startTime = System.currentTimeMillis();
        StringBuilder logBuilder = new StringBuilder();
        AiFailureClassification failureClass = AiFailureClassification.NONE;

        try {
            Workspace ws = context.getWorkspace();
            if (ws != null) {
                if (task.getReworkCount() > 0 && ws.getStatus() == eu.kalafatic.tryst.domain.model.WorkspaceStatus.WAITING_FOR_REVIEW) {
                    ws.rework();
                } else if (ws.getStatus() == eu.kalafatic.tryst.domain.model.WorkspaceStatus.READY) {
                    ws.activate();
                }
            }

            // Stage 1: CLONING / Workspace verification & remote GitHub clone/fetch
            checkCancelled();
            if (task.getStatus() == TaskStatus.QUEUED || task.getStatus() == TaskStatus.CLAIMED) {
                lifecycleManager.transition(task, TaskStatus.CLONING, "Verifying project repository and workspace structure", null);
                recordRecoveryCheckpoint(task, TaskStatus.CLONING, "{\"stage\":\"CLONING\",\"worker\":\"" + context.getWorkerId() + "\"}");
            }
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: CLONING\n");

            performRepositoryCloningStage(task, logBuilder);
            if (notificationService != null && context.getUserId() != null) {
                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.REPOSITORY_CLONED.name(),
                        "Repository Cloned: " + task.getTitle(),
                        "Repository environment prepared in sandbox for task " + task.getId(),
                        "task",
                        task.getId(),
                        context.getUserId() + ":REPOSITORY_CLONED:" + task.getId()
                );
            }

            // Stage 2: ANALYZING (Analyze request, inspect repo, identify relevant source files, analyze conventions)
            checkCancelled();
            lifecycleManager.transition(task, TaskStatus.ANALYZING, "Analyzing project code base and requirements", null);
            recordRecoveryCheckpoint(task, TaskStatus.ANALYZING, "{\"stage\":\"ANALYZING\"}");
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: ANALYZING\n");

            TaskAnalyzer.AnalysisResult analysis = taskAnalyzer.analyze(task, context.getWorkspace());
            logBuilder.append("[Analysis]: ").append(analysis.summary()).append("\n");
            for (String conv : analysis.discoveredConventions()) {
                logBuilder.append("  - Convention: ").append(conv).append("\n");
            }
            for (Path rel : analysis.identifiedFiles()) {
                logBuilder.append("  - Identified file: ").append(rel).append("\n");
            }
            if (notificationService != null && context.getUserId() != null) {
                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.AI_ANALYSIS_COMPLETED.name(),
                        "AI Analysis Completed: " + task.getTitle(),
                        analysis.summary(),
                        "task",
                        task.getId(),
                        context.getUserId() + ":AI_ANALYSIS_COMPLETED:" + task.getId()
                );
            }

            // Stage 3: PLANNING (Generate structured implementation plan)
            checkCancelled();
            lifecycleManager.transition(task, TaskStatus.PLANNING, "Formulating autonomous execution plan", null);
            recordRecoveryCheckpoint(task, TaskStatus.PLANNING, "{\"stage\":\"PLANNING\"}");
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: PLANNING\n");

            ImplementationPlanner.ExecutionPlan plan = implementationPlanner.createPlan(task, analysis, context.getWorkspace());
            logBuilder.append("[Plan Goal]: ").append(plan.goal()).append("\n");
            for (ImplementationPlanner.PlanStep step : plan.steps()) {
                logBuilder.append("  Step ").append(step.order()).append(": ").append(step.description()).append("\n");
            }

            // Stage 4: IMPLEMENTING (Execute changes incrementally and track modified files)
            checkCancelled();
            lifecycleManager.transition(task, TaskStatus.IMPLEMENTING, "Executing AI inference and modifying files", null);
            recordRecoveryCheckpoint(task, TaskStatus.IMPLEMENTING, "{\"stage\":\"IMPLEMENTING\",\"goal\":\"" + plan.goal().replace("\"", "\\\"") + "\"}");
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: IMPLEMENTING\n");
            if (notificationService != null && context.getUserId() != null) {
                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.IMPLEMENTATION_STARTED.name(),
                        "Implementation Started: " + task.getTitle(),
                        "Autonomous code modifications started based on execution plan: " + plan.goal(),
                        "task",
                        task.getId(),
                        context.getUserId() + ":IMPLEMENTATION_STARTED:" + task.getId() + ":" + task.getReworkCount()
                );
            }

            executeAgentImplementationLoop(task, analysis, plan, logBuilder);

            Set<Path> modified = codeChangeExecutor.getModifiedFiles();
            logBuilder.append("[Tracked Modifications]: ").append(modified.size()).append(" files modified\n");
            for (Path p : modified) {
                logBuilder.append("  * ").append(p).append("\n");
            }

            // Stage 5: BUILDING (Run build verification)
            checkCancelled();
            lifecycleManager.transition(task, TaskStatus.BUILDING, "Compiling modified codebase", null);
            recordRecoveryCheckpoint(task, TaskStatus.BUILDING, "{\"stage\":\"BUILDING\",\"modifiedCount\":" + modified.size() + "}");
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: BUILDING\n");

            ResultValidator.ValidationReport validation = resultValidator.validate(context.getWorkspace());
            if (!validation.buildSuccess()) {
                failureClass = AiFailureClassification.BUILD_FAILURE;
                throw new IllegalStateException("Build failed during task execution: " + validation.details());
            }
            logBuilder.append("[Build Passed]: Compiler verified successfully.\n");
            if (notificationService != null && context.getUserId() != null) {
                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.BUILD_COMPLETED.name(),
                        "Build Completed: " + task.getTitle(),
                        "Compilation and build verification passed successfully.",
                        "task",
                        task.getId(),
                        context.getUserId() + ":BUILD_COMPLETED:" + task.getId() + ":" + task.getReworkCount()
                );
            }

            // Stage 6: TESTING (Run test verification)
            checkCancelled();
            lifecycleManager.transition(task, TaskStatus.TESTING, "Executing project tests and verification", null);
            recordRecoveryCheckpoint(task, TaskStatus.TESTING, "{\"stage\":\"TESTING\"}");
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Stage: TESTING\n");

            if (!validation.testSuccess()) {
                failureClass = AiFailureClassification.TEST_FAILURE;
                if (notificationService != null && context.getUserId() != null) {
                    notificationService.notifyUser(
                            context.getUserId(),
                            eu.kalafatic.tryst.domain.model.NotificationType.TESTS_FAILED.name(),
                            "Tests Failed: " + task.getTitle(),
                            validation.details(),
                            "task",
                            task.getId(),
                            context.getUserId() + ":TESTS_FAILED:" + task.getId() + ":" + task.getReworkCount()
                    );
                }
                throw new IllegalStateException("Tests failed during task verification: " + validation.details());
            }
            logBuilder.append("[Tests Passed]: Test suite verified successfully.\n");

            // Stage 7: Produce reviewable result with diff for WAITING_FOR_REVIEW
            checkCancelled();
            String diff = extractWorkspaceDiff();
            logBuilder.append("[Worker ").append(context.getWorkerId()).append("] Extracted workspace diff (")
                    .append(diff.length()).append(" chars)\n");

            CodeReview review = null;
            int revCount = 1;
            if (codeReviewRepository != null) {
                String projectId = context.getProjectId() != null ? context.getProjectId() : "default-project";
                Optional<CodeReview> existingReviewOpt = codeReviewRepository.findByTaskId(task.getId());
                String explanation = "Implemented proposed changes: " + task.getTitle() + "\n" + plan.goal();

                if (existingReviewOpt.isPresent()) {
                    review = existingReviewOpt.get();
                    review.incrementRevisionCount();
                    revCount = review.getRevisionCount();
                    review.updateEffectiveDiff(diff);
                    review.updateBuildAndTestStatus("SUCCESS", "Automated build and tests passed successfully");
                    review.setAiExplanation(explanation);
                    codeReviewRepository.save(review);
                } else {
                    review = new CodeReview(
                            projectId,
                            task.getId(),
                            context.getWorkspace() != null ? context.getWorkspace().getBranch() : "main",
                            diff,
                            "SUCCESS",
                            "Automated build and tests passed successfully"
                    );
                    review.setAiExplanation(explanation);
                    review.setRevisionCount(1);
                    codeReviewRepository.save(review);
                }

                eu.kalafatic.tryst.domain.review.ReviewRevision rev = new eu.kalafatic.tryst.domain.review.ReviewRevision(
                        UUID.randomUUID().toString(),
                        review.getId(),
                        revCount,
                        diff,
                        explanation,
                        "SUCCESS",
                        "Automated build and tests passed successfully",
                        java.time.Instant.now()
                );
                codeReviewRepository.saveRevision(rev);
                task.setReviewId(review.getId());
            }

            if (context.getWorkspace() != null) {
                context.getWorkspace().markWaitingForReview();
            }

            recordRecoveryCheckpoint(task, TaskStatus.WAITING_FOR_REVIEW,
                    "{\"reviewId\":\"" + (review != null ? review.getId() : "") + "\",\"revision\":" + revCount + "}");

            lifecycleManager.transition(
                    task,
                    TaskStatus.WAITING_FOR_REVIEW,
                    "Code changes complete; awaiting user review in Code Review Center",
                    review != null ? ("Review ID: " + review.getId()) : null
            );

            if (notificationService != null && context.getUserId() != null) {
                if (task.getReworkCount() > 0) {
                    notificationService.notifyUser(
                            context.getUserId(),
                            eu.kalafatic.tryst.domain.model.NotificationType.REWORK_COMPLETED.name(),
                            "Rework Complete (Rev " + revCount + "): " + task.getTitle(),
                            "Task " + task.getId() + " rework complete. Revision " + revCount + " ready for review.",
                            "review",
                            review != null ? review.getId() : task.getId(),
                            context.getUserId() + ":REWORK_COMPLETED:" + task.getId() + ":" + revCount
                    );
                }

                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.REVIEW_READY.name(),
                        "Review Ready: " + task.getTitle(),
                        "Task " + task.getId() + " changes are ready for human code review.",
                        "review",
                        review != null ? review.getId() : task.getId(),
                        context.getUserId() + ":REVIEW_READY:" + task.getId() + ":" + revCount
                );

                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.APPROVAL_REQUIRED.name(),
                        "Approval Required: " + task.getTitle(),
                        "Code review " + (review != null ? review.getId() : task.getId()) + " requires reviewer approval before commit.",
                        "review",
                        review != null ? review.getId() : task.getId(),
                        context.getUserId() + ":APPROVAL_REQUIRED:" + (review != null ? review.getId() : task.getId()) + ":" + revCount
                );
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureClass = AiFailureClassification.CANCELLATION;
            lifecycleManager.transition(task, TaskStatus.CANCELLED, "Worker thread interrupted", e.getMessage());
            logBuilder.append("\n[Execution Interrupted/Cancelled]\n");
        } catch (TaskCancelledException e) {
            failureClass = AiFailureClassification.CANCELLATION;
            lifecycleManager.transition(task, TaskStatus.CANCELLED, "Execution cancelled by user", e.getMessage());
            logBuilder.append("\n[Execution Cancelled by User]\n");
        } catch (Exception e) {
            TrystLog.error(TaskWorker.class, "Task " + task.getId() + " failed: " + e.getMessage(), e);
            if (failureClass == AiFailureClassification.NONE) {
                failureClass = AiFailureClassification.UNCLASSIFIED_ERROR;
            }
            if (notificationService != null && context.getUserId() != null) {
                notificationService.notifyUser(
                        context.getUserId(),
                        eu.kalafatic.tryst.domain.model.NotificationType.TASK_FAILED.name(),
                        "Task Failed: " + task.getTitle(),
                        "Task " + task.getId() + " failed: " + e.getMessage(),
                        "task",
                        task.getId(),
                        context.getUserId() + ":TASK_FAILED:" + task.getId() + ":" + task.getReworkCount()
                );
            }
            lifecycleManager.transition(task, TaskStatus.FAILED, "Execution error: " + e.getMessage(),
                    "Classification: " + failureClass + "\n" + e.toString());
            logBuilder.append("\n[Execution Failure - ").append(failureClass).append("]: ").append(e.getMessage()).append("\n");
        }

        long duration = System.currentTimeMillis() - startTime;
        int exitCode = (task.getStatus() == TaskStatus.WAITING_FOR_REVIEW || task.getStatus() == TaskStatus.COMPLETED) ? 0 : 1;
        ExecutionResult result = new ExecutionResult(task.getId(), exitCode, logBuilder.toString(),
                failureClass != AiFailureClassification.NONE ? failureClass.name() : "", duration);

        if (executionResultRepository != null) {
            executionResultRepository.save(result);
            task.setExecutionResultId(result.getId());
        }

        return result;
    }

    private void checkCancelled() throws TaskCancelledException {
        if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new TaskCancelledException("Task execution cancelled");
        }
    }

    private void performRepositoryCloningStage(Task task, StringBuilder log) {
        Workspace ws = context.getWorkspace();
        if (ws == null) {
            log.append("[Cloning]: No workspace assigned to task; skipping git clone.\n");
            return;
        }

        String repoUrl = ws.getRepositoryUrl();
        if (repoUrl != null && !repoUrl.isBlank() && gitHubService != null) {
            log.append("[Cloning]: Synchronizing remote repository ").append(repoUrl)
               .append(" (branch: ").append(ws.getBranch()).append(") into sandbox ").append(ws.getRootPath()).append("\n");
            try {
                gitHubService.cloneOrFetchRepository(context.getUserId(), repoUrl, ws.getBranch(), ws.getRootPath());
                log.append("[Cloning]: Sandbox cloned/fetched successfully.\n");
            } catch (Exception e) {
                log.append("[Cloning Error]: ").append(e.getMessage()).append("\n");
                throw new IllegalStateException("Failed to clone/fetch GitHub repository: " + e.getMessage(), e);
            }
        } else {
            log.append("[Cloning]: Local workspace directory verified at ").append(ws.getRootPath()).append("\n");
        }
    }

    private void executeAgentImplementationLoop(
            Task task,
            TaskAnalyzer.AnalysisResult analysis,
            ImplementationPlanner.ExecutionPlan plan,
            StringBuilder log
    ) throws Exception {
        if (aiProvider == null) {
            log.append("No AI provider configured; skipping simulated prompt loop.\n");
            return;
        }

        List<ConversationMessage> history = (messageRepository != null && task.getSessionId() != null)
                ? messageRepository.findBySessionId(task.getSessionId())
                : List.of();

        Workspace ws = context.getWorkspace();
        Project proj = context.getProject();

        String toolHelp = toolRegistry != null ? toolRegistry.generateToolHelp() : "";
        String instructions = promptContextBuilder.buildSystemInstructions(ws, proj, toolHelp);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(task.getTitle()).append("\n").append(task.getDescription() != null ? task.getDescription() : "");

        if (codeReviewRepository != null && task.getReworkCount() > 0) {
            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByTaskId(task.getId());
            List<eu.kalafatic.tryst.domain.review.ReviewComment> reworkComments = comments.stream()
                    .filter(c -> !c.isResolved() && c.isIncludeInRework())
                    .toList();

            if (!reworkComments.isEmpty()) {
                promptBuilder.append("\n\n=== MANDATORY ITERATIVE REVIEW REWORK INSTRUCTIONS ===\n");
                promptBuilder.append("This is rework iteration #").append(task.getReworkCount()).append(".\n");
                promptBuilder.append("CRITICAL EXECUTION CONSTRAINTS:\n");
                promptBuilder.append("1. Address every requested modification described below precisely.\n");
                promptBuilder.append("2. Restrict modifications strictly to the relevant scope of the comments unless additional changes are strictly justified.\n");
                promptBuilder.append("3. Preserve all existing public APIs and keep unrelated files/modifications completely intact.\n");
                promptBuilder.append("4. Do not duplicate database queries or introduce redundant operations.\n");
                promptBuilder.append("5. Ensure all project code compiles cleanly and automated tests continue to pass.\n\n");

                promptBuilder.append("ACTIVE USER REVIEW FEEDBACK:\n");
                for (int ci = 0; ci < reworkComments.size(); ci++) {
                    eu.kalafatic.tryst.domain.review.ReviewComment c = reworkComments.get(ci);
                    promptBuilder.append("[").append(ci + 1).append("] Type: ").append(c.getCommentType());
                    if (c.getFilePath() != null && !c.getFilePath().isBlank()) {
                        promptBuilder.append(" | File: ").append(c.getFilePath());
                    }
                    if (c.getCommentType() == eu.kalafatic.tryst.domain.review.ReviewComment.CommentType.LINE) {
                        promptBuilder.append(" | Lines: ").append(c.getStartLine()).append("–").append(c.getEndLine())
                                .append(" (Side: ").append(c.getDiffSide()).append(")");
                    }
                    promptBuilder.append("\n");
                    if (!c.getCodeContext().isBlank()) {
                        promptBuilder.append("   Selected Code Context:\n   ```\n   ")
                                .append(c.getCodeContext().replace("\n", "\n   "))
                                .append("\n   ```\n");
                    }
                    promptBuilder.append("   User Instructions: ").append(c.getContent()).append("\n\n");
                }
            }
        }

        String prompt = promptBuilder.toString();

        CodeAgent.AgentExecutionResult res = codeAgent.runStep(
                task,
                ws,
                instructions,
                history,
                prompt,
                context.getModelName(),
                chunk -> log.append(chunk),
                () -> {
                    try {
                        checkCancelled();
                    } catch (TaskCancelledException e) {
                        throw new java.util.concurrent.CancellationException(e.getMessage());
                    }
                }
        );

        if (!res.success()) {
            if (res.failureClassification() == AiFailureClassification.CANCELLATION) {
                throw new TaskCancelledException("Task was cancelled during AI inference");
            }
            log.append("[Agent step issue]: ").append(res.errorMessage()).append("\n");
        }
    }

    private String extractWorkspaceDiff() {
        if (toolRegistry == null || context.getWorkspace() == null) {
            return "";
        }
        Optional<Tool> diffTool = toolRegistry.getTool("git_diff");
        if (diffTool.isPresent()) {
            Tool.ToolResult res = diffTool.get().execute(Map.of(), context.getWorkspace(), permissionManager);
            if (res.success()) {
                return res.output();
            }
        }
        return "";
    }

    public static class TaskCancelledException extends Exception {
        public TaskCancelledException(String message) {
            super(message);
        }
    }

    private static class LegacyAiProviderAdapter implements AIProvider {
        private final AiInferenceService delegate;

        LegacyAiProviderAdapter(AiInferenceService delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getProviderId() {
            return "legacy-adapter";
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
