package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an immutable persistent audit event.
 */
public record AuditEvent(
        String id,
        Instant eventTime,
        String actorId,
        String action,
        String targetType,
        String targetId,
        String projectId,
        String taskId,
        String outcome,
        String detailsJson
) {
    public AuditEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventTime, "eventTime must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    public static AuditEvent create(String actorId, String action, String targetType, String targetId,
                                    String projectId, String taskId, String outcome, String detailsJson) {
        return new AuditEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                actorId != null ? actorId : "system",
                action,
                targetType,
                targetId,
                projectId,
                taskId,
                outcome,
                detailsJson
        );
    }
}
