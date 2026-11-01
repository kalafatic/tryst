package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing a periodic system telemetry snapshot.
 */
public record TelemetrySnapshot(
        String id,
        Instant timestamp,
        double cpuSystem,
        double cpuProcess,
        long memoryHeapUsed,
        long memoryHeapMax,
        long memorySystemUsed,
        long diskWorkspaceUsed,
        long diskWorkspaceTotal,
        int activeSessions,
        int queuedTasks,
        int runningTasks,
        double aiLatencyMs
) {
    public TelemetrySnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static TelemetrySnapshot create(double cpuSystem, double cpuProcess,
                                           long heapUsed, long heapMax, long sysMemUsed,
                                           long diskUsed, long diskTotal,
                                           int activeSessions, int queuedTasks, int runningTasks,
                                           double aiLatencyMs) {
        return new TelemetrySnapshot(
                UUID.randomUUID().toString(),
                Instant.now(),
                cpuSystem,
                cpuProcess,
                heapUsed,
                heapMax,
                sysMemUsed,
                diskUsed,
                diskTotal,
                activeSessions,
                queuedTasks,
                runningTasks,
                aiLatencyMs
        );
    }
}
