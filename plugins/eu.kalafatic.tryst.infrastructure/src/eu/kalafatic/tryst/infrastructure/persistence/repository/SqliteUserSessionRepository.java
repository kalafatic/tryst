package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.UserSession;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SqliteUserSessionRepository implements UserSessionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteUserSessionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(UserSession session) {
        Objects.requireNonNull(session, "session must not be null");
        String sql = """
            INSERT INTO user_sessions (id, user_id, token_hash, client_ip, user_agent, expires_at, revoked, created_at, last_activity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                expires_at = excluded.expires_at,
                revoked = excluded.revoked,
                last_activity = excluded.last_activity;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, session.getId());
                pstmt.setString(2, session.getUserId());
                pstmt.setString(3, session.getTokenHash());
                pstmt.setString(4, session.getClientIp());
                pstmt.setString(5, session.getUserAgent());
                pstmt.setString(6, session.getExpiresAt().toString());
                pstmt.setInt(7, session.isRevoked() ? 1 : 0);
                pstmt.setString(8, session.getCreatedAt().toString());
                pstmt.setString(9, session.getLastActivity().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save user session " + session.getId(), e);
            }
        }
    }

    @Override
    public Optional<UserSession> findById(String id) {
        String sql = "SELECT * FROM user_sessions WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find session by id " + id, e);
            }
        }
    }

    @Override
    public Optional<UserSession> findByTokenHash(String tokenHash) {
        String sql = "SELECT * FROM user_sessions WHERE token_hash = ? AND revoked = 0;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, tokenHash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find session by token hash", e);
            }
        }
    }

    @Override
    public List<UserSession> findByUserId(String userId) {
        String sql = "SELECT * FROM user_sessions WHERE user_id = ? ORDER BY created_at DESC;";
        List<UserSession> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find sessions for user " + userId, e);
            }
        }
    }

    @Override
    public List<UserSession> findAllActive() {
        String sql = "SELECT * FROM user_sessions WHERE revoked = 0 ORDER BY last_activity DESC;";
        List<UserSession> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query active sessions", e);
            }
        }
    }

    @Override
    public List<UserSession> findAll() {
        String sql = "SELECT * FROM user_sessions ORDER BY created_at DESC;";
        List<UserSession> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query sessions", e);
            }
        }
    }

    @Override
    public void revokeByUserId(String userId) {
        String sql = "UPDATE user_sessions SET revoked = 1 WHERE user_id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to revoke sessions for user " + userId, e);
            }
        }
    }

    @Override
    public void revoke(String sessionId) {
        String sql = "UPDATE user_sessions SET revoked = 1 WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sessionId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to revoke session " + sessionId, e);
            }
        }
    }

    @Override
    public void deleteExpired() {
        String sql = "DELETE FROM user_sessions WHERE expires_at < ? OR revoked = 1;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, Instant.now().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete expired sessions", e);
            }
        }
    }

    private UserSession mapRow(ResultSet rs) throws SQLException {
        return new UserSession(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("token_hash"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                Instant.parse(rs.getString("expires_at")),
                rs.getInt("revoked") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("last_activity"))
        );
    }
}
