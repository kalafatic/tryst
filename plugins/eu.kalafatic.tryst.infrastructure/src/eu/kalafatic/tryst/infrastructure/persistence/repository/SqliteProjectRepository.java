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

    private static final String PROJECT_COLUMNS = "id, owner_id, name, description, location, active, "
            + "repository_url, default_branch, build_command, test_command, instructions, created_at, updated_at";

    @Override
    public void save(Project project) {
        Objects.requireNonNull(project, "project must not be null");
        String sql = """
            INSERT INTO projects (id, owner_id, name, description, location, active,
                                  repository_url, default_branch, build_command, test_command,
                                  instructions, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                owner_id = excluded.owner_id,
                name = excluded.name,
                description = excluded.description,
                location = excluded.location,
                active = excluded.active,
                repository_url = excluded.repository_url,
                default_branch = excluded.default_branch,
                build_command = excluded.build_command,
                test_command = excluded.test_command,
                instructions = excluded.instructions,
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
                pstmt.setString(7, project.getRepositoryUrl() != null ? project.getRepositoryUrl() : "");
                pstmt.setString(8, project.getDefaultBranch() != null ? project.getDefaultBranch() : "main");
                pstmt.setString(9, project.getBuildCommand() != null ? project.getBuildCommand() : "");
                pstmt.setString(10, project.getTestCommand() != null ? project.getTestCommand() : "");
                pstmt.setString(11, project.getInstructions() != null ? project.getInstructions() : "");
                pstmt.setString(12, project.getCreatedAt().toString());
                pstmt.setString(13, now);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save project: " + project.getId(), e);
            }
        }
    }

    @Override
    public Optional<Project> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE id = ?;";

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
        String sql = "SELECT " + PROJECT_COLUMNS + " FROM projects ORDER BY name ASC;";
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
        String sql = "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE active = 1 LIMIT 1;";

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
        String sql = "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE owner_id = ? ORDER BY name ASC;";
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

        String repositoryUrl = readNullableColumn(rs, "repository_url");
        String defaultBranch = readNullableColumn(rs, "default_branch");
        if (defaultBranch == null || defaultBranch.isBlank()) defaultBranch = "main";
        String buildCommand = readNullableColumn(rs, "build_command");
        String testCommand = readNullableColumn(rs, "test_command");
        String instructions = readNullableColumn(rs, "instructions");

        Instant createdAt;
        Instant updatedAt;
        try {
            createdAt = Instant.parse(rs.getString("created_at"));
        } catch (Exception e) {
            createdAt = Instant.now();
        }
        try {
            updatedAt = Instant.parse(rs.getString("updated_at"));
        } catch (Exception e) {
            updatedAt = Instant.now();
        }

        return new Project(id, ownerId, name, description != null ? description : "", location,
                eu.kalafatic.tryst.domain.model.ProjectStatus.ACTIVE, active, createdAt, updatedAt,
                repositoryUrl, defaultBranch, buildCommand, testCommand, instructions);
    }

    private static String readNullableColumn(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return "";
        }
    }
}
