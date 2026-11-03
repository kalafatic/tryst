package eu.kalafatic.tryst.domain.github;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an encrypted GitHub credential connection for a TRYST user.
 * Plaintext tokens are NEVER stored in this entity or persisted to disk.
 */
public class GitHubCredential {

    private final String id;
    private final String userId;
    private String githubUsername;
    private String encryptedToken;
    private String scopes;
    private String tokenType;
    private Instant expiresAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public GitHubCredential(String userId,
                            String githubUsername,
                            String encryptedToken,
                            String scopes,
                            String tokenType,
                            Instant expiresAt) {
        this(UUID.randomUUID().toString(), userId, githubUsername, encryptedToken, scopes, tokenType, expiresAt, Instant.now(), Instant.now());
    }

    public GitHubCredential(String id,
                            String userId,
                            String githubUsername,
                            String encryptedToken,
                            String scopes,
                            String tokenType,
                            Instant expiresAt,
                            Instant createdAt,
                            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.githubUsername = Objects.requireNonNull(githubUsername, "githubUsername must not be null");
        this.encryptedToken = Objects.requireNonNull(encryptedToken, "encryptedToken must not be null");
        this.scopes = scopes != null ? scopes : "";
        this.tokenType = tokenType != null ? tokenType : "pat";
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (githubUsername.isBlank()) {
            throw new TrystDomainException("GitHub username must not be blank");
        }
        if (encryptedToken.isBlank()) {
            throw new TrystDomainException("Encrypted token must not be blank");
        }
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getGithubUsername() {
        return githubUsername;
    }

    public String getEncryptedToken() {
        return encryptedToken;
    }

    public String getScopes() {
        return scopes;
    }

    public String getTokenType() {
        return tokenType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public void updateCredential(String encryptedToken, String scopes, Instant expiresAt) {
        if (encryptedToken == null || encryptedToken.isBlank()) {
            throw new TrystDomainException("Updated encrypted token must not be blank");
        }
        this.encryptedToken = encryptedToken;
        this.scopes = scopes != null ? scopes : "";
        this.expiresAt = expiresAt;
        this.updatedAt = Instant.now();
    }

    public void updateGithubUsername(String githubUsername) {
        if (githubUsername == null || githubUsername.isBlank()) {
            throw new TrystDomainException("GitHub username must not be blank");
        }
        this.githubUsername = githubUsername;
        this.updatedAt = Instant.now();
    }
}
