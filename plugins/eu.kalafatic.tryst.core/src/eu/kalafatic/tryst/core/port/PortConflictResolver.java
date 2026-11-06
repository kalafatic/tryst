package eu.kalafatic.tryst.core.port;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates candidate port groups, identifies collisions and occupying processes,
 * resolves coherent group alternatives with 100-port spacing, and terminates
 * processes only upon explicit authorization.
 */
public class PortConflictResolver {

    public static final int DEFAULT_MAX_SEARCH_STEPS = 50; // allows searching +100 to +5000 offset

    private final PortAvailabilityChecker availabilityChecker;
    private final int maxSearchSteps;

    public PortConflictResolver() {
        this(new PortAvailabilityChecker(), DEFAULT_MAX_SEARCH_STEPS);
    }

    public PortConflictResolver(PortAvailabilityChecker availabilityChecker, int maxSearchSteps) {
        this.availabilityChecker = Objects.requireNonNull(availabilityChecker, "availabilityChecker must not be null");
        this.maxSearchSteps = maxSearchSteps > 0 ? maxSearchSteps : DEFAULT_MAX_SEARCH_STEPS;
    }

    /**
     * Checks all enabled ports in the candidate group and produces a conflict report.
     */
    public PortConflictReport evaluateGroup(PortAllocationGroup candidate) {
        Objects.requireNonNull(candidate, "candidate group must not be null");
        List<PortConflictReport.PortConflictItem> conflicts = new ArrayList<>();

        for (PortConfiguration cfg : candidate.configurations().values()) {
            if (!cfg.enabled()) {
                continue;
            }
            boolean available = availabilityChecker.isPortAvailable(cfg.bindingAddress(), cfg.assignedPort());
            if (!available) {
                ProcessPortInfo procInfo = availabilityChecker.identifyConflictingProcess(cfg.assignedPort())
                        .orElse(ProcessPortInfo.unidentifiable());
                conflicts.add(new PortConflictReport.PortConflictItem(cfg.serviceId(), cfg.assignedPort(), procInfo));
            }
        }

        PortAllocationGroup alternative = null;
        if (!conflicts.isEmpty()) {
            alternative = findNextAvailableGroup(candidate).orElse(null);
        }

        return new PortConflictReport(candidate, conflicts, alternative);
    }

    /**
     * Deterministically evaluates coherent alternative port groups by adding multiples
     * of SERVICE_PORT_SPACING (100) to the current group offset.
     * Evaluates the complete candidate group before accepting it.
     */
    public Optional<PortAllocationGroup> findNextAvailableGroup(PortAllocationGroup currentGroup) {
        int currentOffset = currentGroup.groupOffset();

        for (int step = 1; step <= maxSearchSteps; step++) {
            int candidateOffset = currentOffset + (step * PortAllocationGroup.SERVICE_PORT_SPACING);
            PortAllocationGroup candidate = currentGroup.withGroupOffset(candidateOffset);

            boolean allAvailable = true;
            for (PortConfiguration cfg : candidate.configurations().values()) {
                if (!cfg.enabled()) continue;
                if (!availabilityChecker.isPortAvailable(cfg.bindingAddress(), cfg.assignedPort())) {
                    allAvailable = false;
                    break;
                }
            }

            if (allAvailable) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    /**
     * Terminates the conflicting process for the specified item ONLY after explicit user confirmation.
     * Never called automatically.
     */
    public boolean terminateConflictingProcess(PortConflictReport.PortConflictItem conflictItem) {
        Objects.requireNonNull(conflictItem, "conflictItem must not be null");
        ProcessPortInfo pInfo = conflictItem.processInfo();
        if (pInfo == null || pInfo.pid() <= 0) {
            TrystLog.warn(PortConflictResolver.class, "Cannot terminate unidentifiable process on port " + conflictItem.port());
            return false;
        }

        long pid = pInfo.pid();
        if (pid == ProcessHandle.current().pid()) {
            TrystLog.error(PortConflictResolver.class, "Safety violation: Refusing to terminate current process (PID " + pid + ")");
            return false;
        }

        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        if (phOpt.isEmpty() || !phOpt.get().isAlive()) {
            TrystLog.info(PortConflictResolver.class, "Process PID " + pid + " is already not alive.");
            return true;
        }

        ProcessHandle ph = phOpt.get();
        TrystLog.info(PortConflictResolver.class, "Explicitly terminating conflicting process PID " + pid + " (" + pInfo.name() + ")");
        try {
            boolean destroyed = ph.destroy();
            if (!destroyed) {
                destroyed = ph.destroyForcibly();
            }
            // Wait up to 2 seconds for socket release
            for (int i = 0; i < 20; i++) {
                if (availabilityChecker.isPortAvailable(null, conflictItem.port())) {
                    return true;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }
            return availabilityChecker.isPortAvailable(null, conflictItem.port());
        } catch (Throwable t) {
            TrystLog.error(PortConflictResolver.class, "Failed to terminate process PID " + pid + ": " + t.getMessage(), t);
            return false;
        }
    }
}
