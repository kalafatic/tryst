package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an asynchronous notification to a user.
 */
public class Notification {

    private final String id;
    private final String userId;
    private final String type;
    private final String title;
    private final String message;
    private final String referenceType;
    private final String referenceId;
    private boolean read;
    private final Instant createdAt;

    public Notification(String id, String userId, String type, String title, String message,
                        String referenceType, String referenceId, boolean read, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.read = read;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public Notification(String userId, String type, String title, String message, String referenceType, String referenceId) {
        this(UUID.randomUUID().toString(), userId, type, title, message, referenceType, referenceId, false, Instant.now());
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public boolean isRead() {
        return read;
    }

    public void markRead() {
        this.read = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
