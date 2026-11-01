package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a conversation message in a session.
 */
public class ConversationMessage {

    private final String id;
    private final String sessionId;
    private final MessageRole role;
    private final String content;
    private final Instant createdAt;
    private final Map<String, String> metadata;

    public ConversationMessage(String sessionId, String role, String content) {
        this(UUID.randomUUID().toString(), sessionId, role, content, Instant.now());
    }

    public ConversationMessage(String sessionId, MessageRole role, String content) {
        this(UUID.randomUUID().toString(), sessionId, role, content, Instant.now(), Collections.emptyMap());
    }

    public ConversationMessage(String id, String sessionId, String role, String content, Instant createdAt) {
        this(id, sessionId, parseRole(role), content, createdAt, Collections.emptyMap());
    }

    public ConversationMessage(String id, String sessionId, MessageRole role, String content, Instant createdAt, Map<String, String> metadata) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new TrystDomainException("Message sessionId must not be null or blank");
        }
        if (role == null) {
            throw new TrystDomainException("Message role must not be null");
        }
        if (content == null) {
            throw new TrystDomainException("Message content must not be null");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sessionId = sessionId.trim();
        this.role = role;
        this.content = content;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }

    private static MessageRole parseRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            throw new TrystDomainException("Message role must not be null or blank");
        }
        try {
            return MessageRole.fromString(role);
        } catch (IllegalArgumentException e) {
            throw new TrystDomainException("Invalid message role: " + role, e);
        }
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role.name();
    }

    public MessageRole getMessageRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
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
                ", role=" + role +
                ", createdAt=" + createdAt +
                '}';
    }
}
