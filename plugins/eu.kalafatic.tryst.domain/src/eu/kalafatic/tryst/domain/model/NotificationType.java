package eu.kalafatic.tryst.domain.model;

/**
 * Standard notification event types required across the asynchronous task lifecycle.
 */
public enum NotificationType {
    TASK_ACCEPTED,
    REPOSITORY_CLONED,
    AI_ANALYSIS_COMPLETED,
    IMPLEMENTATION_STARTED,
    BUILD_COMPLETED,
    TESTS_FAILED,
    REVIEW_READY,
    REVIEW_COMMENTS_RECEIVED,
    REWORK_COMPLETED,
    APPROVAL_REQUIRED,
    COMMIT_COMPLETED,
    TASK_FAILED;

    public static NotificationType fromString(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return NotificationType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
