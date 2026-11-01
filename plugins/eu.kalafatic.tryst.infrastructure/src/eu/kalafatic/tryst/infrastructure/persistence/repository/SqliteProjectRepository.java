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
 * SQLite implementation of ProjectRepository.
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
            INSERT INTO projects (id, name, location, active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                location = excluded.location,
                active = excluded.active,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                String now = Instant.now().toString();
                pstmt.setString(1, project.getId());
                pstmt.setString(2, project.getName());
                pstmt.setString(3, project.getLocation().toAbsolutePath().normalize().toString());
                pstmt.setInt(4, project.isActive() ? 1 : 0);
                pstmt.setString(5, now);
                pstmt.setString(6, now);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save project: " + project.getId(), e);
            }
        }
    }

    @Override
    public Optional<Project> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, name, location, active FROM projects WHERE id = ?;";

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
        String sql = "SELECT id, name, location, active FROM projects ORDER BY name ASC;";
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
        String sql = "SELECT id, name, location, active FROM projects WHERE active = 1 LIMIT 1;";

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
        String name = rs.getString("name");
        Path location = Path.of(rs.getString("location"));
        boolean active = rs.getInt("active") == 1;
        return new Project(id, name, location, active);
    }
}
