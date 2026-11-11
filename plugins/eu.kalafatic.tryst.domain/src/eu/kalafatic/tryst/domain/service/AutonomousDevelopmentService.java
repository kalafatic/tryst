package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Autonomous development service that drives the full engineering loop:
 * Request -> Resolution -> Plan -> Tool execution -> Workspace file edits -> Build/Tests -> Code Review -> WAITING_FOR_REVIEW.
 * Never commits automatically; creates a durable review package awaiting explicit human-in-the-loop approval.
 */
public class AutonomousDevelopmentService {

    private final SessionTaskOrchestrator sessionTaskOrchestrator;
    private final WorkspaceService workspaceService;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ExecutionResultRepository executionResultRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final CodeReviewService codeReviewService;
    private final EventBus eventBus;

    public record AutonomousExecutionSummary(
            boolean successful,
            int iterations,
            CodeReviewService.ReviewReport reviewReport,
            ExecutionResult executionResult,
            CodeReview codeReview
    ) {}

    public AutonomousDevelopmentService(
            SessionTaskOrchestrator sessionTaskOrchestrator,
            WorkspaceService workspaceService,
            TaskRepository taskRepository,
            ExecutionResultRepository executionResultRepository,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            CodeReviewService codeReviewService,
            EventBus eventBus
    ) {
        this(sessionTaskOrchestrator, workspaceService, taskRepository, null, executionResultRepository, null,
                toolRegistry, permissionManager, codeReviewService, eventBus);
    }

    public AutonomousDevelopmentService(
            SessionTaskOrchestrator sessionTaskOrchestrator,
            WorkspaceService workspaceService,
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ExecutionResultRepository executionResultRepository,
            CodeReviewRepository codeReviewRepository,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            CodeReviewService codeReviewService,
            EventBus eventBus
    ) {
        this.sessionTaskOrchestrator = Objects.requireNonNull(sessionTaskOrchestrator, "sessionTaskOrchestrator must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.projectRepository = projectRepository;
        this.executionResultRepository = Objects.requireNonNull(executionResultRepository, "executionResultRepository must not be null");
        this.codeReviewRepository = codeReviewRepository;
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
        this.codeReviewService = Objects.requireNonNull(codeReviewService, "codeReviewService must not be null");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
    }

    public CompletableFuture<AutonomousExecutionSummary> executeAutonomousDevelopment(
            AgentTask task,
            AiInferenceService aiClient,
            String modelName,
            int maxIterations,
            Consumer<String> streamCallback
    ) {
        return sessionTaskOrchestrator.executeTaskWithAgent(task, aiClient, modelName, maxIterations, streamCallback)
                .thenApply(execResult -> {
                    // Resolve project-scoped workspace
                    Workspace ws = task.getProjectId() != null
                            ? workspaceService.getWorkspaceForProject(task.getProjectId())
                            : workspaceService.getCurrentWorkspace();

                    String diff = "";
                    Optional<Tool> gitTool = toolRegistry.getTool("git_diff");
                    if (gitTool.isPresent() && ws != null) {
                        Tool.ToolResult diffRes = gitTool.get().execute(Map.of(), ws, permissionManager);
                        if (diffRes.success()) {
                            diff = diffRes.output();
                        }
                    }

                    boolean buildSuccess = execResult.getExitCode() == 0;
                    CodeReviewService.ReviewReport reviewReport = codeReviewService.reviewChanges(diff, execResult.getOutput(), buildSuccess);

                    // Create durable CodeReview record
                    CodeReview codeReview = null;
                    if (codeReviewRepository != null) {
                        String projId = task.getProjectId() != null ? task.getProjectId() : "default-project";
                        codeReview = new CodeReview(
                                projId,
                                task.getId(),
                                "master",
                                diff,
                                buildSuccess ? "SUCCESS" : "FAILURE",
                                reviewReport.summary()
                        );
                        codeReviewRepository.save(codeReview);

                        // Transition task to WAITING_FOR_REVIEW instead of directly completing
                        task.waitForReview(codeReview.getId());
                        taskRepository.save(task);

                        eventBus.publish(new ApplicationEvents.TaskStateChangedEvent(
                                task.getId(), "RUNNING", "WAITING_FOR_REVIEW", "Changes ready for user inspection in Code Review Center"
                        ));
                    }

                    boolean overallSuccess = buildSuccess && reviewReport.approved();
                    return new AutonomousExecutionSummary(overallSuccess, 1, reviewReport, execResult, codeReview);
                });
    }
}
