package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.nio.file.Path;
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
 * SQLite implementation of ProjectRepository with owner_id support.
 */
public class SqliteProjectRepository implements ProjectRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteProjectRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        String sql = """
            INSERT INTO projects (id, owner_id, name, description, location, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                owner_id = excluded.owner_id,
                name = excluded.name,
                description = excluded.description,
                location = excluded.location,
                active = excluded.active,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String now = Instant.now().toString();
                pstmt.setString(1, project.getId());
                pstmt.setString(2, project.getOwnerId() != null ? project.getOwnerId() : Project.DEFAULT_OWNER_ID);
                pstmt.setString(3, project.getName());
                pstmt.setString(4, project.getDescription() != null ? project.getDescription() : "");
                pstmt.setString(5, project.getLocation().toAbsolutePath().normalize().toString());
                pstmt.setInt(6, project.isActive() ? 1 : 0);
                pstmt.setString(7, now);
                pstmt.setString(8, now);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save project: " + project.getId(), e);
            }
        }
    }

    @Override
    public Optional<Project> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, owner_id, name, description, location, active FROM projects WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToProject(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find project by id: " + id, e);
            }
        }
    }

    @Override
    public List<Project> findAll() {
        String sql = "SELECT id, owner_id, name, description, location, active FROM projects ORDER BY name ASC;";
        List<Project> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToProject(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all projects", e);
            }
        }
    }

    @Override
    public Optional<Project> findActive() {
        String sql = "SELECT id, owner_id, name, description, location, active FROM projects WHERE active = 1 LIMIT 1;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToProject(rs));
                }
                return Optional.empty();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find active project", e);
            }
        }
    }

    @Override
    public List<Project> findByOwnerId(String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        String sql = "SELECT id, owner_id, name, description, location, active FROM projects WHERE owner_id = ? ORDER BY name ASC;";
        List<Project> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, ownerId.trim());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToProject(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find projects by owner: " + ownerId, e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM projects WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete project: " + id, e);
            }
        }
    }

    private Project mapResultSetToProject(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String ownerId = rs.getString("owner_id");
        if (ownerId == null || ownerId.trim().isEmpty()) {
            ownerId = Project.DEFAULT_OWNER_ID;
        }
        String name = rs.getString("name");
        String description = rs.getString("description");
        Path location = Path.of(rs.getString("location"));
        boolean active = rs.getInt("active") == 1;
        return new Project(id, ownerId, name, description != null ? description : "", location,
                eu.kalafatic.tryst.domain.model.ProjectStatus.ACTIVE, active, Instant.now(), Instant.now());
    }
}
