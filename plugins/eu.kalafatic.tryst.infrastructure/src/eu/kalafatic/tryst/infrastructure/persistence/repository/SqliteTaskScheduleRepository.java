package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.schedule.ScheduleType;
import eu.kalafatic.tryst.domain.schedule.TaskSchedule;
import eu.kalafatic.tryst.domain.schedule.TaskScheduleRepository;
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
 * SQLite repository implementation for TaskSchedule.
 */
public class SqliteTaskScheduleRepository implements TaskScheduleRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTaskScheduleRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(TaskSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule must not be null");
        String sql = """
            INSERT INTO task_schedules (
                id, name, project_id, user_id, schedule_type, cron_expression,
                scheduled_time, task_payload_json, status, last_executed_at,
                next_execution_at, execution_count, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                project_id = excluded.project_id,
                user_id = excluded.user_id,
                schedule_type = excluded.schedule_type,
                cron_expression = excluded.cron_expression,
                scheduled_time = excluded.scheduled_time,
                task_payload_json = excluded.task_payload_json,
                status = excluded.status,
                last_executed_at = excluded.last_executed_at,
                next_execution_at = excluded.next_execution_at,
                execution_count = excluded.execution_count;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schedule.getId());
                pstmt.setString(2, schedule.getName());
                pstmt.setString(3, schedule.getProjectId());
                pstmt.setString(4, schedule.getUserId());
                pstmt.setString(5, schedule.getScheduleType().name());
                pstmt.setString(6, schedule.getCronExpression());
                pstmt.setString(7, schedule.getScheduledTime() != null ? schedule.getScheduledTime().toString() : null);
                pstmt.setString(8, schedule.getTaskPayloadJson());
                pstmt.setString(9, schedule.getStatus().name());
                pstmt.setString(10, schedule.getLastExecutedAt() != null ? schedule.getLastExecutedAt().toString() : null);
                pstmt.setString(11, schedule.getNextExecutionAt() != null ? schedule.getNextExecutionAt().toString() : null);
                pstmt.setInt(12, schedule.getExecutionCount());
                pstmt.setString(13, schedule.getCreatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save task schedule: " + schedule.getId(), e);
            }
        }
    }

    @Override
    public Optional<TaskSchedule> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, name, project_id, user_id, schedule_type, cron_expression,
                   scheduled_time, task_payload_json, status, last_executed_at,
                   next_execution_at, execution_count, created_at
            FROM task_schedules WHERE id = ?;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToSchedule(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query task schedule by id: " + id, e);
            }
        }
    }

    @Override
    public List<TaskSchedule> findDueSchedules(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        String sql = """
            SELECT id, name, project_id, user_id, schedule_type, cron_expression,
                   scheduled_time, task_payload_json, status, last_executed_at,
                   next_execution_at, execution_count, created_at
            FROM task_schedules
            WHERE status = 'ACTIVE' AND next_execution_at <= ?
            ORDER BY next_execution_at ASC;
        """;

        List<TaskSchedule> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, asOf.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToSchedule(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query due task schedules", e);
            }
        }
        return list;
    }

    @Override
    public List<TaskSchedule> findByProjectId(String projectId) {
        String sql = """
            SELECT id, name, project_id, user_id, schedule_type, cron_expression,
                   scheduled_time, task_payload_json, status, last_executed_at,
                   next_execution_at, execution_count, created_at
            FROM task_schedules WHERE project_id = ?
            ORDER BY created_at DESC;
        """;

        List<TaskSchedule> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToSchedule(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query task schedules for project: " + projectId, e);
            }
        }
        return list;
    }

    @Override
    public List<TaskSchedule> findByUserId(String userId) {
        String sql = """
            SELECT id, name, project_id, user_id, schedule_type, cron_expression,
                   scheduled_time, task_payload_json, status, last_executed_at,
                   next_execution_at, execution_count, created_at
            FROM task_schedules WHERE user_id = ?
            ORDER BY created_at DESC;
        """;

        List<TaskSchedule> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToSchedule(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query task schedules for user: " + userId, e);
            }
        }
        return list;
    }

    @Override
    public List<TaskSchedule> findAll() {
        String sql = """
            SELECT id, name, project_id, user_id, schedule_type, cron_expression,
                   scheduled_time, task_payload_json, status, last_executed_at,
                   next_execution_at, execution_count, created_at
            FROM task_schedules
            ORDER BY created_at DESC;
        """;

        List<TaskSchedule> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToSchedule(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query all task schedules", e);
            }
        }
        return list;
    }

    @Override
    public boolean delete(String id) {
        String sql = "DELETE FROM task_schedules WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete task schedule: " + id, e);
            }
        }
    }

    private TaskSchedule mapResultSetToSchedule(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String projectId = rs.getString("project_id");
        String userId = rs.getString("user_id");
        String typeStr = rs.getString("schedule_type");
        String cron = rs.getString("cron_expression");
        String schedStr = rs.getString("scheduled_time");
        String payloadJson = rs.getString("task_payload_json");
        String statusStr = rs.getString("status");
        String lastExecStr = rs.getString("last_executed_at");
        String nextExecStr = rs.getString("next_execution_at");
        int execCount = rs.getInt("execution_count");
        Instant createdAt = Instant.parse(rs.getString("created_at"));

        ScheduleType type = ScheduleType.valueOf(typeStr);
        Instant scheduledTime = schedStr != null ? Instant.parse(schedStr) : null;
        TaskSchedule.Status status = TaskSchedule.Status.valueOf(statusStr);
        Instant lastExecutedAt = lastExecStr != null ? Instant.parse(lastExecStr) : null;
        Instant nextExecutionAt = nextExecStr != null ? Instant.parse(nextExecStr) : null;

        return new TaskSchedule(id, name, projectId, userId, type, cron, scheduledTime,
                payloadJson, status, lastExecutedAt, nextExecutionAt, execCount, createdAt);
    }
}
