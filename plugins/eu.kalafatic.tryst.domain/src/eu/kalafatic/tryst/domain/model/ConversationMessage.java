package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a conversation message in a session.
 */
public class ConversationMessage {

    private final String id;
    private final String sessionId;
    private final String role;
    private final String content;
    private final Instant createdAt;

    public ConversationMessage(String sessionId, String role, String content) {
        this(UUID.randomUUID().toString(), sessionId, role, content, Instant.now());
    }

    public ConversationMessage(String id, String sessionId, String role, String content, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConversationMessage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ConversationMessage{" +
                "id='" + id + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", role='" + role + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
