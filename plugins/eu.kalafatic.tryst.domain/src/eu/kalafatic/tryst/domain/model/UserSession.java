package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an authenticated user session.
 */
public class UserSession {

    private final String id;
    private final String userId;
    private final String tokenHash;
    private final String clientIp;
    private final String userAgent;
    private Instant expiresAt;
    private boolean revoked;
    private final Instant createdAt;
    private Instant lastActivity;

    public UserSession(String id, String userId, String tokenHash, String clientIp, String userAgent,
                       Instant expiresAt, boolean revoked, Instant createdAt, Instant lastActivity) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.clientIp = clientIp != null ? clientIp : "unknown";
        this.userAgent = userAgent != null ? userAgent : "unknown";
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.revoked = revoked;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastActivity = lastActivity != null ? lastActivity : Instant.now();
    }

    public UserSession(String userId, String tokenHash, String clientIp, String userAgent, long sessionTtlSeconds) {
        this(UUID.randomUUID().toString(), userId, tokenHash, clientIp, userAgent,
                Instant.now().plusSeconds(sessionTtlSeconds), false, Instant.now(), Instant.now());
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void revoke() {
        this.revoked = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public void touch(long sessionTtlSeconds) {
        this.lastActivity = Instant.now();
        this.expiresAt = this.lastActivity.plusSeconds(sessionTtlSeconds);
    }

    public boolean isValid() {
        return !revoked && Instant.now().isBefore(expiresAt);
    }
}
