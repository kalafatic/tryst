package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.repository.SessionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of SessionRepository.
 */
public class SqliteSessionRepository implements SessionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteSessionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(Session session) {
        Objects.requireNonNull(session, "session must not be null");
        String sql = """
            INSERT INTO sessions (id, project_id, name, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                project_id = excluded.project_id,
                name = excluded.name,
                status = excluded.status,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, session.getId());
                pstmt.setString(2, session.getProjectId());
                pstmt.setString(3, session.getName());
                pstmt.setString(4, session.getStatus());
                pstmt.setString(5, session.getCreatedAt().toString());
                pstmt.setString(6, session.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save session: " + session.getId(), e);
            }
        }
    }

    @Override
    public Optional<Session> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, project_id, name, status, created_at, updated_at FROM sessions WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToSession(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find session by id: " + id, e);
            }
        }
    }

    @Override
    public List<Session> findByProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        String sql = "SELECT id, project_id, name, status, created_at, updated_at FROM sessions WHERE project_id = ? ORDER BY created_at ASC;";
        List<Session> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToSession(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find sessions for project: " + projectId, e);
            }
        }
    }

    @Override
    public List<Session> findAll() {
        String sql = "SELECT id, project_id, name, status, created_at, updated_at FROM sessions ORDER BY created_at DESC;";
        List<Session> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToSession(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all sessions", e);
            }
        }
    }

    @Override
    public List<Session> findRecoverableSessions() {
        String sql = "SELECT id, project_id, name, status, created_at, updated_at FROM sessions WHERE status NOT IN ('COMPLETED', 'CLOSED', 'TERMINATED') ORDER BY updated_at DESC;";
        List<Session> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToSession(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find recoverable sessions", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM sessions WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete session: " + id, e);
            }
        }
    }

    private Session mapResultSetToSession(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String projectId = rs.getString("project_id");
        String name = rs.getString("name");
        String status = rs.getString("status");
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        Instant updatedAt = Instant.parse(rs.getString("updated_at"));
        return new Session(id, projectId, name, status, createdAt, updatedAt);
    }
}
