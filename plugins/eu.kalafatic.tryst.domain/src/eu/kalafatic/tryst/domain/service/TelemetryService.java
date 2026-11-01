package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.TelemetrySnapshot;
import eu.kalafatic.tryst.domain.repository.TelemetryRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing periodic collection and downsampled query of system telemetry.
 */
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final ServerMonitoringService monitoringService;
    private final UserSessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TelemetryService(TelemetryRepository telemetryRepository,
                            ServerMonitoringService monitoringService,
                            UserSessionRepository sessionRepository,
                            TaskRepository taskRepository) {
        this.telemetryRepository = Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
        this.monitoringService = Objects.requireNonNull(monitoringService, "monitoringService must not be null");
        this.sessionRepository = sessionRepository;
        this.taskRepository = taskRepository;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tryst-telemetry-collector");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start(long intervalSeconds) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::captureSnapshot, 2, Math.max(1, intervalSeconds), TimeUnit.SECONDS);
            TrystLog.info(TelemetryService.class, "Telemetry collection service started (interval: " + intervalSeconds + "s).");
        }
    }

    public void captureSnapshot() {
        try {
            ServerMonitoringService.SystemMetrics metrics = monitoringService.collectMetrics();
            int activeSessions = sessionRepository != null ? sessionRepository.findAllActive().size() : 0;
            int queued = 0;
            int runningTasks = 0;
            if (taskRepository != null) {
                for (var t : taskRepository.findAll()) {
                    if (t.getTaskStatus() == eu.kalafatic.tryst.domain.model.TaskStatus.QUEUED) queued++;
                    if (t.getTaskStatus() == eu.kalafatic.tryst.domain.model.TaskStatus.RUNNING) runningTasks++;
                }
            }

            TelemetrySnapshot snapshot = TelemetrySnapshot.create(
                    metrics.systemCpuLoad(),
                    metrics.processCpuLoad(),
                    metrics.heapUsedBytes(),
                    metrics.heapMaxBytes(),
                    metrics.totalPhysicalMemoryBytes() - metrics.freePhysicalMemoryBytes(),
                    metrics.workspaceUsedBytes(),
                    metrics.workspaceTotalBytes(),
                    activeSessions,
                    queued,
                    runningTasks,
                    0.0
            );

            telemetryRepository.save(snapshot);
        } catch (Throwable t) {
            TrystLog.warn(TelemetryService.class, "Failed to capture telemetry snapshot: " + t.getMessage());
        }
    }

    public List<TelemetrySnapshot> getRecentHistory(int limit) {
        return telemetryRepository.findRecent(limit);
    }

    public List<TelemetrySnapshot> getSince(Instant since, int limit) {
        return telemetryRepository.findSince(since, limit);
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdownNow();
            TrystLog.info(TelemetryService.class, "Telemetry collection service stopped.");
        }
    }
}
