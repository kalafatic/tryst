package eu.kalafatic.tryst.domain.workflow;

/**
 * Conceptual node types supported by the TRYST visual workflow designer
 * and workflow execution engine.
 */
public enum NodeType {
    WORKFLOW_START,
    AI_ANALYSIS,
    AI_CODING,
    CODE_REVIEW,
    BUILD,
    TEST,
    DOCUMENTATION,
    REPOSITORY_SYNC,
    USER_APPROVAL,
    CONDITION,
    DELAY,
    PARALLEL_GROUP,
    WORKFLOW_END;

    public boolean isExecutableTask() {
        return this == AI_ANALYSIS || this == AI_CODING || this == CODE_REVIEW
                || this == BUILD || this == TEST || this == DOCUMENTATION
                || this == REPOSITORY_SYNC;
    }

    public boolean isControlFlow() {
        return this == WORKFLOW_START || this == WORKFLOW_END || this == USER_APPROVAL
                || this == CONDITION || this == DELAY || this == PARALLEL_GROUP;
    }
}
