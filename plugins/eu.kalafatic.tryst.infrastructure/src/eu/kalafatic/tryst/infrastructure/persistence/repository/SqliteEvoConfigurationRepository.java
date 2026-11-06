package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of EvoConfigurationRepository.
 */
public class SqliteEvoConfigurationRepository implements EvoConfigurationRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteEvoConfigurationRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(EvoConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        String sql = """
            INSERT INTO evo_configurations (
                id, endpoint, auth_token, enabled, timeout_seconds, max_retries, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                endpoint = excluded.endpoint,
                auth_token = excluded.auth_token,
                enabled = excluded.enabled,
                timeout_seconds = excluded.timeout_seconds,
                max_retries = excluded.max_retries,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, configuration.getId());
                pstmt.setString(2, configuration.getEndpoint());
                pstmt.setString(3, configuration.getAuthToken());
                pstmt.setInt(4, configuration.isEnabled() ? 1 : 0);
                pstmt.setInt(5, configuration.getTimeoutSeconds());
                pstmt.setInt(6, configuration.getMaxRetries());
                pstmt.setString(7, configuration.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save EVO configuration", e);
            }
        }
    }

    @Override
    public Optional<EvoConfiguration> getConfiguration() {
        String sql = """
            SELECT id, endpoint, auth_token, enabled, timeout_seconds, max_retries, updated_at
            FROM evo_configurations
            ORDER BY updated_at DESC
            LIMIT 1;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new EvoConfiguration(
                            rs.getString("id"),
                            rs.getString("endpoint"),
                            rs.getString("auth_token"),
                            rs.getInt("enabled") == 1,
                            rs.getInt("timeout_seconds"),
                            rs.getInt("max_retries"),
                            Instant.parse(rs.getString("updated_at"))
                    ));
                }
                return Optional.empty();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query EVO configuration", e);
            }
        }
    }
}
