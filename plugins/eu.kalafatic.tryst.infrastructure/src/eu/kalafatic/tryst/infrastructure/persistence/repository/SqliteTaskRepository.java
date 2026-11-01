package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of TaskRepository.
 */
public class SqliteTaskRepository implements TaskRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTaskRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        String sql = """
            INSERT INTO tasks (id, project_id, session_id, title, description, status, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                project_id = excluded.project_id,
                session_id = excluded.session_id,
                title = excluded.title,
                description = excluded.description,
                status = excluded.status,
                completed_at = excluded.completed_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, task.getId());
                if (task.getProjectId() != null) {
                    pstmt.setString(2, task.getProjectId());
                } else {
                    pstmt.setNull(2, Types.VARCHAR);
                }
                if (task.getSessionId() != null) {
                    pstmt.setString(3, task.getSessionId());
                } else {
                    pstmt.setNull(3, Types.VARCHAR);
                }
                pstmt.setString(4, task.getTitle());
                pstmt.setString(5, task.getDescription());
                pstmt.setString(6, task.getStatus().name());
                pstmt.setString(7, task.getCreatedAt().toString());
                if (task.getCompletedAt() != null) {
                    pstmt.setString(8, task.getCompletedAt().toString());
                } else {
                    pstmt.setNull(8, Types.VARCHAR);
                }
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save task: " + task.getId(), e);
            }
        }
    }

    @Override
    public Optional<AgentTask> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, project_id, session_id, title, description, status, created_at, completed_at FROM tasks WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToTask(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find task by id: " + id, e);
            }
        }
    }

    @Override
    public List<AgentTask> findBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = "SELECT id, project_id, session_id, title, description, status, created_at, completed_at FROM tasks WHERE session_id = ? ORDER BY created_at ASC;";
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToTask(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find tasks for session: " + sessionId, e);
            }
        }
    }

    @Override
    public List<AgentTask> findByProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        String sql = "SELECT id, project_id, session_id, title, description, status, created_at, completed_at FROM tasks WHERE project_id = ? ORDER BY created_at ASC;";
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToTask(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find tasks for project: " + projectId, e);
            }
        }
    }

    @Override
    public List<AgentTask> findTasksByStatus(ExecutionStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        String sql = "SELECT id, project_id, session_id, title, description, status, created_at, completed_at FROM tasks WHERE status = ? ORDER BY created_at ASC;";
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, status.name());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToTask(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find tasks by status: " + status, e);
            }
        }
    }

    @Override
    public List<AgentTask> findAll() {
        String sql = "SELECT id, project_id, session_id, title, description, status, created_at, completed_at FROM tasks ORDER BY created_at ASC;";
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToTask(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all tasks", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM tasks WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete task: " + id, e);
            }
        }
    }

    private AgentTask mapResultSetToTask(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String projectId = rs.getString("project_id");
        String sessionId = rs.getString("session_id");
        String title = rs.getString("title");
        String description = rs.getString("description");
        ExecutionStatus status = ExecutionStatus.valueOf(rs.getString("status"));
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        String completedAtStr = rs.getString("completed_at");
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;
        return new AgentTask(id, projectId, sessionId, title, description, status, createdAt, completedAt);
    }
}
