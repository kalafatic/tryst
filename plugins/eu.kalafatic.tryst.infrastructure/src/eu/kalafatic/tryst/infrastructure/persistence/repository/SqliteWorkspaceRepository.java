package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of WorkspaceRepository with support for
 * multi-user isolation, task binding, repository branches, and persistent lifecycle statuses.
 */
public class SqliteWorkspaceRepository implements WorkspaceRepository {

    private final SqliteDatabaseManager databaseManager;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;

    public SqliteWorkspaceRepository(SqliteDatabaseManager databaseManager,
                                     ProjectRepository projectRepository,
                                     TaskRepository taskRepository) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository must not be null");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
    }

    @Override
    public void save(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        String sql = """
            INSERT INTO workspaces (id, root_path, active_project_id, owner_id, task_id, status, repository_url, branch, metadata_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                root_path = excluded.root_path,
                active_project_id = excluded.active_project_id,
                owner_id = excluded.owner_id,
                task_id = excluded.task_id,
                status = excluded.status,
                repository_url = excluded.repository_url,
                branch = excluded.branch,
                metadata_json = excluded.metadata_json,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String now = Instant.now().toString();
                String createdAt = workspace.getCreatedAt() != null ? workspace.getCreatedAt().toString() : now;
                pstmt.setString(1, workspace.getId());
                pstmt.setString(2, workspace.getRootPath().toAbsolutePath().normalize().toString());
                String activeProjId = workspace.getActiveProject().map(Project::getId).orElse(null);
                pstmt.setString(3, activeProjId);
                pstmt.setString(4, workspace.getOwnerId() != null ? workspace.getOwnerId() : "default-user");
                pstmt.setString(5, workspace.getTaskId());
                pstmt.setString(6, workspace.getStatus() != null ? workspace.getStatus().name() : WorkspaceStatus.ACTIVE.name());
                pstmt.setString(7, workspace.getRepositoryUrl());
                pstmt.setString(8, workspace.getBranch() != null ? workspace.getBranch() : "master");
                pstmt.setString(9, serializeMetadata(workspace.getMetadata()));
                pstmt.setString(10, createdAt);
                pstmt.setString(11, now);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save workspace: " + workspace.getId(), e);
            }
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT * FROM workspaces WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(populateWorkspace(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find workspace by id: " + id, e);
            }
        }
    }

    @Override
    public Optional<Workspace> findActive() {
        String sql = "SELECT * FROM workspaces WHERE status NOT IN ('ARCHIVED', 'CLEANUP_PENDING') ORDER BY updated_at DESC LIMIT 1;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(populateWorkspace(rs));
                }
                return Optional.empty();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find active workspace", e);
            }
        }
    }

    @Override
    public Optional<Workspace> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT * FROM workspaces WHERE task_id = ? ORDER BY updated_at DESC LIMIT 1;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(populateWorkspace(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find workspace for task: " + taskId, e);
            }
        }
    }

    @Override
    public List<Workspace> findByOwnerId(String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        String sql = "SELECT * FROM workspaces WHERE owner_id = ? ORDER BY created_at DESC;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, ownerId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    List<Workspace> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(populateWorkspace(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find workspaces for owner: " + ownerId, e);
            }
        }
    }

    @Override
    public List<Workspace> findByStatus(WorkspaceStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        String sql = "SELECT * FROM workspaces WHERE status = ? ORDER BY created_at DESC;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, status.name());
                try (ResultSet rs = pstmt.executeQuery()) {
                    List<Workspace> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(populateWorkspace(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find workspaces by status: " + status, e);
            }
        }
    }

    @Override
    public List<Workspace> findAll() {
        String sql = "SELECT * FROM workspaces ORDER BY created_at DESC;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                List<Workspace> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(populateWorkspace(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all workspaces", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM workspaces WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete workspace: " + id, e);
            }
        }
    }

    private Workspace populateWorkspace(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        Path rootPath = Path.of(rs.getString("root_path"));
        String activeProjId = rs.getString("active_project_id");

        String ownerId = getColumnOrDefault(rs, "owner_id", "default-user");
        String taskId = getColumnOrDefault(rs, "task_id", null);
        String statusStr = getColumnOrDefault(rs, "status", WorkspaceStatus.ACTIVE.name());
        WorkspaceStatus status;
        try {
            status = WorkspaceStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = WorkspaceStatus.ACTIVE;
        }

        String repoUrl = getColumnOrDefault(rs, "repository_url", null);
        String branch = getColumnOrDefault(rs, "branch", "master");
        String metadataJson = getColumnOrDefault(rs, "metadata_json", null);

        Instant createdAt = parseInstant(getColumnOrDefault(rs, "created_at", null));
        Instant updatedAt = parseInstant(getColumnOrDefault(rs, "updated_at", null));

        Map<String, String> metadata = deserializeMetadata(metadataJson);

        Workspace workspace = new Workspace(id, rootPath, ownerId, taskId, repoUrl, branch, status,
                createdAt != null ? createdAt : Instant.now(),
                updatedAt != null ? updatedAt : Instant.now(),
                metadata);

        for (Project project : projectRepository.findAll()) {
            workspace.addProject(project);
        }
        if (activeProjId != null) {
            workspace.setActiveProject(activeProjId);
        }
        for (var task : taskRepository.findAll()) {
            workspace.addTask(task);
        }

        return workspace;
    }

    private String getColumnOrDefault(ResultSet rs, String colName, String defVal) {
        try {
            String val = rs.getString(colName);
            return val != null ? val : defVal;
        } catch (SQLException e) {
            return defVal;
        }
    }

    private Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private String serializeMetadata(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private Map<String, String> deserializeMetadata(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank() || !json.startsWith("{") || !json.endsWith("}")) {
            return map;
        }
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) {
            return map;
        }
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        for (String pair : pairs) {
            String[] kv = pair.split(":(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if (kv.length == 2) {
                String k = unescapeJson(kv[0].trim());
                String v = unescapeJson(kv[1].trim());
                map.put(k, v);
            }
        }
        return map;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
    }
}
