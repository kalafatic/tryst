package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
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
 * SQLite implementation of TaskRepository supporting full TaskStatus persistence,
 * atomic task claiming, and persistent task events.
 */
public class SqliteTaskRepository implements TaskRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTaskRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        saveTask(Task.fromAgentTask(task));
    }

    @Override
    public void saveTask(Task task) {
        Objects.requireNonNull(task, "task must not be null");
        String sql = """
            INSERT INTO tasks (
                id, project_id, session_id, title, description, status, priority,
                agent_id, worker_id, execution_result_id, review_id, rework_count,
                failure_reason, created_at, completed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                project_id = excluded.project_id,
                session_id = excluded.session_id,
                title = excluded.title,
                description = excluded.description,
                status = excluded.status,
                priority = excluded.priority,
                agent_id = excluded.agent_id,
                worker_id = excluded.worker_id,
                execution_result_id = excluded.execution_result_id,
                review_id = excluded.review_id,
                rework_count = excluded.rework_count,
                failure_reason = excluded.failure_reason,
                completed_at = excluded.completed_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, task.getId());
                setNullableString(pstmt, 2, task.getProjectId());
                setNullableString(pstmt, 3, task.getSessionId());
                pstmt.setString(4, task.getTitle());
                pstmt.setString(5, task.getDescription());
                pstmt.setString(6, task.getStatus().name());
                pstmt.setString(7, task.getPriority() != null ? task.getPriority().name() : TaskPriority.NORMAL.name());
                setNullableString(pstmt, 8, task.getAgentId());
                setNullableString(pstmt, 9, task.getWorkerId());
                setNullableString(pstmt, 10, task.getExecutionResultId());
                setNullableString(pstmt, 11, task.getReviewId());
                pstmt.setInt(12, task.getReworkCount());
                setNullableString(pstmt, 13, task.getFailureReason());
                pstmt.setString(14, task.getCreatedAt().toString());
                if (task.getCompletedAt() != null) {
                    pstmt.setString(15, task.getCompletedAt().toString());
                } else {
                    pstmt.setNull(15, Types.VARCHAR);
                }
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save task: " + task.getId(), e);
            }
        }
    }

    @Override
    public Optional<AgentTask> findById(String id) {
        return findTaskById(id).map(Task::toAgentTask);
    }

    @Override
    public Optional<Task> findTaskById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, project_id, session_id, title, description, status,
                   priority, agent_id, worker_id, execution_result_id, review_id,
                   rework_count, failure_reason, created_at, completed_at
            FROM tasks WHERE id = ?;
        """;

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
        String sql = """
            SELECT id, project_id, session_id, title, description, status,
                   priority, agent_id, worker_id, execution_result_id, review_id,
                   rework_count, failure_reason, created_at, completed_at
            FROM tasks WHERE session_id = ? ORDER BY created_at ASC;
        """;
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToTask(rs).toAgentTask());
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
        String sql = """
            SELECT id, project_id, session_id, title, description, status,
                   priority, agent_id, worker_id, execution_result_id, review_id,
                   rework_count, failure_reason, created_at, completed_at
            FROM tasks WHERE project_id = ? ORDER BY created_at ASC;
        """;
        List<AgentTask> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToTask(rs).toAgentTask());
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
        return findAll().stream()
                .filter(t -> t.getStatus() == status)
                .toList();
    }

    @Override
    public List<Task> findTasksByTaskStatus(TaskStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        String sql = """
            SELECT id, project_id, session_id, title, description, status,
                   priority, agent_id, worker_id, execution_result_id, review_id,
                   rework_count, failure_reason, created_at, completed_at
            FROM tasks WHERE status = ? ORDER BY created_at ASC;
        """;
        List<Task> list = new ArrayList<>();

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
                throw new TrystInfrastructureException("Failed to find tasks by TaskStatus: " + status, e);
            }
        }
    }

    @Override
    public List<AgentTask> findAll() {
        return findAllTasks().stream().map(Task::toAgentTask).toList();
    }

    @Override
    public List<Task> findAllTasks() {
        String sql = """
            SELECT id, project_id, session_id, title, description, status,
                   priority, agent_id, worker_id, execution_result_id, review_id,
                   rework_count, failure_reason, created_at, completed_at
            FROM tasks ORDER BY created_at ASC;
        """;
        List<Task> list = new ArrayList<>();

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

    @Override
    public Optional<Task> claimNextQueuedTask(String workerId) {
        Objects.requireNonNull(workerId, "workerId must not be null");

        // Priority ordering: URGENT (4), HIGH (3), NORMAL (2), LOW (1)
        String selectSql = """
            SELECT id FROM tasks
            WHERE status = 'QUEUED'
            ORDER BY
                CASE priority
                    WHEN 'URGENT' THEN 4
                    WHEN 'HIGH' THEN 3
                    WHEN 'NORMAL' THEN 2
                    WHEN 'LOW' THEN 1
                    ELSE 0
                END DESC,
                created_at ASC
            LIMIT 1;
        """;

        String updateSql = """
            UPDATE tasks
            SET status = 'CLAIMED', worker_id = ?
            WHERE id = ? AND status = 'QUEUED';
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try {
                conn.setAutoCommit(false);
                try {
                    String selectedId = null;
                    try (PreparedStatement selectStmt = conn.prepareStatement(selectSql);
                         ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            selectedId = rs.getString("id");
                        }
                    }

                    if (selectedId == null) {
                        conn.commit();
                        return Optional.empty();
                    }

                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, workerId);
                        updateStmt.setString(2, selectedId);
                        int rowsUpdated = updateStmt.executeUpdate();
                        if (rowsUpdated > 0) {
                            conn.commit();
                            return findTaskById(selectedId);
                        } else {
                            // Concurrently claimed by another worker
                            conn.commit();
                            return Optional.empty();
                        }
                    }
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to claim next queued task", e);
            }
        }
    }

    @Override
    public boolean claimTask(String taskId, String workerId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");

        String sql = """
            UPDATE tasks
            SET status = 'CLAIMED', worker_id = ?
            WHERE id = ? AND status IN ('QUEUED', 'READY');
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, workerId);
                pstmt.setString(2, taskId);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to claim task: " + taskId, e);
            }
        }
    }

    @Override
    public void saveTaskEvent(TaskEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String sql = """
            INSERT INTO task_events (id, task_id, timestamp, status, message, details)
            VALUES (?, ?, ?, ?, ?, ?);
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, event.getId());
                pstmt.setString(2, event.getTaskId());
                pstmt.setString(3, event.getTimestamp().toString());
                pstmt.setString(4, event.getStatus().name());
                pstmt.setString(5, event.getMessage());
                setNullableString(pstmt, 6, event.getDetails());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save task event: " + event.getId(), e);
            }
        }
    }

    @Override
    public List<TaskEvent> findEventsByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = """
            SELECT id, task_id, timestamp, status, message, details
            FROM task_events WHERE task_id = ? ORDER BY timestamp ASC;
        """;
        List<TaskEvent> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        Instant timestamp = Instant.parse(rs.getString("timestamp"));
                        TaskStatus status = TaskStatus.valueOf(rs.getString("status"));
                        String message = rs.getString("message");
                        String details = rs.getString("details");
                        list.add(new TaskEvent(id, taskId, timestamp, status, message, details));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find events for task: " + taskId, e);
            }
        }
    }

    private static void setNullableString(PreparedStatement pstmt, int index, String value) throws SQLException {
        if (value != null && !value.trim().isEmpty()) {
            pstmt.setString(index, value.trim());
        } else {
            pstmt.setNull(index, Types.VARCHAR);
        }
    }

    private Task mapResultSetToTask(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String projectId = rs.getString("project_id");
        String sessionId = rs.getString("session_id");
        String title = rs.getString("title");
        String description = rs.getString("description");
        String statusStr = rs.getString("status");

        TaskStatus taskStatus;
        try {
            taskStatus = TaskStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            try {
                ExecutionStatus es = ExecutionStatus.valueOf(statusStr);
                taskStatus = switch (es) {
                    case IDLE -> TaskStatus.CREATED;
                    case QUEUED -> TaskStatus.QUEUED;
                    case CLAIMED -> TaskStatus.CLAIMED;
                    case RUNNING -> TaskStatus.RUNNING;
                    case WAITING_FOR_REVIEW -> TaskStatus.WAITING_FOR_REVIEW;
                    case APPROVED -> TaskStatus.APPROVED;
                    case REJECTED -> TaskStatus.REJECTED;
                    case COMMITTING -> TaskStatus.COMMITTING;
                    case COMPLETED -> TaskStatus.COMPLETED;
                    case FAILED -> TaskStatus.FAILED;
                    case CANCELLED -> TaskStatus.CANCELLED;
                    case RECOVERY_REQUIRED -> TaskStatus.RECOVERY_REQUIRED;
                };
            } catch (Exception ex) {
                taskStatus = TaskStatus.CREATED;
            }
        }

        TaskPriority priority = TaskPriority.NORMAL;
        try {
            String pStr = rs.getString("priority");
            if (pStr != null && !pStr.isBlank()) {
                priority = TaskPriority.valueOf(pStr);
            }
        } catch (Exception ignored) {}

        String agentId = null;
        try {
            agentId = rs.getString("agent_id");
        } catch (Exception ignored) {}

        String workerId = null;
        try {
            workerId = rs.getString("worker_id");
        } catch (Exception ignored) {}

        String executionResultId = null;
        try {
            executionResultId = rs.getString("execution_result_id");
        } catch (Exception ignored) {}

        String reviewId = null;
        try {
            reviewId = rs.getString("review_id");
        } catch (Exception ignored) {}

        int reworkCount = 0;
        try {
            reworkCount = rs.getInt("rework_count");
        } catch (Exception ignored) {}

        String failureReason = null;
        try {
            failureReason = rs.getString("failure_reason");
        } catch (Exception ignored) {}

        Instant createdAt = Instant.parse(rs.getString("created_at"));
        String completedAtStr = rs.getString("completed_at");
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;

        return new Task(id, projectId, sessionId, title, description, taskStatus, priority,
                agentId, workerId, executionResultId, reviewId, reworkCount, failureReason,
                createdAt, completedAt);
    }
}
