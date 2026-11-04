package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an authenticated user account.
 */
public class User {

    private final String id;
    private String username;
    private String passwordHash;
    private String salt;
    private UserRole role;
    private boolean active;
    private boolean locked;
    private int failedAttempts;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(String id, String username, String passwordHash, String salt, UserRole role,
                boolean active, boolean locked, int failedAttempts, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.username = validateUsername(username);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.salt = Objects.requireNonNull(salt, "salt must not be null");
        this.role = role != null ? role : UserRole.USER;
        this.active = active;
        this.locked = locked;
        this.failedAttempts = Math.max(0, failedAttempts);
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public User(String username, String passwordHash, String salt, UserRole role) {
        this(UUID.randomUUID().toString(), username, passwordHash, salt, role, true, false, 0, Instant.now(), Instant.now());
    }

    private static String validateUsername(String name) {
        if (name == null || name.trim().length() < 3) {
            throw new TrystDomainException("Username must be at least 3 characters");
        }
        return name.trim();
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = validateUsername(username);
        this.updatedAt = Instant.now();
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void updatePassword(String passwordHash, String salt) {
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.salt = Objects.requireNonNull(salt, "salt must not be null");
        this.failedAttempts = 0;
        this.updatedAt = Instant.now();
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        this.updatedAt = Instant.now();
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void incrementFailedAttempts() {
        this.failedAttempts++;
        if (this.failedAttempts >= 5) {
            this.locked = true;
        }
        this.updatedAt = Instant.now();
    }

    public void resetFailedAttempts() {
        this.failedAttempts = 0;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasPermission(Permission permission) {
        if (!active || locked) {
            return false;
        }
        if (role == UserRole.ADMIN) {
            return true;
        }
        return switch (permission) {
            case MANAGE_USERS, MANAGE_ROLES, MANAGE_SERVER_CONFIG, INSPECT_SERVER_HEALTH, VIEW_AUDIT_LOGS, MANAGE_SESSIONS -> false;
            case CREATE_PROJECTS, EXECUTE_TASKS, CANCEL_TASKS -> role != UserRole.REVIEWER && role != UserRole.USER;
            case ACCESS_PROJECTS, VIEW_TASKS, ACCESS_WORKSPACE -> true;
            case EDIT_PROPOSED_CHANGES, APPROVE_REVIEWS, COMMIT_APPROVED_CHANGES ->
                    role == UserRole.PROJECT_OWNER || role == UserRole.DEVELOPER;
            case MANAGE_AGENTS -> role == UserRole.PROJECT_OWNER || role == UserRole.DEVELOPER;
            case MANAGE_GITHUB_CREDENTIALS -> role != UserRole.USER;
        };
    }
}
