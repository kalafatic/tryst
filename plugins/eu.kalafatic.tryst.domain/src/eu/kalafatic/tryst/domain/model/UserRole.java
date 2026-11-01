package eu.kalafatic.tryst.domain.model;

public enum UserRole {
    ADMIN,
    PROJECT_OWNER,
    DEVELOPER,
    REVIEWER,
    USER;

    public boolean canManageUsers() {
        return this == ADMIN;
    }

    public boolean canManageConfiguration() {
        return this == ADMIN;
    }

    public boolean canViewAuditLogs() {
        return this == ADMIN;
    }

    public boolean canInspectServerHealth() {
        return this == ADMIN;
    }

    public boolean canCommitReviews() {
        return this == ADMIN || this == PROJECT_OWNER || this == DEVELOPER;
    }

    public boolean canApproveReviews() {
        return this == ADMIN || this == PROJECT_OWNER || this == DEVELOPER;
    }

    public boolean canCreateProjects() {
        return this != REVIEWER;
    }

    public boolean canExecuteTasks() {
        return this != REVIEWER;
    }
}
