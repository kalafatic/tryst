package eu.kalafatic.tryst.domain.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persisting and querying workflow definitions.
 */
public interface WorkflowDefinitionRepository {

    void save(WorkflowDefinition definition);

    Optional<WorkflowDefinition> findById(String id);

    List<WorkflowDefinition> findByProjectId(String projectId);

    List<WorkflowDefinition> findByOwnerId(String ownerId);

    List<WorkflowDefinition> findAll();

    List<WorkflowDefinition> findTemplates();

    boolean delete(String id);
}
