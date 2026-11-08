package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.workflow.WorkflowDefinition;
import eu.kalafatic.tryst.domain.workflow.WorkflowDefinitionRepository;
import eu.kalafatic.tryst.domain.workflow.WorkflowEdge;
import eu.kalafatic.tryst.domain.workflow.WorkflowNode;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite repository implementation for WorkflowDefinition.
 */
public class SqliteWorkflowDefinitionRepository implements WorkflowDefinitionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteWorkflowDefinitionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        String sql = """
            INSERT INTO workflow_definitions (
                id, name, description, project_id, owner_id, version, is_template,
                graph_json, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                description = excluded.description,
                project_id = excluded.project_id,
                owner_id = excluded.owner_id,
                version = excluded.version,
                is_template = excluded.is_template,
                graph_json = excluded.graph_json,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, definition.getId());
                pstmt.setString(2, definition.getName());
                pstmt.setString(3, definition.getDescription());
                pstmt.setString(4, definition.getProjectId());
                pstmt.setString(5, definition.getOwnerId());
                pstmt.setInt(6, definition.getVersion());
                pstmt.setInt(7, definition.isTemplate() ? 1 : 0);
                pstmt.setString(8, definition.getGraphJson());
                pstmt.setString(9, definition.getCreatedAt().toString());
                pstmt.setString(10, definition.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save workflow definition: " + definition.getId(), e);
            }
        }
    }

    @Override
    public Optional<WorkflowDefinition> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, name, description, project_id, owner_id, version, is_template,
                   graph_json, created_at, updated_at
            FROM workflow_definitions WHERE id = ?;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToDefinition(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow definition: " + id, e);
            }
        }
    }

    @Override
    public List<WorkflowDefinition> findByProjectId(String projectId) {
        String sql = """
            SELECT id, name, description, project_id, owner_id, version, is_template,
                   graph_json, created_at, updated_at
            FROM workflow_definitions WHERE project_id = ?
            ORDER BY updated_at DESC;
        """;

        List<WorkflowDefinition> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToDefinition(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow definitions for project: " + projectId, e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowDefinition> findByOwnerId(String ownerId) {
        String sql = """
            SELECT id, name, description, project_id, owner_id, version, is_template,
                   graph_json, created_at, updated_at
            FROM workflow_definitions WHERE owner_id = ?
            ORDER BY updated_at DESC;
        """;

        List<WorkflowDefinition> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, ownerId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToDefinition(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query workflow definitions for owner: " + ownerId, e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowDefinition> findAll() {
        String sql = """
            SELECT id, name, description, project_id, owner_id, version, is_template,
                   graph_json, created_at, updated_at
            FROM workflow_definitions
            ORDER BY updated_at DESC;
        """;

        List<WorkflowDefinition> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDefinition(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query all workflow definitions", e);
            }
        }
        return list;
    }

    @Override
    public List<WorkflowDefinition> findTemplates() {
        String sql = """
            SELECT id, name, description, project_id, owner_id, version, is_template,
                   graph_json, created_at, updated_at
            FROM workflow_definitions WHERE is_template = 1
            ORDER BY name ASC;
        """;

        List<WorkflowDefinition> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDefinition(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query template workflow definitions", e);
            }
        }
        return list;
    }

    @Override
    public boolean delete(String id) {
        String sql = "DELETE FROM workflow_definitions WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete workflow definition: " + id, e);
            }
        }
    }

    private WorkflowDefinition mapResultSetToDefinition(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String desc = rs.getString("description");
        String projId = rs.getString("project_id");
        String ownerId = rs.getString("owner_id");
        int version = rs.getInt("version");
        boolean isTemplate = rs.getInt("is_template") == 1;
        String graphJson = rs.getString("graph_json");
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        Instant updatedAt = Instant.parse(rs.getString("updated_at"));

        // Minimal empty node/edge lists; detailed node parsing from graphJson when deserializing in services
        List<WorkflowNode> nodes = Collections.emptyList();
        List<WorkflowEdge> edges = Collections.emptyList();

        return new WorkflowDefinition(id, name, desc, projId, ownerId, version, isTemplate, nodes, edges, graphJson, createdAt, updatedAt);
    }
}
