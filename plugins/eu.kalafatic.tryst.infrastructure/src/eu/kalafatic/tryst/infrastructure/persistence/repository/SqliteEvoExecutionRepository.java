package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.EvoExecutionEvent;
import eu.kalafatic.tryst.domain.repository.EvoExecutionRepository;
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
 * SQLite implementation of EvoExecutionRepository managing execution mappings and events.
 */
public class SqliteEvoExecutionRepository implements EvoExecutionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteEvoExecutionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void saveExecution(EvoExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        String sql = """
            INSERT INTO evo_executions (
                id, tryst_task_id, tryst_session_id, evo_execution_id, evo_session_id,
                capability, ai_mode, status, phase, progress, last_sequence,
                idempotency_key, summary, error, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                tryst_task_id = excluded.tryst_task_id,
                tryst_session_id = excluded.tryst_session_id,
                evo_execution_id = excluded.evo_execution_id,
                evo_session_id = excluded.evo_session_id,
                capability = excluded.capability,
                ai_mode = excluded.ai_mode,
                status = excluded.status,
                phase = excluded.phase,
                progress = excluded.progress,
                last_sequence = excluded.last_sequence,
                idempotency_key = excluded.idempotency_key,
                summary = excluded.summary,
                error = excluded.error,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, execution.getId());
                pstmt.setString(2, execution.getTrystTaskId());
                pstmt.setString(3, execution.getTrystSessionId());
                pstmt.setString(4, execution.getEvoExecutionId());
                pstmt.setString(5, execution.getEvoSessionId());
                pstmt.setString(6, execution.getCapability());
                pstmt.setString(7, execution.getAiMode());
                pstmt.setString(8, execution.getStatus().name());
                pstmt.setString(9, execution.getPhase());
                pstmt.setDouble(10, execution.getProgress());
                pstmt.setLong(11, execution.getLastSequence());
                pstmt.setString(12, execution.getIdempotencyKey());
                pstmt.setString(13, execution.getSummary());
                pstmt.setString(14, execution.getError());
                pstmt.setString(15, execution.getCreatedAt().toString());
                pstmt.setString(16, execution.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save EVO execution: " + execution.getId(), e);
            }
        }
    }

    @Override
    public Optional<EvoExecution> findExecutionById(String id) {
        return findSingle("SELECT * FROM evo_executions WHERE id = ?", id);
    }

    @Override
    public Optional<EvoExecution> findExecutionByEvoExecutionId(String evoExecutionId) {
        return findSingle("SELECT * FROM evo_executions WHERE evo_execution_id = ?", evoExecutionId);
    }

    @Override
    public Optional<EvoExecution> findExecutionByIdempotencyKey(String idempotencyKey) {
        return findSingle("SELECT * FROM evo_executions WHERE idempotency_key = ?", idempotencyKey);
    }

    private Optional<EvoExecution> findSingle(String sql, String param) {
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, param);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToExecution(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query execution: " + sql, e);
            }
        }
    }

    @Override
    public List<EvoExecution> findExecutionsByTaskId(String taskId) {
        String sql = "SELECT * FROM evo_executions WHERE tryst_task_id = ? ORDER BY created_at DESC";
        List<EvoExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToExecution(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query executions by task id: " + taskId, e);
            }
        }
        return list;
    }

    @Override
    public List<EvoExecution> findAllExecutions() {
        String sql = "SELECT * FROM evo_executions ORDER BY created_at DESC";
        List<EvoExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToExecution(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query all executions", e);
            }
        }
        return list;
    }

    @Override
    public boolean saveEvent(EvoExecutionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String sql = """
            INSERT INTO evo_execution_events (
                event_id, execution_id, sequence, event_type, correlation_id, timestamp, data_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(event_id) DO NOTHING;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, event.getEventId());
                pstmt.setString(2, event.getExecutionId());
                pstmt.setLong(3, event.getSequence());
                pstmt.setString(4, event.getEventType());
                pstmt.setString(5, event.getCorrelationId());
                pstmt.setString(6, event.getTimestamp().toString());
                pstmt.setString(7, event.getDataJson());
                int rows = pstmt.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save execution event: " + event.getEventId(), e);
            }
        }
    }

    @Override
    public List<EvoExecutionEvent> findEventsByExecutionId(String executionId, long afterSequence, int limit) {
        String sql = """
            SELECT * FROM evo_execution_events
            WHERE execution_id = ? AND sequence > ?
            ORDER BY sequence ASC
            LIMIT ?;
        """;
        List<EvoExecutionEvent> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, executionId);
                pstmt.setLong(2, afterSequence);
                pstmt.setInt(3, limit > 0 ? limit : 100);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToEvent(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query events for execution: " + executionId, e);
            }
        }
        return list;
    }

    @Override
    public long getLatestSequence(String executionId) {
        String sql = "SELECT MAX(sequence) AS max_seq FROM evo_execution_events WHERE execution_id = ?";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, executionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("max_seq");
                    }
                    return 0;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to get latest sequence for execution: " + executionId, e);
            }
        }
    }

    private EvoExecution mapResultSetToExecution(ResultSet rs) throws SQLException {
        return new EvoExecution(
                rs.getString("id"),
                rs.getString("tryst_task_id"),
                rs.getString("tryst_session_id"),
                rs.getString("evo_execution_id"),
                rs.getString("evo_session_id"),
                rs.getString("capability"),
                rs.getString("ai_mode"),
                EvoExecution.ExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("phase"),
                rs.getDouble("progress"),
                rs.getLong("last_sequence"),
                rs.getString("idempotency_key"),
                rs.getString("summary"),
                rs.getString("error"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private EvoExecutionEvent mapResultSetToEvent(ResultSet rs) throws SQLException {
        return new EvoExecutionEvent(
                rs.getString("event_id"),
                rs.getString("execution_id"),
                rs.getLong("sequence"),
                rs.getString("event_type"),
                rs.getString("correlation_id"),
                Instant.parse(rs.getString("timestamp")),
                rs.getString("data_json")
        );
    }
}
