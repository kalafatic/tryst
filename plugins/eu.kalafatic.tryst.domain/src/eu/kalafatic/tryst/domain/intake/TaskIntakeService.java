package eu.kalafatic.tryst.domain.intake;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.security.SecurityContext;

import java.util.List;

/**
 * Service contract for intelligent task intake:
 * repository and branch validation, prompt analysis, missing information detection,
 * title generation, task classification, and non-destructive intake execution.
 */
public interface TaskIntakeService {

    enum RequestClassification {
        FEATURE,
        BUG_FIX,
        REFACTORING,
        TEST,
        DOCUMENTATION,
        CHORE,
        SECURITY,
        GENERAL
    }

    record TaskAttachment(
            String name,
            String type,
            long size,
            String content
    ) {
        public TaskAttachment {
            if (name == null || name.isBlank()) {
                name = "attachment.txt";
            }
            if (content == null) {
                content = "";
            }
        }
    }

    record ExecutionOptions(
            boolean notifyProgress,
            boolean notifyCompletion,
            boolean notifyIntervention,
            boolean generateCodeReview,
            boolean requireApproval,
            boolean autoExecute,
            boolean autoCommit,
            boolean autoPush
    ) {
        public static ExecutionOptions defaults() {
            return new ExecutionOptions(true, true, true, true, true, true, false, false);
        }
    }

    record TaskIntakeRequest(
            String projectId,
            String repositoryUrl,
            String branch,
            String title,
            String description,
            TaskPriority priority,
            String expectedOutcome,
            List<String> acceptanceCriteria,
            List<String> constraints,
            List<String> supplementaryInstructions,
            List<TaskAttachment> attachments,
            ExecutionOptions executionOptions
    ) {
        public TaskIntakeRequest(
                String projectId,
                String repositoryUrl,
                String branch,
                String title,
                String description,
                TaskPriority priority,
                String expectedOutcome,
                List<String> acceptanceCriteria,
                List<String> constraints,
                List<String> supplementaryInstructions
        ) {
            this(projectId, repositoryUrl, branch, title, description, priority,
                    expectedOutcome, acceptanceCriteria, constraints, supplementaryInstructions,
                    List.of(), ExecutionOptions.defaults());
        }
    }

    record TaskIntakeAnalysis(
            boolean valid,
            String suggestedTitle,
            RequestClassification classification,
            TaskPriority suggestedPriority,
            List<String> missingInformation,
            List<String> validationErrors,
            String repositoryContext,
            String complexityEstimate
    ) {}

    record TaskIntakeResult(
            Task task,
            TaskIntakeAnalysis analysis,
            List<TaskInstruction> persistedInstructions
    ) {}

    /**
     * Performs non-destructive preliminary analysis and validation of the intake request.
     * Does NOT execute or queue the task, and does NOT perform destructive disk or git operations.
     */
    TaskIntakeAnalysis analyzeAndValidate(TaskIntakeRequest request, String userId, SecurityContext context);

    /**
     * Validates and submits the intake request, persisting the original request,
     * acceptance criteria, constraints, and supplementary instructions, and enqueuing the task.
     */
    TaskIntakeResult submitIntake(TaskIntakeRequest request, String userId, SecurityContext context);

    /**
     * Appends a supplementary instruction or context note to an existing task.
     */
    TaskInstruction addSupplementaryInstruction(String taskId, String instructionText,
                                              TaskInstruction.InstructionType type,
                                              String userId, SecurityContext context);

    /**
     * Retrieves all instructions associated with a task.
     */
    List<TaskInstruction> getInstructionsForTask(String taskId, String userId, SecurityContext context);
}
