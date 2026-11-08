package eu.kalafatic.tryst.domain.workflow;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an execution instance of a workflow.
 */
public class WorkflowExecution {

    public enum Status {
        PENDING,
        RUNNING,
        PAUSED,
        WAITING_FOR_APPROVAL,
        COMPLETED,
        FAILED,
        CANCELLED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    private final String id;
    private final String workflowDefinitionId;
    private final String projectId;
    private final String triggeredBy;
    private Status status;
    private String currentNodeId;
    private String executionStateJson;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    public WorkflowExecution(String workflowDefinitionId, String projectId, String triggeredBy) {
        this(UUID.randomUUID().toString(), workflowDefinitionId, projectId, triggeredBy,
                Status.PENDING, null, "{}", Instant.now(), null, null);
    }

    public WorkflowExecution(String id, String workflowDefinitionId, String projectId,
                             String triggeredBy, Status status, String currentNodeId,
                             String executionStateJson, Instant createdAt, Instant startedAt,
                             Instant completedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.workflowDefinitionId = Objects.requireNonNull(workflowDefinitionId, "workflowDefinitionId must not be null");
        this.projectId = projectId != null && !projectId.trim().isEmpty() ? projectId.trim() : null;
        this.triggeredBy = triggeredBy != null && !triggeredBy.trim().isEmpty() ? triggeredBy.trim() : "system";
        this.status = status != null ? status : Status.PENDING;
        this.currentNodeId = currentNodeId;
        this.executionStateJson = executionStateJson != null ? executionStateJson : "{}";
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public String getId() {
        return id;
    }

    public String getWorkflowDefinitionId() {
        return workflowDefinitionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized void setStatus(Status status) {
        if (this.status.isTerminal() && this.status != status) {
            throw new TrystDomainException("Cannot change status from terminal state: " + this.status);
        }
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (status == Status.RUNNING && this.startedAt == null) {
            this.startedAt = Instant.now();
        } else if (status.isTerminal() && this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public synchronized String getCurrentNodeId() {
        return currentNodeId;
    }

    public synchronized void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public synchronized String getExecutionStateJson() {
        return executionStateJson;
    }

    public synchronized void setExecutionStateJson(String executionStateJson) {
        this.executionStateJson = executionStateJson != null ? executionStateJson : "{}";
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getStartedAt() {
        return startedAt;
    }

    public synchronized Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowExecution that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
