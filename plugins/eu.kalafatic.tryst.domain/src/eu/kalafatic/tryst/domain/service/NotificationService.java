package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.model.NotificationPreference;
import eu.kalafatic.tryst.domain.model.NotificationType;
import eu.kalafatic.tryst.domain.repository.NotificationPreferenceRepository;
import eu.kalafatic.tryst.domain.repository.NotificationRepository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final EventBus eventBus;
    private final List<Consumer<Notification>> realTimeListeners = new CopyOnWriteArrayList<>();

    public NotificationService(NotificationRepository notificationRepository, EventBus eventBus) {
        this(notificationRepository, null, eventBus);
    }

    private eu.kalafatic.tryst.domain.repository.UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationPreferenceRepository preferenceRepository,
                               EventBus eventBus) {
        this(notificationRepository, preferenceRepository, eventBus, null);
    }

    public NotificationService(NotificationRepository notificationRepository,
                               NotificationPreferenceRepository preferenceRepository,
                               EventBus eventBus,
                               eu.kalafatic.tryst.domain.repository.UserRepository userRepository) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.preferenceRepository = preferenceRepository;
        this.eventBus = eventBus;
        this.userRepository = userRepository;
        subscribeToEvents();
    }

    private void subscribeToEvents() {
        if (eventBus != null) {
            eventBus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, event -> {});
        }
    }

    public Optional<Notification> notifyUser(String userId, String typeStr, String title, String message,
                                             String refType, String refId, String dedupKey) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        NotificationType type = NotificationType.fromString(typeStr);

        if (preferenceRepository != null && type != null) {
            NotificationPreference pref = preferenceRepository.findByUserId(userId)
                    .orElseGet(() -> NotificationPreference.createDefault(userId));
            if (!pref.isInAppNotificationsEnabled() && !pref.isBrowserNotificationsEnabled()) {
                return Optional.empty();
            }
            if (!pref.isTypeEnabled(type)) {
                return Optional.empty();
            }
        }

        String effectiveDedupKey = dedupKey;
        if (effectiveDedupKey == null || effectiveDedupKey.isBlank()) {
            effectiveDedupKey = userId + ":" + (type != null ? type.name() : typeStr) + ":" + (refId != null ? refId : "global");
        }

        if (userRepository != null && !userRepository.findById(userId).isPresent()) {
            return Optional.empty();
        }

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                type != null ? type.name() : typeStr,
                title,
                message,
                refType,
                refId,
                false,
                Instant.now(),
                effectiveDedupKey,
                0L
        );

        boolean saved = false;
        try {
            saved = notificationRepository.saveIfAbsent(notification);
        } catch (Exception e) {
            TrystLog.warn(NotificationService.class, "Failed to persist notification for user " + userId + ": " + e.getMessage());
            return Optional.empty();
        }
        if (!saved) {
            return Optional.empty();
        }

        Notification stored = notificationRepository.findById(notification.getId()).orElse(notification);

        if (eventBus != null) {
            eventBus.publish(new ApplicationEvents.NotificationBroadcastEvent(
                    stored.getId(),
                    stored.getUserId(),
                    stored.getType(),
                    stored.getTitle(),
                    stored.getMessage(),
                    stored.getReferenceType(),
                    stored.getReferenceId(),
                    stored.getSequenceNumber()
            ));
        }

        broadcast(stored);
        return Optional.of(stored);
    }

    public Optional<Notification> notifyUser(String userId, String type, String title, String message, String refType, String refId) {
        return notifyUser(userId, type, title, message, refType, refId, null);
    }

    public AutoCloseable registerListener(Consumer<Notification> listener) {
        realTimeListeners.add(listener);
        return () -> realTimeListeners.remove(listener);
    }

    private void broadcast(Notification notification) {
        for (Consumer<Notification> listener : realTimeListeners) {
            try {
                listener.accept(notification);
            } catch (Exception e) {
                TrystLog.warn(NotificationService.class, "Failed to deliver real-time notification: " + e.getMessage());
            }
        }
    }

    public List<Notification> getUserNotifications(String userId, boolean unreadOnly, int limit) {
        return notificationRepository.findByUserId(userId, unreadOnly, limit);
    }

    public List<Notification> getTaskNotifications(String taskId, String userId) {
        return notificationRepository.findByTaskId(taskId, userId);
    }

    public List<Notification> getNotificationsSinceSequence(String userId, long sinceSeq, int limit) {
        return notificationRepository.findSinceSequence(userId, sinceSeq, limit);
    }

    public void markAsRead(String notificationId) {
        notificationRepository.markAsRead(notificationId);
    }

    public void markAllAsRead(String userId) {
        notificationRepository.markAllAsRead(userId);
    }

    public long getUnreadCount(String userId) {
        return notificationRepository.countUnread(userId);
    }

    public NotificationPreference getPreferences(String userId) {
        if (preferenceRepository != null) {
            return preferenceRepository.findByUserId(userId)
                    .orElseGet(() -> NotificationPreference.createDefault(userId));
        }
        return NotificationPreference.createDefault(userId);
    }

    public void updatePreferences(NotificationPreference preference) {
        if (preferenceRepository != null) {
            preferenceRepository.save(preference);
        }
    }

    public NotificationRepository getNotificationRepository() {
        return notificationRepository;
    }

    public NotificationPreferenceRepository getPreferenceRepository() {
        return preferenceRepository;
    }
}
