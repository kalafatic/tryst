package eu.kalafatic.tryst.domain.model;

/**
 * Deterministic recovery decision evaluated for interrupted tasks.
 * Prevents naive replay of non-idempotent operations.
 */
public enum TaskRecoveryDecision {
    /**
     * Operation is strictly idempotent or can safely resume from its recorded checkpoint.
     */
    SAFE_TO_RESUME,

    /**
     * Workspace or Git branch state must be cleaned or reconciled before safely re-queuing the task.
     */
    RECONCILE_AND_RETRY,

    /**
     * Non-idempotent or ambiguous side effects occurred (e.g. partial manual uncommitted edits,
     * commit conflicts, or unresolvable workspace lock); human intervention is required.
     */
    REQUIRES_USER_INTERVENTION
}
