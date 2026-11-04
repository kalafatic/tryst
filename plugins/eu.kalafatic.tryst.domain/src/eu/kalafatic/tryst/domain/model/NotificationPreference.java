package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Domain entity representing user notification preferences.
 */
public class NotificationPreference {

    private final String userId;
    private final boolean browserNotificationsEnabled;
    private final boolean inAppNotificationsEnabled;
    private final Set<NotificationType> enabledTypes;
    private final Instant updatedAt;

    public NotificationPreference(String userId, boolean browserNotificationsEnabled,
                                  boolean inAppNotificationsEnabled, Set<NotificationType> enabledTypes,
                                  Instant updatedAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.browserNotificationsEnabled = browserNotificationsEnabled;
        this.inAppNotificationsEnabled = inAppNotificationsEnabled;
        this.enabledTypes = enabledTypes != null ? Collections.unmodifiableSet(new HashSet<>(enabledTypes)) : Collections.emptySet();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static NotificationPreference createDefault(String userId) {
        Set<NotificationType> all = new HashSet<>();
        Collections.addAll(all, NotificationType.values());
        return new NotificationPreference(userId, true, true, all, Instant.now());
    }

    public String getUserId() {
        return userId;
    }

    public boolean isBrowserNotificationsEnabled() {
        return browserNotificationsEnabled;
    }

    public boolean isInAppNotificationsEnabled() {
        return inAppNotificationsEnabled;
    }

    public Set<NotificationType> getEnabledTypes() {
        return enabledTypes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isTypeEnabled(NotificationType type) {
        if (type == null) {
            return true;
        }
        return enabledTypes.contains(type);
    }
}
