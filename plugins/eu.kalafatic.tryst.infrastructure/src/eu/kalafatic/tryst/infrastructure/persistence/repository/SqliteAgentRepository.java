package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.AgentConfiguration;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
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
 * SQLite implementation of AgentRepository.
 */
public class SqliteAgentRepository implements AgentRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteAgentRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(AgentConfiguration agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        String sql = """
            INSERT INTO agents (id, name, model_name, system_prompt, configuration_json, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                model_name = excluded.model_name,
                system_prompt = excluded.system_prompt,
                configuration_json = excluded.configuration_json,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, agent.getId());
                pstmt.setString(2, agent.getName());
                pstmt.setString(3, agent.getModelName());
                pstmt.setString(4, agent.getSystemPrompt());
                pstmt.setString(5, agent.getConfigurationJson());
                pstmt.setString(6, agent.getCreatedAt().toString());
                pstmt.setString(7, agent.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save agent configuration: " + agent.getId(), e);
            }
        }
    }

    @Override
    public Optional<AgentConfiguration> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, name, model_name, system_prompt, configuration_json, created_at, updated_at FROM agents WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToAgent(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find agent configuration by id: " + id, e);
            }
        }
    }

    @Override
    public Optional<AgentConfiguration> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        String sql = "SELECT id, name, model_name, system_prompt, configuration_json, created_at, updated_at FROM agents WHERE name = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, name);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToAgent(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find agent configuration by name: " + name, e);
            }
        }
    }

    @Override
    public List<AgentConfiguration> findAll() {
        String sql = "SELECT id, name, model_name, system_prompt, configuration_json, created_at, updated_at FROM agents ORDER BY name ASC;";
        List<AgentConfiguration> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToAgent(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all agent configurations", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM agents WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete agent configuration: " + id, e);
            }
        }
    }

    private AgentConfiguration mapResultSetToAgent(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String modelName = rs.getString("model_name");
        String systemPrompt = rs.getString("system_prompt");
        String configJson = rs.getString("configuration_json");
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        Instant updatedAt = Instant.parse(rs.getString("updated_at"));
        return new AgentConfiguration(id, name, modelName, systemPrompt, configJson, createdAt, updatedAt);
    }
}
