package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.UserSession;

import java.util.List;
import java.util.Optional;

public interface UserSessionRepository {
    void save(UserSession session);
    Optional<UserSession> findById(String id);
    Optional<UserSession> findByTokenHash(String tokenHash);
    List<UserSession> findByUserId(String userId);
    List<UserSession> findAllActive();
    List<UserSession> findAll();
    void revokeByUserId(String userId);
    void revoke(String sessionId);
    void deleteExpired();
}
