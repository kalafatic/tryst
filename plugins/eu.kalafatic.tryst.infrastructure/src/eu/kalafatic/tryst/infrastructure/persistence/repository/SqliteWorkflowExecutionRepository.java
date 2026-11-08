package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.workflow.WorkflowExecution;
import eu.kalafatic.tryst.domain.workflow.WorkflowExecutionRepository;
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
 * SQLite repository implementation for WorkflowExecution.
 */
public class SqliteWorkflowExecutionRepository implements WorkflowExecutionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteWorkflowExecutionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(WorkflowExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        String sql = """
            INSERT INTO workflow_executions (
                id, workflow_definition_id, project_id, status, current_node_id,
                execution_state_json, triggered_by, created_at, started_at, completed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                workflow_definition_id = excluded.workflow_definition_id,
                project_id = excluded.project_id,
                status = excluded.status,
                current_node_id = excluded.current_node_id,
                execution_state_json = excluded.execution_state_json,
                triggered_by = excluded.triggered_by,
                started_at = excluded.started_at,
                completed_at = excluded.completed_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, execution.getId());
                pstmt.setString(2, execution.getWorkflowDefinitionId());
                pstmt.setString(3, execution.getProjectId());
                pstmt.setString(4, execution.getStatus().name());
                pstmt.setString(5, execution.getCurrentNodeId());
                pstmt.setString(6, execution.getExecutionStateJson());
                pstmt.setString(7, execution.getTriggeredBy());
                pstmt.setString(8, execution.getCreatedAt().toString());
                pstmt.setString(9, execution.getStartedAt() != null ? execution.getStartedAt().toString() : null);
                pstmt.setString(10, execution.getCompletedAt() != null ? execution.getCompletedAt().toString() : null);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save workflow execution: " + execution.getId(), e);
            }
        }
    }

    @Override
    public Optional<WorkflowExecution> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, workflow_definition_id, project_id, status, current_node_id,
                   execution_state_json, triggered_by, created_at, started_at, completed_at
            FROM workflow_executions WHERE id = ?;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToExecution(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow execution: " + id, e);
            }
        }
    }

    @Override
    public List<WorkflowExecution> findByWorkflowDefinitionId(String workflowDefinitionId) {
        String sql = """
            SELECT id, workflow_definition_id, project_id, status, current_node_id,
                   execution_state_json, triggered_by, created_at, started_at, completed_at
            FROM workflow_executions WHERE workflow_definition_id = ?
            ORDER BY created_at DESC;
        """;

        List<WorkflowExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, workflowDefinitionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToExecution(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow executions by definition: " + workflowDefinitionId, e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowExecution> findByProjectId(String projectId) {
        String sql = """
            SELECT id, workflow_definition_id, project_id, status, current_node_id,
                   execution_state_json, triggered_by, created_at, started_at, completed_at
            FROM workflow_executions WHERE project_id = ?
            ORDER BY created_at DESC;
        """;

        List<WorkflowExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToExecution(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow executions by project: " + projectId, e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowExecution> findActiveExecutions() {
        String sql = """
            SELECT id, workflow_definition_id, project_id, status, current_node_id,
                   execution_state_json, triggered_by, created_at, started_at, completed_at
            FROM workflow_executions WHERE status IN ('PENDING', 'RUNNING', 'WAITING_FOR_APPROVAL', 'PAUSED')
            ORDER BY created_at ASC;
        """;

        List<WorkflowExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToExecution(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query active workflow executions", e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowExecution> findAll() {
        String sql = """
            SELECT id, workflow_definition_id, project_id, status, current_node_id,
                   execution_state_json, triggered_by, created_at, started_at, completed_at
            FROM workflow_executions
            ORDER BY created_at DESC;
        """;

        List<WorkflowExecution> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToExecution(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query all workflow executions", e);
            }
        }
        return list;
    }

    @Override
    public boolean delete(String id) {
        String sql = "DELETE FROM workflow_executions WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete workflow execution: " + id, e);
            }
        }
    }

    private WorkflowExecution mapResultSetToExecution(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String defId = rs.getString("workflow_definition_id");
        String projId = rs.getString("project_id");
        String statusStr = rs.getString("status");
        String currentNodeId = rs.getString("current_node_id");
        String stateJson = rs.getString("execution_state_json");
        String triggeredBy = rs.getString("triggered_by");
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        String startedAtStr = rs.getString("started_at");
        String completedAtStr = rs.getString("completed_at");

        WorkflowExecution.Status status = WorkflowExecution.Status.valueOf(statusStr);
        Instant startedAt = startedAtStr != null ? Instant.parse(startedAtStr) : null;
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;

        return new WorkflowExecution(id, defId, projId, triggeredBy, status, currentNodeId, stateJson, createdAt, startedAt, completedAt);
    }
}
