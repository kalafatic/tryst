package eu.kalafatic.tryst.domain.ai;

/**
 * Categorization of autonomous development failures.
 */
public enum AiFailureClassification {
    NONE,
    TIMEOUT,
    CANCELLATION,
    CONTEXT_LIMIT_EXCEEDED,
    MODEL_UNAVAILABLE,
    TOOL_EXECUTION_ERROR,
    BUILD_FAILURE,
    TEST_FAILURE,
    SECURITY_VIOLATION,
    WORKSPACE_VIOLATION,
    UNCLASSIFIED_ERROR
}
