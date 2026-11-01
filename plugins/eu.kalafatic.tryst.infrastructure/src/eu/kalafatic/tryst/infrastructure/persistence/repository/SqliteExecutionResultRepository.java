package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
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
 * SQLite implementation of ExecutionResultRepository.
 */
public class SqliteExecutionResultRepository implements ExecutionResultRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteExecutionResultRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(ExecutionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        String sql = """
            INSERT INTO execution_results (id, task_id, exit_code, output, error_output, duration_ms, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                task_id = excluded.task_id,
                exit_code = excluded.exit_code,
                output = excluded.output,
                error_output = excluded.error_output,
                duration_ms = excluded.duration_ms,
                completed_at = excluded.completed_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, result.getId());
                pstmt.setString(2, result.getTaskId());
                pstmt.setInt(3, result.getExitCode());
                pstmt.setString(4, result.getOutput());
                pstmt.setString(5, result.getErrorOutput());
                pstmt.setLong(6, result.getDurationMs());
                pstmt.setString(7, result.getCompletedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save execution result: " + result.getId(), e);
            }
        }
    }

    @Override
    public Optional<ExecutionResult> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, task_id, exit_code, output, error_output, duration_ms, completed_at FROM execution_results WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToExecutionResult(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find execution result by id: " + id, e);
            }
        }
    }

    @Override
    public List<ExecutionResult> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT id, task_id, exit_code, output, error_output, duration_ms, completed_at FROM execution_results WHERE task_id = ? ORDER BY completed_at ASC;";
        List<ExecutionResult> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToExecutionResult(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find execution results for task: " + taskId, e);
            }
        }
    }

    @Override
    public Optional<ExecutionResult> findLatestByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT id, task_id, exit_code, output, error_output, duration_ms, completed_at FROM execution_results WHERE task_id = ? ORDER BY completed_at DESC LIMIT 1;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToExecutionResult(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find latest execution result for task: " + taskId, e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM execution_results WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete execution result: " + id, e);
            }
        }
    }

    private ExecutionResult mapResultSetToExecutionResult(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String taskId = rs.getString("task_id");
        int exitCode = rs.getInt("exit_code");
        String output = rs.getString("output");
        String errorOutput = rs.getString("error_output");
        long durationMs = rs.getLong("duration_ms");
        Instant completedAt = Instant.parse(rs.getString("completed_at"));
        return new ExecutionResult(id, taskId, exitCode, output, errorOutput, durationMs, completedAt);
    }
}
