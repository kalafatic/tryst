package eu.kalafatic.tryst.domain.workflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single node in a visual workflow definition.
 */
public class WorkflowNode {

    private final String id;
    private final String name;
    private final NodeType type;
    private final String description;
    private final String prompt;
    private final String command;
    private final String priority;
    private final ExecutionMode executionMode;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final String branch;
    private final double x;
    private final double y;
    private final Map<String, Object> properties;

    public WorkflowNode(String id, String name, NodeType type, String description,
                        String prompt, String command, String priority, ExecutionMode executionMode,
                        int timeoutSeconds, int maxRetries, String branch, double x, double y,
                        Map<String, Object> properties) {
        this.id = Objects.requireNonNull(id, "node id must not be null");
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : id;
        this.type = type != null ? type : NodeType.AI_CODING;
        this.description = description != null ? description.trim() : "";
        this.prompt = prompt != null ? prompt.trim() : "";
        this.command = command != null ? command.trim() : "";
        this.priority = priority != null ? priority.trim() : "NORMAL";
        this.executionMode = executionMode != null ? executionMode : ExecutionMode.ASYNC;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 600;
        this.maxRetries = Math.max(0, maxRetries);
        this.branch = branch != null ? branch.trim() : "";
        this.x = x;
        this.y = y;
        this.properties = properties != null ? Collections.unmodifiableMap(new HashMap<>(properties)) : Collections.emptyMap();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public NodeType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getCommand() {
        return command;
    }

    public String getPriority() {
        return priority;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getBranch() {
        return branch;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkflowNode that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WorkflowNode{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", type=" + type +
                '}';
    }
}
