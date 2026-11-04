package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.repository.UnitOfWork;

import java.time.Instant;
import java.util.Objects;

/**
 * Service managing data retention and safe archival / cleanup policies across TRYST persistence.
 * Safely purges expired sessions, stale telemetry snapshots, and obsolete task events
 * inside atomic database transaction boundaries while preserving active projects, tasks, and reviews.
 */
public class DataRetentionService {

    public record RetentionReport(
            int expiredSessionsPurged,
            int oldTelemetryPurged,
            int oldAuditEventsPurged,
            int oldTaskEventsPurged,
            Instant executionTime
    ) {}

    public interface RetentionStore {
        int purgeExpiredUserSessions(Instant cutoff);
        int purgeTelemetrySnapshots(Instant cutoff);
        int purgeAuditEvents(Instant cutoff);
        int purgeTaskEvents(Instant cutoff);
    }

    private final RetentionStore retentionStore;
    private final UnitOfWork unitOfWork;
    private RetentionPolicy policy;

    public DataRetentionService(RetentionStore retentionStore, UnitOfWork unitOfWork) {
        this(retentionStore, unitOfWork, RetentionPolicy.defaultPolicy());
    }

    public DataRetentionService(RetentionStore retentionStore, UnitOfWork unitOfWork, RetentionPolicy policy) {
        this.retentionStore = Objects.requireNonNull(retentionStore, "retentionStore must not be null");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public synchronized void setPolicy(RetentionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public RetentionPolicy getPolicy() {
        return policy;
    }

    /**
     * Executes the configured retention policy within a transactional boundary.
     */
    public synchronized RetentionReport executeRetentionPolicy() {
        Instant now = Instant.now();
        Instant sessionCutoff = now.minus(policy.userSessionTtl());
        Instant telemetryCutoff = now.minus(policy.telemetryRetention());
        Instant auditCutoff = now.minus(policy.auditEventRetention());
        Instant taskEventCutoff = now.minus(policy.taskEventRetention());

        TrystLog.info(DataRetentionService.class, "Executing data retention policies...");

        return unitOfWork.supplyInTransaction(() -> {
            int sessions = retentionStore.purgeExpiredUserSessions(sessionCutoff);
            int telemetry = retentionStore.purgeTelemetrySnapshots(telemetryCutoff);
            int audits = retentionStore.purgeAuditEvents(auditCutoff);
            int events = retentionStore.purgeTaskEvents(taskEventCutoff);

            TrystLog.info(DataRetentionService.class, String.format(
                    "Retention completed: purged %d expired sessions, %d telemetry snapshots, %d audit records, %d task events.",
                    sessions, telemetry, audits, events
            ));

            return new RetentionReport(sessions, telemetry, audits, events, now);
        });
    }
}
