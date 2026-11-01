package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiContextManager;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Autonomous development service that drives the full engineering loop:
 * Request -> Plan -> Tool execution -> Workspace file edits -> Build/Tests -> Code Review -> Completion.
 */
public class AutonomousDevelopmentService {

    private final SessionTaskOrchestrator sessionTaskOrchestrator;
    private final WorkspaceService workspaceService;
    private final TaskRepository taskRepository;
    private final ExecutionResultRepository executionResultRepository;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final CodeReviewService codeReviewService;
    private final EventBus eventBus;

    public record AutonomousExecutionSummary(
            boolean successful,
            int iterations,
            CodeReviewService.ReviewReport reviewReport,
            ExecutionResult executionResult
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
        this.sessionTaskOrchestrator = Objects.requireNonNull(sessionTaskOrchestrator, "sessionTaskOrchestrator must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.executionResultRepository = Objects.requireNonNull(executionResultRepository, "executionResultRepository must not be null");
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
                    // Check Git diff or tool changes
                    Workspace ws = workspaceService.getCurrentWorkspace();
                    String diff = "";
                    Optional<Tool> gitTool = toolRegistry.getTool("git_diff");
                    if (gitTool.isPresent() && ws != null) {
                        Tool.ToolResult diffRes = gitTool.get().execute(Map.of(), ws, permissionManager);
                        if (diffRes.success()) {
                            diff = diffRes.output();
                        }
                    }

                    boolean buildSuccess = execResult.getExitCode() == 0;
                    CodeReviewService.ReviewReport review = codeReviewService.reviewChanges(diff, execResult.getOutput(), buildSuccess);

                    boolean overallSuccess = buildSuccess && review.approved();
                    return new AutonomousExecutionSummary(overallSuccess, 1, review, execResult);
                });
    }
}
