package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.TaskRecoveryCheckpoint;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of TaskRecoveryCheckpointRepository.
 */
public class SqliteTaskRecoveryCheckpointRepository implements TaskRecoveryCheckpointRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTaskRecoveryCheckpointRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(TaskRecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        String sql = """
            INSERT INTO task_recovery_checkpoints (id, task_id, stage, checkpoint_data, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                stage = excluded.stage,
                checkpoint_data = excluded.checkpoint_data,
                created_at = excluded.created_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, checkpoint.id());
                pstmt.setString(2, checkpoint.taskId());
                pstmt.setString(3, checkpoint.stage().name());
                pstmt.setString(4, checkpoint.checkpointDataJson());
                pstmt.setString(5, checkpoint.timestamp().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save recovery checkpoint: " + checkpoint.id(), e);
            }
        }
    }

    @Override
    public Optional<TaskRecoveryCheckpoint> findLatestByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = """
            SELECT id, task_id, stage, checkpoint_data, created_at
            FROM task_recovery_checkpoints
            WHERE task_id = ?
            ORDER BY created_at DESC
            LIMIT 1;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find latest checkpoint for task: " + taskId, e);
            }
        }
    }

    @Override
    public List<TaskRecoveryCheckpoint> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = """
            SELECT id, task_id, stage, checkpoint_data, created_at
            FROM task_recovery_checkpoints
            WHERE task_id = ?
            ORDER BY created_at ASC;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    List<TaskRecoveryCheckpoint> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find checkpoints for task: " + taskId, e);
            }
        }
    }

    @Override
    public Optional<TaskRecoveryCheckpoint> findByTaskIdAndStage(String taskId, TaskStatus stage) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        String sql = """
            SELECT id, task_id, stage, checkpoint_data, created_at
            FROM task_recovery_checkpoints
            WHERE task_id = ? AND stage = ?
            ORDER BY created_at DESC
            LIMIT 1;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                pstmt.setString(2, stage.name());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find checkpoint for task: " + taskId + " stage: " + stage, e);
            }
        }
    }

    @Override
    public void deleteByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "DELETE FROM task_recovery_checkpoints WHERE task_id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete checkpoints for task: " + taskId, e);
            }
        }
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM task_recovery_checkpoints;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to count task recovery checkpoints", e);
            }
        }
    }

    private TaskRecoveryCheckpoint mapRow(ResultSet rs) throws SQLException {
        return new TaskRecoveryCheckpoint(
                rs.getString("id"),
                rs.getString("task_id"),
                TaskStatus.valueOf(rs.getString("stage")),
                rs.getString("checkpoint_data"),
                Instant.parse(rs.getString("created_at"))
        );
    }
}
