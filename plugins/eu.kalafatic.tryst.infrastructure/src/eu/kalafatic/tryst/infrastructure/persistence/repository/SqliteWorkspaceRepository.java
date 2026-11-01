package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
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
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of WorkspaceRepository.
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
            INSERT INTO workspaces (id, root_path, active_project_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                root_path = excluded.root_path,
                active_project_id = excluded.active_project_id,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String now = Instant.now().toString();
                pstmt.setString(1, workspace.getId());
                pstmt.setString(2, workspace.getRootPath().toAbsolutePath().normalize().toString());
                String activeProjId = workspace.getActiveProject().map(Project::getId).orElse(null);
                pstmt.setString(3, activeProjId);
                pstmt.setString(4, now);
                pstmt.setString(5, now);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save workspace: " + workspace.getId(), e);
            }
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, root_path, active_project_id FROM workspaces WHERE id = ?;";

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
        String sql = "SELECT id, root_path, active_project_id FROM workspaces ORDER BY updated_at DESC LIMIT 1;";

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

        Workspace workspace = new Workspace(id, rootPath);
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
}
