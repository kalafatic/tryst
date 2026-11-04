package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.NotificationPreference;

import java.util.Optional;

public interface NotificationPreferenceRepository {
    void save(NotificationPreference preference);
    Optional<NotificationPreference> findByUserId(String userId);
}
