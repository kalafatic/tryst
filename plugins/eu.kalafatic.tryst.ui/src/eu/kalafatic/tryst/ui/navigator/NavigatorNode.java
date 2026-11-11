package eu.kalafatic.tryst.ui.navigator;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Universal hierarchical node in the TRYST Navigator tree.
 */
public class NavigatorNode {

    public enum NodeType {
        ROOT_CATEGORY,
        SERVER,
        SESSION,
        TASK,
        TASK_QUEUE,
        TASK_RUNNING,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_CANCELLED,
        PROJECT,
        GIT_REPO,
        GIT_BRANCH,
        WORKSPACE,
        REVIEW_SESSION,
        USER,
        BUILD_RESULT,
        AI_EXECUTION,
        MCP_RESOURCE,
        TEIP_INTEGRATION,
        DATABASE,
        TABLE,
        SQL_SCRIPT,
        SYSTEM_ITEM
    }

    private final String id;
    private final String name;
    private final NodeType type;
    private final Object domainObject;
    private final NavigatorNode parent;
    private List<NavigatorNode> children;

    public NavigatorNode(String id, String name, NodeType type, Object domainObject, NavigatorNode parent) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.domainObject = domainObject;
        this.parent = parent;
        this.children = Collections.emptyList();
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

    public Object getDomainObject() {
        return domainObject;
    }

    public NavigatorNode getParent() {
        return parent;
    }

    public List<NavigatorNode> getChildren() {
        return children;
    }

    public void setChildren(List<NavigatorNode> children) {
        this.children = children != null ? children : Collections.emptyList();
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NavigatorNode that = (NavigatorNode) o;
        return Objects.equals(id, that.id) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        return name;
    }
}
