package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Controller for inspecting and controlling supported platform background services
 * (Telemetry collection, Data retention pruning, and Task queue orchestrator).
 */
public class ManagedServiceController {

    public enum ServiceStatus {
        RUNNING,
        STOPPED,
        UNKNOWN
    }

    public record ServiceInfo(String name, String displayName, boolean running, ServiceStatus status, String details) {}

    private final TelemetryService telemetryService;
    private final DataRetentionService dataRetentionService;
    private final TaskOrchestrator taskOrchestrator;

    public ManagedServiceController(
            TelemetryService telemetryService,
            DataRetentionService dataRetentionService,
            TaskOrchestrator taskOrchestrator
    ) {
        this.telemetryService = telemetryService;
        this.dataRetentionService = dataRetentionService;
        this.taskOrchestrator = taskOrchestrator;
    }

    public Map<String, ServiceInfo> listServices() {
        Map<String, ServiceInfo> map = new LinkedHashMap<>();

        boolean telemRunning = telemetryService != null && telemetryService.isRunning();
        map.put("telemetry", new ServiceInfo(
                "telemetry",
                "Telemetry Collection Service",
                telemRunning,
                telemRunning ? ServiceStatus.RUNNING : ServiceStatus.STOPPED,
                "Captures CPU, memory, thread pool, and disk telemetry snapshots."
        ));

        boolean retentionRunning = dataRetentionService != null && dataRetentionService.isRunning();
        map.put("data-retention", new ServiceInfo(
                "data-retention",
                "Data Retention & Archival Service",
                retentionRunning,
                retentionRunning ? ServiceStatus.RUNNING : ServiceStatus.STOPPED,
                "Executes transactional pruning of expired sessions, stale telemetry, and obsolete events."
        ));

        boolean orchRunning = taskOrchestrator != null && taskOrchestrator.isRunning();
        map.put("task-orchestrator", new ServiceInfo(
                "task-orchestrator",
                "Asynchronous Task Queue Orchestrator",
                orchRunning,
                orchRunning ? ServiceStatus.RUNNING : ServiceStatus.STOPPED,
                "Dispatches autonomous development tasks to worker threads."
        ));

        return Collections.unmodifiableMap(map);
    }

    public boolean startService(String name) {
        Objects.requireNonNull(name, "service name must not be null");
        switch (name.toLowerCase()) {
            case "telemetry" -> {
                if (telemetryService != null && !telemetryService.isRunning()) {
                    telemetryService.start();
                    TrystLog.info(ManagedServiceController.class, "Started service: telemetry");
                    return true;
                }
                return telemetryService != null && telemetryService.isRunning();
            }
            case "data-retention" -> {
                if (dataRetentionService != null && !dataRetentionService.isRunning()) {
                    dataRetentionService.start();
                    TrystLog.info(ManagedServiceController.class, "Started service: data-retention");
                    return true;
                }
                return dataRetentionService != null && dataRetentionService.isRunning();
            }
            case "task-orchestrator" -> {
                if (taskOrchestrator != null && !taskOrchestrator.isRunning()) {
                    taskOrchestrator.start();
                    TrystLog.info(ManagedServiceController.class, "Started service: task-orchestrator");
                    return true;
                }
                return taskOrchestrator != null && taskOrchestrator.isRunning();
            }
            default -> {
                TrystLog.warn(ManagedServiceController.class, "Unknown service to start: " + name);
                return false;
            }
        }
    }

    public boolean stopService(String name) {
        Objects.requireNonNull(name, "service name must not be null");
        switch (name.toLowerCase()) {
            case "telemetry" -> {
                if (telemetryService != null && telemetryService.isRunning()) {
                    telemetryService.stop();
                    TrystLog.info(ManagedServiceController.class, "Stopped service: telemetry");
                    return true;
                }
                return telemetryService != null && !telemetryService.isRunning();
            }
            case "data-retention" -> {
                if (dataRetentionService != null && dataRetentionService.isRunning()) {
                    dataRetentionService.stop();
                    TrystLog.info(ManagedServiceController.class, "Stopped service: data-retention");
                    return true;
                }
                return dataRetentionService != null && !dataRetentionService.isRunning();
            }
            case "task-orchestrator" -> {
                if (taskOrchestrator != null && taskOrchestrator.isRunning()) {
                    taskOrchestrator.shutdown();
                    TrystLog.info(ManagedServiceController.class, "Stopped service: task-orchestrator");
                    return true;
                }
                return taskOrchestrator != null && !taskOrchestrator.isRunning();
            }
            default -> {
                TrystLog.warn(ManagedServiceController.class, "Unknown service to stop: " + name);
                return false;
            }
        }
    }
}
