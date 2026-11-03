package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.github.GitHubCredential;
import eu.kalafatic.tryst.domain.github.GitHubCredentialRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of GitHubCredentialRepository.
 * Manages encrypted GitHub credentials associated with TRYST users.
 */
public class SqliteGitHubCredentialRepository implements GitHubCredentialRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteGitHubCredentialRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(GitHubCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        String sql = """
            INSERT INTO github_credentials (id, user_id, github_username, encrypted_token, scopes, token_type, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                user_id = excluded.user_id,
                github_username = excluded.github_username,
                encrypted_token = excluded.encrypted_token,
                scopes = excluded.scopes,
                token_type = excluded.token_type,
                expires_at = excluded.expires_at,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            databaseManager.runInTransaction(() -> {
                try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                    pstmt.setString(1, credential.getId());
                    pstmt.setString(2, credential.getUserId());
                    pstmt.setString(3, credential.getGithubUsername());
                    pstmt.setString(4, credential.getEncryptedToken());
                    pstmt.setString(5, credential.getScopes());
                    pstmt.setString(6, credential.getTokenType());
                    pstmt.setString(7, credential.getExpiresAt() != null ? credential.getExpiresAt().toString() : null);
                    pstmt.setString(8, credential.getCreatedAt().toString());
                    pstmt.setString(9, credential.getUpdatedAt().toString());
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TrystInfrastructureException("Failed to save GitHub credential for user " + credential.getUserId(), e);
                }
            });
        }
    }

    @Override
    public Optional<GitHubCredential> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT * FROM github_credentials WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find GitHub credential by id: " + id, e);
            }
        }
    }

    @Override
    public Optional<GitHubCredential> findByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String sql = "SELECT * FROM github_credentials WHERE user_id = ?;";
        synchronized (databaseManager.getLock()) {
            try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find GitHub credential by user_id: " + userId, e);
            }
        }
    }

    @Override
    public Optional<GitHubCredential> findByUsername(String githubUsername) {
        Objects.requireNonNull(githubUsername, "githubUsername must not be null");
        String sql = "SELECT * FROM github_credentials WHERE github_username = ?;";
        synchronized (databaseManager.getLock()) {
            try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                pstmt.setString(1, githubUsername);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find GitHub credential by username: " + githubUsername, e);
            }
        }
    }

    @Override
    public List<GitHubCredential> findAll() {
        String sql = "SELECT * FROM github_credentials ORDER BY created_at DESC;";
        List<GitHubCredential> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            try (Statement stmt = databaseManager.getConnection().createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to list all GitHub credentials", e);
            }
        }
    }

    @Override
    public void deleteByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String sql = "DELETE FROM github_credentials WHERE user_id = ?;";
        synchronized (databaseManager.getLock()) {
            databaseManager.runInTransaction(() -> {
                try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                    pstmt.setString(1, userId);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TrystInfrastructureException("Failed to delete GitHub credential for user: " + userId, e);
                }
            });
        }
    }

    @Override
    public void delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM github_credentials WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            databaseManager.runInTransaction(() -> {
                try (PreparedStatement pstmt = databaseManager.getConnection().prepareStatement(sql)) {
                    pstmt.setString(1, id);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    throw new TrystInfrastructureException("Failed to delete GitHub credential: " + id, e);
                }
            });
        }
    }

    private GitHubCredential mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String userId = rs.getString("user_id");
        String githubUsername = rs.getString("github_username");
        String encryptedToken = rs.getString("encrypted_token");
        String scopes = rs.getString("scopes");
        String tokenType = rs.getString("token_type");
        String expStr = rs.getString("expires_at");
        Instant expiresAt = expStr != null ? Instant.parse(expStr) : null;
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        Instant updatedAt = Instant.parse(rs.getString("updated_at"));

        return new GitHubCredential(
                id,
                userId,
                githubUsername,
                encryptedToken,
                scopes,
                tokenType,
                expiresAt,
                createdAt,
                updatedAt
        );
    }
}
