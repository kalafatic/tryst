package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRepository;
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
 * SQLite implementation of the connected-application repository (V17 tables).
 */
public class SqliteConnectedApplicationRepository implements ConnectedApplicationRepository {

    private static final String SELECTED_KEY = "selected_application_id";

    private final SqliteDatabaseManager databaseManager;

    public SqliteConnectedApplicationRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void saveDescriptor(ConnectedApplicationDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        String sql = """
            INSERT INTO connected_applications (
                id, name, application_type, capabilities, enabled, status, status_message, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                application_type = excluded.application_type,
                capabilities = excluded.capabilities,
                enabled = excluded.enabled,
                status = excluded.status,
                status_message = excluded.status_message,
                updated_at = excluded.updated_at;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, descriptor.getId());
                pstmt.setString(2, descriptor.getName());
                pstmt.setString(3, descriptor.getApplicationType());
                pstmt.setString(4, String.join(",", descriptor.getCapabilities()));
                pstmt.setInt(5, descriptor.isEnabled() ? 1 : 0);
                pstmt.setString(6, descriptor.getStatus().name());
                pstmt.setString(7, descriptor.getStatusMessage());
                pstmt.setString(8, descriptor.getCreatedAt().toString());
                pstmt.setString(9, descriptor.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save connected application descriptor: " + descriptor.getId(), e);
            }
        }
    }

    @Override
    public void deleteDescriptor(String applicationId) {
        if (applicationId == null) return;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "DELETE FROM connected_applications WHERE id = ?")) {
                pstmt.setString(1, applicationId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete connected application descriptor: " + applicationId, e);
            }
        }
    }

    @Override
    public Optional<ConnectedApplicationDescriptor> findDescriptor(String applicationId) {
        if (applicationId == null) return Optional.empty();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT * FROM connected_applications WHERE id = ?")) {
                pstmt.setString(1, applicationId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapDescriptor(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query connected application: " + applicationId, e);
            }
        }
    }

    @Override
    public List<ConnectedApplicationDescriptor> findAllDescriptors() {
        List<ConnectedApplicationDescriptor> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT * FROM connected_applications ORDER BY name ASC");
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapDescriptor(rs));
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query connected applications", e);
            }
        }
        return list;
    }

    @Override
    public Optional<String> findSelectedApplicationId() {
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT value FROM connected_application_state WHERE key = ?")) {
                pstmt.setString(1, SELECTED_KEY);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("value"));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query selected application", e);
            }
        }
    }

    @Override
    public void saveSelectedApplicationId(String applicationId) {
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO connected_application_state (key, value) VALUES (?, ?)")) {
                pstmt.setString(1, SELECTED_KEY);
                pstmt.setString(2, applicationId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to persist selected application", e);
            }
        }
    }

    @Override
    public void clearSelectedApplicationId() {
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "DELETE FROM connected_application_state WHERE key = ?")) {
                pstmt.setString(1, SELECTED_KEY);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to clear selected application", e);
            }
        }
    }

    private ConnectedApplicationDescriptor mapDescriptor(ResultSet rs) throws SQLException {
        List<String> capabilities = new ArrayList<>();
        String capabilitiesRaw = rs.getString("capabilities");
        if (capabilitiesRaw != null && !capabilitiesRaw.isBlank()) {
            for (String c : capabilitiesRaw.split(",")) {
                if (!c.isBlank()) {
                    capabilities.add(c.trim());
                }
            }
        }
        return new ConnectedApplicationDescriptor(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("application_type"),
                capabilities,
                rs.getInt("enabled") != 0,
                ConnectedApplicationDescriptor.ApplicationStatus.valueOf(rs.getString("status")),
                rs.getString("status_message"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
