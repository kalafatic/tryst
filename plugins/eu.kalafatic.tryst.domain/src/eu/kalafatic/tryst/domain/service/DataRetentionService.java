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
    private java.util.concurrent.ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

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

    public synchronized void start() {
        start(3600);
    }

    public synchronized void start(long intervalSeconds) {
        if (running.compareAndSet(false, true)) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "tryst-retention-pruner");
                    t.setDaemon(true);
                    return t;
                });
            }
            scheduler.scheduleAtFixedRate(this::executeRetentionPolicy, 10, Math.max(1, intervalSeconds), java.util.concurrent.TimeUnit.SECONDS);
            TrystLog.info(DataRetentionService.class, "Data retention pruning service started (interval: " + intervalSeconds + "s).");
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            TrystLog.info(DataRetentionService.class, "Data retention pruning service stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
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
