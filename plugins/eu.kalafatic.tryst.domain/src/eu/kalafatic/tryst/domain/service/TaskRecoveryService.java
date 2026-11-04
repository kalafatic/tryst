package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.InterruptedOperationClassification;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskRecoveryCheckpoint;
import eu.kalafatic.tryst.domain.model.TaskRecoveryDecision;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles detection, granular classification, and deterministic recovery of interrupted tasks.
 * Evaluates whether an interrupted task is SAFE_TO_RESUME, requires RECONCILE_AND_RETRY,
 * or REQUIRES_USER_INTERVENTION to avoid naive replaying of non-idempotent operations.
 * Preserves tasks awaiting human-in-the-loop review (WAITING_FOR_REVIEW) and APPROVED tasks.
 */
public class TaskRecoveryService {

    public record RecoveryAssessment(
            Task task,
            TaskStatus interruptedStage,
            InterruptedOperationClassification classification,
            TaskRecoveryDecision decision,
            String explanation,
            Optional<TaskRecoveryCheckpoint> latestCheckpoint
    ) {}

    private final TaskRepository taskRepository;
    private final TaskLifecycleManager lifecycleManager;
    private final TaskRecoveryCheckpointRepository checkpointRepository;

    public TaskRecoveryService(TaskRepository taskRepository, TaskLifecycleManager lifecycleManager) {
        this(taskRepository, lifecycleManager, null);
    }

    public TaskRecoveryService(TaskRepository taskRepository, TaskLifecycleManager lifecycleManager,
                               TaskRecoveryCheckpointRepository checkpointRepository) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager must not be null");
        this.checkpointRepository = checkpointRepository;
    }

    /**
     * Records a durable recovery checkpoint for a task.
     */
    public void recordCheckpoint(String taskId, TaskStatus stage, String checkpointDataJson) {
        if (checkpointRepository != null) {
            TaskRecoveryCheckpoint cp = TaskRecoveryCheckpoint.of(taskId, stage, checkpointDataJson);
            checkpointRepository.save(cp);
            TrystLog.info(TaskRecoveryService.class, "Recorded checkpoint for task " + taskId + " in stage " + stage);
        }
    }

    public Optional<TaskRecoveryCheckpoint> getLatestCheckpoint(String taskId) {
        return checkpointRepository != null ? checkpointRepository.findLatestByTaskId(taskId) : Optional.empty();
    }

    /**
     * Granularly classifies an interrupted stage into an InterruptedOperationClassification.
     */
    public InterruptedOperationClassification classifyInterruption(TaskStatus stage) {
        if (stage == null) {
            return InterruptedOperationClassification.UNCLASSIFIED;
        }
        return switch (stage) {
            case CLONING -> InterruptedOperationClassification.INTERRUPTED_GIT_OPERATION;
            case COMMITTING -> InterruptedOperationClassification.INTERRUPTED_GIT_OPERATION;
            case ANALYZING, PLANNING, IMPLEMENTING, REWORKING -> InterruptedOperationClassification.INTERRUPTED_AI_REQUEST;
            case BUILDING, TESTING -> InterruptedOperationClassification.INTERRUPTED_BUILD;
            default -> InterruptedOperationClassification.OS_TERMINATION;
        };
    }

    /**
     * Determines whether an operation can safely resume, must be reconciled, or requires user intervention.
     */
    public TaskRecoveryDecision determineRecoveryDecision(TaskStatus stage, Optional<TaskRecoveryCheckpoint> checkpoint) {
        if (stage == null) {
            return TaskRecoveryDecision.RECONCILE_AND_RETRY;
        }

        switch (stage) {
            case QUEUED, READY, CREATED:
                // Strictly safe to resume / re-queue as no side effects occurred yet
                return TaskRecoveryDecision.SAFE_TO_RESUME;

            case CLONING:
                // Git clone can be safely cleaned and retried idempotently
                return TaskRecoveryDecision.RECONCILE_AND_RETRY;

            case ANALYZING:
            case PLANNING:
                // Read-only analysis/planning stages can safely resume or retry idempotently
                return TaskRecoveryDecision.SAFE_TO_RESUME;

            case IMPLEMENTING:
            case REWORKING:
                // Code modifications occurred in workspace sandbox. Reconcile sandbox and retry
                return TaskRecoveryDecision.RECONCILE_AND_RETRY;

            case BUILDING:
            case TESTING:
                // Build/test processes were interrupted; workspace requires build artifact cleanup and retry
                return TaskRecoveryDecision.RECONCILE_AND_RETRY;

            case COMMITTING:
                // Committing involves VCS state mutation; requires user intervention to verify if commit landed
                return TaskRecoveryDecision.REQUIRES_USER_INTERVENTION;

            default:
                return TaskRecoveryDecision.REQUIRES_USER_INTERVENTION;
        }
    }

    /**
     * Assesses an interrupted task without mutating state.
     */
    public RecoveryAssessment assessTask(Task task) {
        Objects.requireNonNull(task, "task must not be null");
        TaskStatus stage = task.getStatus();
        Optional<TaskRecoveryCheckpoint> cp = getLatestCheckpoint(task.getId());
        InterruptedOperationClassification classification = classifyInterruption(stage);
        TaskRecoveryDecision decision = determineRecoveryDecision(stage, cp);

        String explanation = switch (decision) {
            case SAFE_TO_RESUME -> "Task interrupted in idempotent or pre-execution stage (" + stage + "). Safe to resume directly.";
            case RECONCILE_AND_RETRY -> "Task interrupted during active execution (" + stage + "). Workspace sandbox must be reconciled before retry.";
            case REQUIRES_USER_INTERVENTION -> "Task interrupted during non-idempotent operation (" + stage + "). Requires explicit operator review.";
        };

        return new RecoveryAssessment(task, stage, classification, decision, explanation, cp);
    }

    /**
     * Reconciles all active interrupted tasks upon application startup.
     * Preserves tasks in WAITING_FOR_REVIEW and APPROVED.
     *
     * @return List of tasks reconciled to RECOVERY_REQUIRED
     */
    public List<Task> reconcileInterruptedTasks() {
        List<Task> allTasks = taskRepository.findAllTasks();
        List<Task> recoveredTasks = new ArrayList<>();

        for (Task task : allTasks) {
            TaskStatus status = task.getStatus();
            if (status.isActive() || status == TaskStatus.QUEUED) {
                RecoveryAssessment assessment = assessTask(task);

                TrystLog.warn(TaskRecoveryService.class, String.format(
                        "Startup reconciliation: Interrupted task %s in stage %s [Classification: %s, Decision: %s] -> RECOVERY_REQUIRED",
                        task.getId(), status, assessment.classification(), assessment.decision()
                ));

                // Record checkpoint of interrupted state if not already present
                recordCheckpoint(task.getId(), status, "{\"interruptedAt\":\"" + Instant.now() + "\",\"decision\":\"" + assessment.decision() + "\"}");

                lifecycleManager.transition(
                        task,
                        TaskStatus.RECOVERY_REQUIRED,
                        "Server restart interrupted execution in stage: " + status + " (" + assessment.classification() + ")",
                        assessment.explanation()
                );
                recoveredTasks.add(task);
            }
        }

        return recoveredTasks;
    }

    /**
     * Executes safe retry for a recovered task based on its recovery decision.
     */
    public boolean retryRecoveredTask(String taskId) {
        return taskRepository.findTaskById(taskId).map(task -> {
            if (task.getStatus() == TaskStatus.RECOVERY_REQUIRED) {
                RecoveryAssessment assessment = assessTask(task);
                if (assessment.decision() == TaskRecoveryDecision.REQUIRES_USER_INTERVENTION) {
                    TrystLog.warn(TaskRecoveryService.class, "Cannot automatically retry task " + taskId + ": requires user intervention");
                    return false;
                }

                lifecycleManager.transition(
                        task,
                        TaskStatus.QUEUED,
                        "Task queued for retry following recovery reconciliation (" + assessment.decision() + ")",
                        assessment.explanation()
                );
                return true;
            }
            return false;
        }).orElse(false);
    }

    /**
     * Force retry of a task requiring user intervention after manual operator confirmation.
     */
    public boolean forceRetryRecoveredTask(String taskId, String operatorId) {
        return taskRepository.findTaskById(taskId).map(task -> {
            if (task.getStatus() == TaskStatus.RECOVERY_REQUIRED) {
                lifecycleManager.transition(
                        task,
                        TaskStatus.QUEUED,
                        "Task forced into retry queue by operator " + operatorId,
                        "Operator confirmed resolution of interrupted side effects."
                );
                return true;
            }
            return false;
        }).orElse(false);
    }
}
