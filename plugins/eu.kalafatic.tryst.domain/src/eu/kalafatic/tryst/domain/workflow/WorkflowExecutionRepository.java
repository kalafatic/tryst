package eu.kalafatic.tryst.domain.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and querying workflow execution instances.
 */
public interface WorkflowExecutionRepository {

    void save(WorkflowExecution execution);

    Optional<WorkflowExecution> findById(String id);

    List<WorkflowExecution> findByWorkflowDefinitionId(String workflowDefinitionId);

    List<WorkflowExecution> findByProjectId(String projectId);

    List<WorkflowExecution> findActiveExecutions();

    List<WorkflowExecution> findAll();

    boolean delete(String id);
}
