package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.NotificationPreference;
import eu.kalafatic.tryst.domain.model.NotificationType;
import eu.kalafatic.tryst.domain.repository.NotificationPreferenceRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class SqliteNotificationPreferenceRepository implements NotificationPreferenceRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteNotificationPreferenceRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(NotificationPreference preference) {
        Objects.requireNonNull(preference, "preference must not be null");
        String sql = """
            INSERT INTO notification_preferences (user_id, browser_enabled, in_app_enabled, enabled_types_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
                browser_enabled = excluded.browser_enabled,
                in_app_enabled = excluded.in_app_enabled,
                enabled_types_json = excluded.enabled_types_json,
                updated_at = excluded.updated_at;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, preference.getUserId());
                pstmt.setInt(2, preference.isBrowserNotificationsEnabled() ? 1 : 0);
                pstmt.setInt(3, preference.isInAppNotificationsEnabled() ? 1 : 0);
                pstmt.setString(4, serializeTypes(preference.getEnabledTypes()));
                pstmt.setString(5, preference.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save notification preferences for " + preference.getUserId(), e);
            }
        }
    }

    @Override
    public Optional<NotificationPreference> findByUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        String sql = "SELECT * FROM notification_preferences WHERE user_id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new NotificationPreference(
                                rs.getString("user_id"),
                                rs.getInt("browser_enabled") == 1,
                                rs.getInt("in_app_enabled") == 1,
                                deserializeTypes(rs.getString("enabled_types_json")),
                                Instant.parse(rs.getString("updated_at"))
                        ));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to load notification preferences for " + userId, e);
            }
        }
    }

    private static String serializeTypes(Set<NotificationType> types) {
        if (types == null || types.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (NotificationType t : types) {
            if (!first) sb.append(",");
            sb.append("\"").append(t.name()).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static Set<NotificationType> deserializeTypes(String json) {
        Set<NotificationType> set = new HashSet<>();
        if (json == null || json.isBlank() || json.equals("[]")) {
            return set;
        }
        String clean = json.replace("[", "").replace("]", "").replace("\"", "").trim();
        for (String part : clean.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                NotificationType nt = NotificationType.fromString(p);
                if (nt != null) {
                    set.add(nt);
                }
            }
        }
        return set;
    }
}
