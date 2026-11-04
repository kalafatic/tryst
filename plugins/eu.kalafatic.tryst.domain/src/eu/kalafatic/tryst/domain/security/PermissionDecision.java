package eu.kalafatic.tryst.domain.security;

/**
 * Explicit authorization decision representation.
 * In any composite evaluation, DENY must always take precedence over ALLOW.
 */
public enum PermissionDecision {
    ALLOW,
    DENY;

    public boolean isAllowed() {
        return this == ALLOW;
    }

    public boolean isDenied() {
        return this == DENY;
    }

    public static PermissionDecision combine(PermissionDecision... decisions) {
        if (decisions == null || decisions.length == 0) {
            return DENY;
        }
        for (PermissionDecision decision : decisions) {
            if (decision == null || decision == DENY) {
                return DENY;
            }
        }
        return ALLOW;
    }

    public record DecisionResult(PermissionDecision decision, String reason) {
        public static DecisionResult allow(String reason) {
            return new DecisionResult(ALLOW, reason != null ? reason : "Access granted");
        }

        public static DecisionResult deny(String reason) {
            return new DecisionResult(DENY, reason != null ? reason : "Access denied");
        }

        public boolean isAllowed() {
            return decision == ALLOW;
        }

        public boolean isDenied() {
            return decision == DENY;
        }
    }
}
