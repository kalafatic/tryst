package eu.kalafatic.tryst.domain.workflow;

import java.util.Objects;

/**
 * Represents a directed connection between two nodes in a workflow graph.
 */
public class WorkflowEdge {

    private final String id;
    private final String sourceNodeId;
    private final String targetNodeId;
    private final String sourcePort; // e.g., "output", "success", "failure"
    private final String targetPort; // e.g., "input"
    private final String conditionExpression; // optional expression for conditional branches

    public WorkflowEdge(String id, String sourceNodeId, String targetNodeId,
                        String sourcePort, String targetPort, String conditionExpression) {
        this.id = Objects.requireNonNull(id, "edge id must not be null");
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId must not be null");
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId must not be null");
        this.sourcePort = sourcePort != null && !sourcePort.trim().isEmpty() ? sourcePort.trim() : "default";
        this.targetPort = targetPort != null && !targetPort.trim().isEmpty() ? targetPort.trim() : "input";
        this.conditionExpression = conditionExpression != null ? conditionExpression.trim() : "";
    }

    public String getId() {
        return id;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public String getSourcePort() {
        return sourcePort;
    }

    public String getTargetPort() {
        return targetPort;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowEdge that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkflowEdge{" +
                "id='" + id + '\'' +
                ", " + sourceNodeId + " [" + sourcePort + "] -> " + targetNodeId + " [" + targetPort + "]" +
                '}';
    }
}
