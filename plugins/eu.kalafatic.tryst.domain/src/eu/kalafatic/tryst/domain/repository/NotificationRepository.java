package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.Notification;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
    void save(Notification notification);
    boolean saveIfAbsent(Notification notification);
    Optional<Notification> findById(String id);
    List<Notification> findByUserId(String userId, boolean unreadOnly, int limit);
    List<Notification> findByTaskId(String taskId, String userId);
    List<Notification> findSinceSequence(String userId, long sinceSeq, int limit);
    void markAsRead(String id);
    void markAllAsRead(String userId);
    long countUnread(String userId);
}
