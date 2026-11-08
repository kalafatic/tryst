package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.workflow.WorkflowDefinition;
import eu.kalafatic.tryst.domain.workflow.WorkflowDefinitionRepository;
import eu.kalafatic.tryst.domain.workflow.WorkflowEdge;
import eu.kalafatic.tryst.domain.workflow.WorkflowExecution;
import eu.kalafatic.tryst.domain.workflow.WorkflowExecutionRepository;
import eu.kalafatic.tryst.domain.workflow.WorkflowNode;
import eu.kalafatic.tryst.domain.workflow.WorkflowValidator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates multi-node workflow execution:
 * - Topological dependency resolution
 * - Progresses workflow execution through sequential and parallel phases
 * - Pauses at approval checkpoints
 * - Records execution state
 */
public class WorkflowEngine {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final TaskOrchestrator taskOrchestrator;

    public WorkflowEngine(WorkflowDefinitionRepository definitionRepository,
                          WorkflowExecutionRepository executionRepository,
                          TaskOrchestrator taskOrchestrator) {
        this.definitionRepository = Objects.requireNonNull(definitionRepository, "definitionRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository must not be null");
        this.taskOrchestrator = Objects.requireNonNull(taskOrchestrator, "taskOrchestrator must not be null");
    }

    /**
     * Starts execution of a workflow definition.
     */
    public WorkflowExecution startWorkflow(String definitionId, String projectId, String triggeredBy) {
        WorkflowDefinition def = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow definition not found: " + definitionId));

        WorkflowValidator.ValidationResult validation = WorkflowValidator.validate(def);
        if (!validation.valid()) {
            throw new IllegalStateException("Cannot start invalid workflow: " + String.join(", ", validation.errors()));
        }

        WorkflowExecution execution = new WorkflowExecution(definitionId, projectId, triggeredBy);
        execution.setStatus(WorkflowExecution.Status.RUNNING);
        executionRepository.save(execution);

        TrystLog.info(WorkflowEngine.class, "Started workflow execution " + execution.getId() + " for definition " + def.getName());
        return execution;
    }

    /**
     * Pauses a running workflow execution.
     */
    public void pauseWorkflow(String executionId) {
        executionRepository.findById(executionId).ifPresent(exec -> {
            if (exec.getStatus() == WorkflowExecution.Status.RUNNING) {
                exec.setStatus(WorkflowExecution.Status.PAUSED);
                executionRepository.save(exec);
                TrystLog.info(WorkflowEngine.class, "Workflow " + executionId + " paused");
            }
        });
    }

    /**
     * Resumes a paused workflow execution.
     */
    public void resumeWorkflow(String executionId) {
        executionRepository.findById(executionId).ifPresent(exec -> {
            if (exec.getStatus() == WorkflowExecution.Status.PAUSED) {
                exec.setStatus(WorkflowExecution.Status.RUNNING);
                executionRepository.save(exec);
                TrystLog.info(WorkflowEngine.class, "Workflow " + executionId + " resumed");
            }
        });
    }

    /**
     * Cancels a workflow execution.
     */
    public void cancelWorkflow(String executionId) {
        executionRepository.findById(executionId).ifPresent(exec -> {
            if (!exec.getStatus().isTerminal()) {
                exec.setStatus(WorkflowExecution.Status.CANCELLED);
                executionRepository.save(exec);
                TrystLog.info(WorkflowEngine.class, "Workflow " + executionId + " cancelled");
            }
        });
    }
}
