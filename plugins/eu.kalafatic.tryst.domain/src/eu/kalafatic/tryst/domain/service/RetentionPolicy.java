package eu.kalafatic.tryst.domain.service;

import java.time.Duration;

/**
 * Configuration contract defining retention and archival policies for transient data.
 */
public record RetentionPolicy(
        Duration userSessionTtl,
        Duration auditEventRetention,
        Duration telemetryRetention,
        Duration completedTaskRetention,
        Duration taskEventRetention
) {
    public static RetentionPolicy defaultPolicy() {
        return new RetentionPolicy(
                Duration.ofDays(7),    // Inactive/revoked sessions retained for 7 days
                Duration.ofDays(90),   // Audit events retained for 90 days
                Duration.ofDays(30),   // Telemetry snapshots retained for 30 days
                Duration.ofDays(60),   // Completed/cancelled tasks older than 60 days
                Duration.ofDays(30)    // Task lifecycle diagnostic events older than 30 days
        );
    }
}
