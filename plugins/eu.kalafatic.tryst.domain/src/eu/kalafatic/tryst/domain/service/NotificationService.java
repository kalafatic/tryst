package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Notification;
import eu.kalafatic.tryst.domain.repository.NotificationRepository;

import java.util.List;
import java.util.Objects;

/**
 * Service managing user notifications, bridging domain lifecycle events
 * to persistent user notification channels.
 */
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EventBus eventBus;

    public NotificationService(NotificationRepository notificationRepository, EventBus eventBus) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.eventBus = eventBus;
        subscribeToEvents();
    }

    private void subscribeToEvents() {
        if (eventBus != null) {
            eventBus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, event -> {
                // Application task state change bridge
            });
        }
    }

    public void notifyUser(String userId, String type, String title, String message, String refType, String refId) {
        Notification notification = new Notification(userId, type, title, message, refType, refId);
        notificationRepository.save(notification);
    }

    public List<Notification> getUserNotifications(String userId, boolean unreadOnly, int limit) {
        return notificationRepository.findByUserId(userId, unreadOnly, limit);
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
}
