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

    @Override
    public void save(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        String sql = """
            INSERT INTO notifications (id, user_id, type, title, message, reference_type, reference_id, is_read, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET is_read = excluded.is_read;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
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
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save notification " + notification.getId(), e);
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
        return new Notification(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getInt("is_read") == 1,
                Instant.parse(rs.getString("created_at"))
        );
    }
}
