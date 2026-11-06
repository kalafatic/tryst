package eu.kalafatic.tryst.core.port;

import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative port allocation manager.
 * Evaluates candidate groups (persisted or defaults), detects conflicts,
 * invokes user or headless conflict resolution prompts, persists effective
 * allocations, and provides the authoritative group to listeners.
 */
public class PortAllocationManager {

    private final PortPersistence persistence;
    private final PortConflictResolver conflictResolver;
    private final PortAvailabilityChecker availabilityChecker;
    private volatile PortAllocationGroup effectiveGroup;

    public PortAllocationManager(PortPersistence persistence) {
        this(persistence, new PortConflictResolver(), new PortAvailabilityChecker());
    }

    public PortAllocationManager(PortPersistence persistence,
                                 PortConflictResolver conflictResolver,
                                 PortAvailabilityChecker availabilityChecker) {
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        this.conflictResolver = Objects.requireNonNull(conflictResolver, "conflictResolver must not be null");
        this.availabilityChecker = Objects.requireNonNull(availabilityChecker, "availabilityChecker must not be null");
    }

    /**
     * Resolves the authoritative PortAllocationGroup for startup.
     * Sequence:
     * 1. Load persisted allocation group if present.
     * 2. If absent, start with default allocation (+30000 offset).
     * 3. Evaluate candidate group for port collisions.
     * 4. If occupied, invoke prompt:
     *    - OPTION A: Free port (explicit user confirmation).
     *    - OPTION B: Shift group to next coherent available group.
     * 5. Persist effective assignment.
     */
    public synchronized PortAllocationGroup resolveAllocations(String host, PortConflictPrompt prompt) {
        PortAllocationGroup candidate = persistence.loadAllocations()
                .orElseGet(() -> PortAllocationGroup.createDefault(host));

        PortConflictReport report = conflictResolver.evaluateGroup(candidate);
        if (!report.hasConflicts()) {
            this.effectiveGroup = candidate;
            persistence.saveAllocations(effectiveGroup);
            return effectiveGroup;
        }

        TrystLog.warn(PortAllocationManager.class, "Port collision detected for candidate group (offset " + candidate.groupOffset() + "): " + report.conflicts().size() + " conflict(s).");

        if (prompt == null) {
            // Default to deterministic alternative allocation if no prompt provided
            if (report.alternativeGroup() != null) {
                this.effectiveGroup = report.alternativeGroup();
                persistence.saveAllocations(effectiveGroup);
                return effectiveGroup;
            } else {
                throw new TrystApplicationException("Port collision detected and no alternative port group is available within range.");
            }
        }

        PortConflictAction action = prompt.promptResolution(report);
        switch (action) {
            case FREE_PORT -> {
                boolean freedAll = true;
                for (PortConflictReport.PortConflictItem item : report.conflicts()) {
                    boolean freed = conflictResolver.terminateConflictingProcess(item);
                    if (!freed) {
                        freedAll = false;
                        TrystLog.error(PortAllocationManager.class, "Failed to free port " + item.port() + " occupied by PID " + item.processInfo().pid());
                    }
                }
                if (freedAll) {
                    // Re-evaluate candidate group
                    PortConflictReport reCheck = conflictResolver.evaluateGroup(candidate);
                    if (!reCheck.hasConflicts()) {
                        this.effectiveGroup = candidate;
                        persistence.saveAllocations(effectiveGroup);
                        return effectiveGroup;
                    }
                }
                // If freeing failed or partial, fallback to alternative if available
                if (report.alternativeGroup() != null) {
                    TrystLog.warn(PortAllocationManager.class, "Falling back to coherent alternative group (offset " + report.alternativeGroup().groupOffset() + ").");
                    this.effectiveGroup = report.alternativeGroup();
                    persistence.saveAllocations(effectiveGroup);
                    return effectiveGroup;
                }
                throw new TrystApplicationException("Could not free occupied ports and no alternative group is available.");
            }
            case ALLOCATE_ALTERNATIVE -> {
                if (report.alternativeGroup() == null) {
                    throw new TrystApplicationException("No alternative port group available within configured search range.");
                }
                this.effectiveGroup = report.alternativeGroup();
                persistence.saveAllocations(effectiveGroup);
                return effectiveGroup;
            }
            case ABORT -> throw new TrystApplicationException("Startup aborted by user due to port conflict.");
            default -> throw new IllegalStateException("Unexpected conflict action: " + action);
        }
    }

    public synchronized PortAllocationGroup getEffectiveGroup() {
        return effectiveGroup;
    }

    public synchronized void setEffectiveGroup(PortAllocationGroup group) {
        this.effectiveGroup = group;
        if (group != null) {
            persistence.saveAllocations(group);
        }
    }
}
