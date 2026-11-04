package eu.kalafatic.tryst.infrastructure.persistence;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.service.DataRetentionService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * SQLite implementation of RetentionStore executing batched cleanup
 * across audit events, telemetry snapshots, task events, and user sessions.
 */
public class SqliteRetentionStore implements DataRetentionService.RetentionStore {

    private final SqliteDatabaseManager databaseManager;

    public SqliteRetentionStore(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public int purgeExpiredUserSessions(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        // Purge sessions where expires_at < cutoff OR (revoked = 1 AND last_activity < cutoff)
        String sql = "DELETE FROM user_sessions WHERE expires_at < ? OR (revoked = 1 AND last_activity < ?);";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, cutoff.toString());
                pstmt.setString(2, cutoff.toString());
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to purge expired user sessions", e);
            }
        }
    }

    @Override
    public int purgeTelemetrySnapshots(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        String sql = "DELETE FROM telemetry_snapshots WHERE timestamp < ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, cutoff.toString());
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to purge telemetry snapshots", e);
            }
        }
    }

    @Override
    public int purgeAuditEvents(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        String sql = "DELETE FROM audit_events WHERE event_time < ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, cutoff.toString());
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to purge audit events", e);
            }
        }
    }

    @Override
    public int purgeTaskEvents(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        String sql = "DELETE FROM task_events WHERE timestamp < ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, cutoff.toString());
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to purge task events", e);
            }
        }
    }
}
