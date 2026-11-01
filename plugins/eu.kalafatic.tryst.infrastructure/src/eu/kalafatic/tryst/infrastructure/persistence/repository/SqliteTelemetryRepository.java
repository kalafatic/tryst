package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.TelemetrySnapshot;
import eu.kalafatic.tryst.domain.repository.TelemetryRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SqliteTelemetryRepository implements TelemetryRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTelemetryRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(TelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        String sql = """
            INSERT INTO telemetry_snapshots (id, timestamp, cpu_system, cpu_process, memory_heap_used, memory_heap_max,
                memory_system_used, disk_workspace_used, disk_workspace_total, active_sessions, queued_tasks, running_tasks, ai_latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, snapshot.id());
                pstmt.setString(2, snapshot.timestamp().toString());
                pstmt.setDouble(3, snapshot.cpuSystem());
                pstmt.setDouble(4, snapshot.cpuProcess());
                pstmt.setLong(5, snapshot.memoryHeapUsed());
                pstmt.setLong(6, snapshot.memoryHeapMax());
                pstmt.setLong(7, snapshot.memorySystemUsed());
                pstmt.setLong(8, snapshot.diskWorkspaceUsed());
                pstmt.setLong(9, snapshot.diskWorkspaceTotal());
                pstmt.setInt(10, snapshot.activeSessions());
                pstmt.setInt(11, snapshot.queuedTasks());
                pstmt.setInt(12, snapshot.runningTasks());
                pstmt.setDouble(13, snapshot.aiLatencyMs());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save telemetry snapshot " + snapshot.id(), e);
            }
        }
    }

    @Override
    public List<TelemetrySnapshot> findSince(Instant since, int limit) {
        String sql = "SELECT * FROM telemetry_snapshots WHERE timestamp >= ? ORDER BY timestamp ASC LIMIT ?;";
        List<TelemetrySnapshot> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, since != null ? since.toString() : Instant.EPOCH.toString());
                pstmt.setInt(2, Math.max(1, limit));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query telemetry snapshots since " + since, e);
            }
        }
    }

    @Override
    public List<TelemetrySnapshot> findRecent(int limit) {
        String sql = "SELECT * FROM telemetry_snapshots ORDER BY timestamp DESC LIMIT ?;";
        List<TelemetrySnapshot> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, Math.max(1, limit));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query recent telemetry snapshots", e);
            }
        }
    }

    @Override
    public void deleteOlderThan(Instant cutoff) {
        String sql = "DELETE FROM telemetry_snapshots WHERE timestamp < ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, cutoff.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to purge old telemetry snapshots", e);
            }
        }
    }

    private TelemetrySnapshot mapRow(ResultSet rs) throws SQLException {
        return new TelemetrySnapshot(
                rs.getString("id"),
                Instant.parse(rs.getString("timestamp")),
                rs.getDouble("cpu_system"),
                rs.getDouble("cpu_process"),
                rs.getLong("memory_heap_used"),
                rs.getLong("memory_heap_max"),
                rs.getLong("memory_system_used"),
                rs.getLong("disk_workspace_used"),
                rs.getLong("disk_workspace_total"),
                rs.getInt("active_sessions"),
                rs.getInt("queued_tasks"),
                rs.getInt("running_tasks"),
                rs.getDouble("ai_latency_ms")
        );
    }
}
