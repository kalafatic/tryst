package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.repository.NotificationRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SqliteNotificationRepository implements NotificationRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteNotificationRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    private long getNextSequence(Connection conn) throws SQLException {
        try {
            String sql = "SELECT COALESCE(MAX(seq_num), 0) + 1 FROM notifications;";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 1L;
            }
        } catch (SQLException e) {
            return System.currentTimeMillis();
        }
    }

    @Override
    public void save(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        String sql = """
            INSERT INTO notifications (id, user_id, type, title, message, reference_type, reference_id, is_read, created_at, dedup_key, seq_num)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET is_read = excluded.is_read;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try {
                long seq = notification.getSequenceNumber() > 0 ? notification.getSequenceNumber() : getNextSequence(conn);
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, notification.getId());
                    pstmt.setString(2, notification.getUserId());
                    pstmt.setString(3, notification.getType());
                    pstmt.setString(4, notification.getTitle());
                    pstmt.setString(5, notification.getMessage());
                    pstmt.setString(6, notification.getReferenceType());
                    pstmt.setString(7, notification.getReferenceId());
                    pstmt.setInt(8, notification.isRead() ? 1 : 0);
                    pstmt.setString(9, notification.getCreatedAt().toString());
                    pstmt.setString(10, notification.getDedupKey());
                    pstmt.setLong(11, seq);
                    pstmt.executeUpdate();
                }
            } catch (SQLException e) {
                // If table doesn't have dedup_key / seq_num yet (e.g. during pre-migration tests)
                String fallbackSql = """
                    INSERT INTO notifications (id, user_id, type, title, message, reference_type, reference_id, is_read, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET is_read = excluded.is_read;
                """;
                try (PreparedStatement pstmt = conn.prepareStatement(fallbackSql)) {
                    pstmt.setString(1, notification.getId());
                    pstmt.setString(2, notification.getUserId());
                    pstmt.setString(3, notification.getType());
                    pstmt.setString(4, notification.getTitle());
                    pstmt.setString(5, notification.getMessage());
                    pstmt.setString(6, notification.getReferenceType());
                    pstmt.setString(7, notification.getReferenceId());
                    pstmt.setInt(8, notification.isRead() ? 1 : 0);
                    pstmt.setString(9, notification.getCreatedAt().toString());
                    pstmt.executeUpdate();
                } catch (SQLException ex) {
                    throw new TrystInfrastructureException("Failed to save notification " + notification.getId(), ex);
                }
            }
        }
    }

    @Override
    public boolean saveIfAbsent(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try {
                if (notification.getDedupKey() != null && !notification.getDedupKey().isBlank()) {
                    String checkSql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND dedup_key = ?;";
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                        checkStmt.setString(1, notification.getUserId());
                        checkStmt.setString(2, notification.getDedupKey());
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                return false;
                            }
                        }
                    } catch (SQLException ignored) {}
                }
                save(notification);
                return true;
            } catch (Exception e) {
                throw new TrystInfrastructureException("Failed to check or save notification " + notification.getId(), e);
            }
        }
    }

    @Override
    public Optional<Notification> findById(String id) {
        String sql = "SELECT * FROM notifications WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapRow(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find notification " + id, e);
            }
        }
    }

    @Override
    public List<Notification> findByUserId(String userId, boolean unreadOnly, int limit) {
        String sql = "SELECT * FROM notifications WHERE user_id = ?"
                + (unreadOnly ? " AND is_read = 0" : "")
                + " ORDER BY created_at DESC LIMIT ?;";
        List<Notification> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setInt(2, Math.max(1, limit));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query notifications for user " + userId, e);
            }
        }
    }

    @Override
    public List<Notification> findByTaskId(String taskId, String userId) {
        String sql = "SELECT * FROM notifications WHERE (user_id = ? OR ? IS NULL) AND reference_type = 'task' AND reference_id = ? ORDER BY created_at ASC;";
        List<Notification> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId != null ? userId : "");
                pstmt.setString(2, userId);
                pstmt.setString(3, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query notifications for taskId " + taskId, e);
            }
        }
    }

    @Override
    public List<Notification> findSinceSequence(String userId, long sinceSeq, int limit) {
        String sql = "SELECT * FROM notifications WHERE user_id = ? AND seq_num > ? ORDER BY seq_num ASC LIMIT ?;";
        List<Notification> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setLong(2, sinceSeq);
                pstmt.setInt(3, Math.max(1, limit));
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRow(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                return findByUserId(userId, false, limit);
            }
        }
    }

    @Override
    public void markAsRead(String id) {
        String sql = "UPDATE notifications SET is_read = 1 WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to mark notification as read " + id, e);
            }
        }
    }

    @Override
    public void markAllAsRead(String userId) {
        String sql = "UPDATE notifications SET is_read = 1 WHERE user_id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to mark all notifications as read for " + userId, e);
            }
        }
    }

    @Override
    public long countUnread(String userId) {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = 0;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to count unread notifications for " + userId, e);
            }
        }
    }

    private Notification mapRow(ResultSet rs) throws SQLException {
        String dedupKey = null;
        long seqNum = 0L;
        try {
            dedupKey = rs.getString("dedup_key");
        } catch (SQLException ignored) {}
        try {
            seqNum = rs.getLong("seq_num");
        } catch (SQLException ignored) {}

        return new Notification(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getInt("is_read") == 1,
                Instant.parse(rs.getString("created_at")),
                dedupKey,
                seqNum
        );
    }
}
