package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.repository.ExecutionConfigurationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.TrystJson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of {@link ExecutionConfigurationRepository} (schema V21,
 * tables {@code execution_configurations} and {@code task_execution_snapshots}).
 * Configuration JSON never contains secrets.
 */
public class SqliteExecutionConfigurationRepository
        implements ExecutionConfigurationRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteExecutionConfigurationRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager,
                "databaseManager must not be null");
    }

    @Override
    public void save(String scope, String scopeKey, ExecutionConfiguration configuration) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(scopeKey, "scopeKey must not be null");
        String sql = """
            INSERT INTO execution_configurations (scope, scope_key, config_json, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(scope, scope_key) DO UPDATE SET
                config_json = excluded.config_json,
                updated_at = excluded.updated_at
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, scope);
                pstmt.setString(2, scopeKey);
                pstmt.setString(3, writeConfiguration(configuration));
                pstmt.setString(4, Instant.now().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to save execution configuration", e);
            }
        }
    }

    @Override
    public Optional<ExecutionConfiguration> find(String scope, String scopeKey) {
        String sql = """
            SELECT config_json FROM execution_configurations WHERE scope = ? AND scope_key = ?
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, scope);
                pstmt.setString(2, scopeKey);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? Optional.of(readConfiguration(rs.getString(1), scope))
                            : Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to load execution configuration", e);
            }
        }
    }

    @Override
    public void delete(String scope, String scopeKey) {
        String sql = "DELETE FROM execution_configurations WHERE scope = ? AND scope_key = ?";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, scope);
                pstmt.setString(2, scopeKey);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to delete execution configuration", e);
            }
        }
    }

    @Override
    public java.util.List<ScopedConfiguration> findByScope(String scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        String sql = """
            SELECT scope_key, config_json FROM execution_configurations
            WHERE scope = ? ORDER BY scope_key
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, scope);
                try (ResultSet rs = pstmt.executeQuery()) {
                    java.util.List<ScopedConfiguration> result = new java.util.ArrayList<>();
                    while (rs.next()) {
                        result.add(new ScopedConfiguration(scope, rs.getString(1),
                                readConfiguration(rs.getString(2), scope)));
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to list execution configurations for scope " + scope, e);
            }
        }
    }

    @Override
    public void saveSnapshot(String taskId, ExecutionConfiguration resolved) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = """
            INSERT INTO task_execution_snapshots (task_id, config_json, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                config_json = excluded.config_json,
                created_at = excluded.created_at
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                pstmt.setString(2, writeConfiguration(resolved));
                pstmt.setString(3, Instant.now().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to save task execution snapshot", e);
            }
        }
    }

    @Override
    public Optional<ExecutionConfiguration> findSnapshot(String taskId) {
        String sql = "SELECT config_json FROM task_execution_snapshots WHERE task_id = ?";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? Optional.of(readConfiguration(rs.getString(1),
                            ExecutionConfiguration.SOURCE_EXECUTION)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to load task execution snapshot", e);
            }
        }
    }

    private static String writeConfiguration(ExecutionConfiguration configuration) {
        // Flat string-map encoding with prefixed keys: scalar fields plain, map entries
        // under param./limit./harness. prefixes, tool permissions as a JSON array string.
        Map<String, String> flat = new LinkedHashMap<>();
        flat.put("harnessId", nullToEmpty(configuration.harnessId()));
        flat.put("providerId", nullToEmpty(configuration.providerId()));
        flat.put("modelId", nullToEmpty(configuration.modelId()));
        flat.put("agentProfileId", nullToEmpty(configuration.agentProfileId()));
        flat.put("source", configuration.source());
        configuration.executionParameters().forEach((k, v) -> flat.put("param." + k, v));
        flat.put("toolPermissions", TrystJson.writeStringList(configuration.toolPermissions()));
        configuration.resourceLimits().forEach((k, v) -> flat.put("limit." + k, v));
        configuration.harnessSettings().forEach((k, v) -> flat.put("harness." + k, v));
        return TrystJson.writeStringMap(flat);
    }

    private static ExecutionConfiguration readConfiguration(String json,
            String fallbackSource) {
        Map<String, String> flat = TrystJson.readStringMap(json);
        Map<String, String> parameters = new LinkedHashMap<>();
        Map<String, String> limits = new LinkedHashMap<>();
        Map<String, String> settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            if (entry.getKey().startsWith("param.")) {
                parameters.put(entry.getKey().substring("param.".length()), entry.getValue());
            } else if (entry.getKey().startsWith("limit.")) {
                limits.put(entry.getKey().substring("limit.".length()), entry.getValue());
            } else if (entry.getKey().startsWith("harness.")) {
                settings.put(entry.getKey().substring("harness.".length()), entry.getValue());
            }
        }
        return new ExecutionConfiguration(
                flat.getOrDefault("harnessId", ""),
                flat.getOrDefault("providerId", ""),
                flat.getOrDefault("modelId", ""),
                flat.getOrDefault("agentProfileId", ""),
                parameters,
                TrystJson.readStringList(flat.getOrDefault("toolPermissions", "[]")),
                limits,
                settings,
                flat.getOrDefault("source", fallbackSource));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
