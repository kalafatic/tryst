package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an interactive or autonomous agent execution context bound to a project.
 * Distinct from UI tab identity.
 */
public class Session {

    private final String id;
    private final String projectId;
    private final String workspaceId;
    private final String name;
    private SessionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Session(String projectId, String name) {
        this(projectId, null, name);
    }

    public Session(String projectId, String workspaceId, String name) {
        this(UUID.randomUUID().toString(), projectId, workspaceId, name, SessionStatus.ACTIVE, Instant.now(), Instant.now());
    }

    public Session(String id, String projectId, String name, String status, Instant createdAt, Instant updatedAt) {
        this(id, projectId, null, name, parseStatus(status), createdAt, updatedAt);
    }

    public Session(String id, String projectId, String workspaceId, String name, SessionStatus status, Instant createdAt, Instant updatedAt) {
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new TrystDomainException("Project ID must not be null or blank");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new TrystDomainException("Session name must not be null or blank");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = projectId.trim();
        this.workspaceId = workspaceId != null && !workspaceId.trim().isEmpty() ? workspaceId.trim() : null;
        this.name = name.trim();
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static SessionStatus parseStatus(String status) {
        if (status == null) {
            return SessionStatus.ACTIVE;
        }
        try {
            return SessionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SessionStatus.ACTIVE;
        }
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status.name();
    }

    public SessionStatus getSessionStatus() {
        return status;
    }

    public void setStatus(String status) {
        Objects.requireNonNull(status, "status must not be null");
        try {
            SessionStatus next = SessionStatus.valueOf(status.trim().toUpperCase());
            transitionTo(next);
        } catch (IllegalArgumentException e) {
            throw new TrystDomainException("Invalid session status: " + status);
        }
    }

    public void pause() {
        transitionTo(SessionStatus.PAUSED);
    }

    public void resume() {
        transitionTo(SessionStatus.ACTIVE);
    }

    public void complete() {
        transitionTo(SessionStatus.COMPLETED);
    }

    public void close() {
        transitionTo(SessionStatus.CLOSED);
    }

    public void terminate() {
        transitionTo(SessionStatus.TERMINATED);
    }

    private void transitionTo(SessionStatus nextStatus) {
        if (this.status == nextStatus) {
            return;
        }
        if (this.status == SessionStatus.TERMINATED) {
            throw new TrystDomainException("Cannot transition from terminal status TERMINATED to " + nextStatus);
        }
        if (this.status == SessionStatus.CLOSED && nextStatus != SessionStatus.TERMINATED) {
            throw new TrystDomainException("Cannot transition from terminal status CLOSED to " + nextStatus);
        }
        if (this.status == SessionStatus.COMPLETED && nextStatus != SessionStatus.CLOSED && nextStatus != SessionStatus.TERMINATED) {
            throw new TrystDomainException("Cannot transition from COMPLETED to " + nextStatus);
        }
        if (nextStatus == SessionStatus.ACTIVE && this.status != SessionStatus.PAUSED) {
            // initial active is set in constructor, subsequent transition to ACTIVE is only allowed from PAUSED
            throw new TrystDomainException("Cannot resume session from status: " + this.status);
        }

        this.status = nextStatus;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Session session)) return false;
        return Objects.equals(id, session.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Session{" +
                "id='" + id + '\'' +
                ", projectId='" + projectId + '\'' +
                ", name='" + name + '\'' +
                ", status=" + status +
                '}';
    }
}
