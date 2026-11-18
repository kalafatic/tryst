package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.repository.AiProviderRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SecretCodec;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.TrystJson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of {@link AiProviderRepository} (schema V21, table
 * {@code ai_providers} + {@code ai_provider_state}). Sensitive configuration values are
 * AES/GCM-encrypted at rest through {@link SecretCodec}; loaded descriptors keep the
 * encrypted values so the registry masks them before any exposure.
 */
public class SqliteAiProviderRepository implements AiProviderRepository {

    private final SqliteDatabaseManager databaseManager;
    private final SecretCodec secretCodec;

    public SqliteAiProviderRepository(SqliteDatabaseManager databaseManager,
            SecretCodec secretCodec) {
        this.databaseManager = Objects.requireNonNull(databaseManager,
                "databaseManager must not be null");
        this.secretCodec = Objects.requireNonNull(secretCodec, "secretCodec must not be null");
    }

    @Override
    public void save(AiProviderDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        String sql = """
            INSERT INTO ai_providers (
                id, name, provider_type, endpoint, enabled, default_model, config_json, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                provider_type = excluded.provider_type,
                endpoint = excluded.endpoint,
                enabled = excluded.enabled,
                default_model = excluded.default_model,
                config_json = excluded.config_json,
                updated_at = excluded.updated_at
        """;
        String storedConfig = TrystJson.writeStringMap(
                secretCodec.encodeMap(descriptor.configuration()));
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, descriptor.id());
                pstmt.setString(2, descriptor.name());
                pstmt.setString(3, descriptor.type());
                pstmt.setString(4, descriptor.endpoint());
                pstmt.setInt(5, descriptor.enabled() ? 1 : 0);
                pstmt.setString(6, descriptor.defaultModel());
                pstmt.setString(7, storedConfig);
                pstmt.setString(8, descriptor.createdAt().toString());
                pstmt.setString(9, descriptor.updatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save AI provider", e);
            }
        }
    }

    @Override
    public Optional<AiProviderDescriptor> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, name, provider_type, endpoint, enabled, default_model, config_json, created_at, updated_at
            FROM ai_providers WHERE id = ?
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? Optional.of(readDescriptor(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to load AI provider", e);
            }
        }
    }

    @Override
    public List<AiProviderDescriptor> findAll() {
        String sql = """
            SELECT id, name, provider_type, endpoint, enabled, default_model, config_json, created_at, updated_at
            FROM ai_providers ORDER BY name
        """;
        List<AiProviderDescriptor> result = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(readDescriptor(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to list AI providers", e);
            }
        }
        return result;
    }

    @Override
    public boolean deleteById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM ai_providers WHERE id = ?";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete AI provider", e);
            }
        }
    }

    @Override
    public void saveDefaultProviderId(String providerId) {
        saveState("default", providerId);
    }

    @Override
    public Optional<String> findDefaultProviderId() {
        String sql = "SELECT value FROM ai_provider_state WHERE key = 'default'";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to load default AI provider", e);
            }
        }
    }

    @Override
    public void clearDefaultProviderId() {
        String sql = "DELETE FROM ai_provider_state WHERE key = 'default'";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to clear default AI provider", e);
            }
        }
    }

    private void saveState(String key, String value) {
        String sql = """
            INSERT INTO ai_provider_state (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                pstmt.setString(2, value);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save AI provider state", e);
            }
        }
    }

    private AiProviderDescriptor readDescriptor(ResultSet rs) throws SQLException {
        Map<String, String> stored = TrystJson.readStringMap(rs.getString("config_json"));
        // Decrypt at rest on load: the registry holds plaintext in memory, masks on
        // exposure and persists encrypted again through this repository.
        Map<String, String> plaintext = secretCodec.decodeMap(stored);
        return new AiProviderDescriptor(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("provider_type"),
                rs.getString("endpoint"),
                rs.getInt("enabled") == 1,
                rs.getString("default_model"),
                plaintext,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    static {
        // Ensure the domain masking rule and the codec stay aligned.
        assert AiProviderRegistry.isSensitiveKey("apiKey")
                == SecretCodec.isSensitiveKey("apiKey");
    }
}
