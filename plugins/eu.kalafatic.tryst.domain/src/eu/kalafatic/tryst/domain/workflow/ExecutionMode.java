package eu.kalafatic.tryst.domain.workflow;

/**
 * Task execution modes supported by TRYST task and workflow engine.
 */
public enum ExecutionMode {
    IMMEDIATE,
    ASYNC,
    SCHEDULED,
    RECURRING,
    DEPENDENT,
    CONDITIONAL,
    MANUAL_APPROVAL
}
