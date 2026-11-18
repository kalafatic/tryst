package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.DCodeConfiguration;
import eu.kalafatic.tryst.domain.repository.DCodeConfigurationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of DCodeConfigurationRepository (Phase 6).
 */
public class SqliteDCodeConfigurationRepository implements DCodeConfigurationRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteDCodeConfigurationRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager,
                "databaseManager must not be null");
    }

    @Override
    public void save(DCodeConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        String sql = """
            INSERT INTO dcode_configurations (
                id, endpoint, provider_id, model, enabled, timeout_seconds, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                endpoint = excluded.endpoint,
                provider_id = excluded.provider_id,
                model = excluded.model,
                enabled = excluded.enabled,
                timeout_seconds = excluded.timeout_seconds,
                updated_at = excluded.updated_at
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, configuration.getId());
                pstmt.setString(2, configuration.getEndpoint());
                pstmt.setString(3, configuration.getProviderId());
                pstmt.setString(4, configuration.getModel());
                pstmt.setInt(5, configuration.isEnabled() ? 1 : 0);
                pstmt.setInt(6, configuration.getTimeoutSeconds());
                pstmt.setString(7, configuration.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to save DCode configuration", e);
            }
        }
    }

    @Override
    public Optional<DCodeConfiguration> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, endpoint, provider_id, model, enabled, timeout_seconds, updated_at
            FROM dcode_configurations
            WHERE id = ?
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new DCodeConfiguration(
                            rs.getString("id"),
                            rs.getString("endpoint"),
                            rs.getString("provider_id"),
                            rs.getString("model"),
                            rs.getInt("enabled") == 1,
                            rs.getInt("timeout_seconds"),
                            Instant.parse(rs.getString("updated_at"))
                    ));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Failed to load DCode configuration", e);
            }
        }
    }
}
