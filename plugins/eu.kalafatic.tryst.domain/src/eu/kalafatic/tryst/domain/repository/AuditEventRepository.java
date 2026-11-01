package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.AuditEvent;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository {
    void save(AuditEvent event);
    List<AuditEvent> findRecent(int limit);
    List<AuditEvent> query(String actorId, String action, String projectId, Instant from, Instant to, int limit, int offset);
    long count();
}
