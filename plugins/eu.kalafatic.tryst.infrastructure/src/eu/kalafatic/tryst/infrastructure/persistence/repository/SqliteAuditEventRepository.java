package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.AuditEvent;
import eu.kalafatic.tryst.domain.repository.AuditEventRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SqliteAuditEventRepository implements AuditEventRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteAuditEventRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String sql = """
            INSERT INTO audit_events (id, event_time, actor_id, action, target_type, target_id, project_id, task_id, outcome, details_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, event.id());
                pstmt.setString(2, event.eventTime().toString());
                pstmt.setString(3, event.actorId());
                pstmt.setString(4, event.action());
                pstmt.setString(5, event.targetType());
                pstmt.setString(6, event.targetId());
                pstmt.setString(7, event.projectId());
                pstmt.setString(8, event.taskId());
                pstmt.setString(9, event.outcome());
                pstmt.setString(10, event.detailsJson());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save audit event " + event.id(), e);
            }
        }
    }

    @Override
    public List<AuditEvent> findRecent(int limit) {
        return query(null, null, null, null, null, Math.max(1, limit), 0);
    }

    @Override
    public List<AuditEvent> query(String actorId, String action, String projectId, Instant from, Instant to, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (actorId != null && !actorId.isBlank()) {
            sql.append(" AND actor_id = ?");
            params.add(actorId.trim());
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" AND project_id = ?");
            params.add(projectId.trim());
        }
        if (from != null) {
            sql.append(" AND event_time >= ?");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND event_time <= ?");
            params.add(to.toString());
        }

        sql.append(" ORDER BY event_time DESC LIMIT ? OFFSET ?;");
        params.add(Math.min(1000, Math.max(1, limit)));
        params.add(Math.max(0, offset));

        List<AuditEvent> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    pstmt.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query audit events", e);
            }
        }
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM audit_events;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to count audit events", e);
            }
        }
    }

    private AuditEvent mapRow(ResultSet rs) throws SQLException {
        return new AuditEvent(
                rs.getString("id"),
                Instant.parse(rs.getString("event_time")),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("project_id"),
                rs.getString("task_id"),
                rs.getString("outcome"),
                rs.getString("details_json")
        );
    }
}
