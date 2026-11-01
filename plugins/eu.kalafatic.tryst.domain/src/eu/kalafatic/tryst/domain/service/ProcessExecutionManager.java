package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.ManagedProcessRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and manager for child operating system processes spawned by TRYST (builds, tests, git).
 * Tracks PIDs, runtimes, and enforces safe termination without affecting external processes.
 */
public class ProcessExecutionManager {

    private final ConcurrentHashMap<String, ManagedProcessRecord> trackedRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public ManagedProcessRecord registerProcess(String taskId, Process process, String commandClass, String workingDir) {
        long pid = process.pid();
        ManagedProcessRecord record = new ManagedProcessRecord(taskId, pid, commandClass, workingDir);
        trackedRecords.put(record.getId(), record);
        activeProcesses.put(record.getId(), process);
        return record;
    }

    public void processCompleted(String recordId, int exitCode, long durationMs) {
        ManagedProcessRecord record = trackedRecords.get(recordId);
        if (record != null) {
            record.markTerminated(exitCode, durationMs);
        }
        activeProcesses.remove(recordId);
    }

    public boolean terminateProcess(String recordId) {
        Process p = activeProcesses.get(recordId);
        ManagedProcessRecord record = trackedRecords.get(recordId);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            if (record != null) {
                record.markKilled();
            }
            activeProcesses.remove(recordId);
            TrystLog.warn(ProcessExecutionManager.class, "Terminated managed process " + recordId + " (PID " + (record != null ? record.getPid() : "?") + ")");
            return true;
        }
        return false;
    }

    public List<ManagedProcessRecord> getManagedProcesses() {
        return new ArrayList<>(trackedRecords.values());
    }

    public void shutdown() {
        for (String id : activeProcesses.keySet()) {
            terminateProcess(id);
        }
        activeProcesses.clear();
    }
}
