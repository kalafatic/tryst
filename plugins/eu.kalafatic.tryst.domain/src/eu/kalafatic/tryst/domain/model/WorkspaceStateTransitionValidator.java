package eu.kalafatic.tryst.domain.model;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict validator for workspace lifecycle state transitions.
 * Guarantees that workspaces move through valid, deterministic states
 * and protects active or recoverable work from accidental transition to invalid states.
 */
public final class WorkspaceStateTransitionValidator {

    private static final Map<WorkspaceStatus, Set<WorkspaceStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<WorkspaceStatus, Set<WorkspaceStatus>> map = new EnumMap<>(WorkspaceStatus.class);

        // ALLOCATED -> INITIALIZING, FAILED, CLEANUP_PENDING
        map.put(WorkspaceStatus.ALLOCATED, EnumSet.of(
                WorkspaceStatus.INITIALIZING,
                WorkspaceStatus.FAILED,
                WorkspaceStatus.CLEANUP_PENDING
        ));

        // INITIALIZING -> READY, FAILED, CLEANUP_PENDING
        map.put(WorkspaceStatus.INITIALIZING, EnumSet.of(
                WorkspaceStatus.READY,
                WorkspaceStatus.FAILED,
                WorkspaceStatus.CLEANUP_PENDING
        ));

        // READY -> ACTIVE, ARCHIVED, CLEANUP_PENDING, FAILED
        map.put(WorkspaceStatus.READY, EnumSet.of(
                WorkspaceStatus.ACTIVE,
                WorkspaceStatus.ARCHIVED,
                WorkspaceStatus.CLEANUP_PENDING,
                WorkspaceStatus.FAILED
        ));

        // ACTIVE -> WAITING_FOR_REVIEW, COMPLETED, FAILED, REWORKING, INACTIVE, LOCKED, CLOSED
        map.put(WorkspaceStatus.ACTIVE, EnumSet.of(
                WorkspaceStatus.WAITING_FOR_REVIEW,
                WorkspaceStatus.COMPLETED,
                WorkspaceStatus.FAILED,
                WorkspaceStatus.REWORKING,
                WorkspaceStatus.INACTIVE,
                WorkspaceStatus.LOCKED,
                WorkspaceStatus.CLOSED
        ));

        // WAITING_FOR_REVIEW -> REWORKING, COMPLETED, ARCHIVED, FAILED
        map.put(WorkspaceStatus.WAITING_FOR_REVIEW, EnumSet.of(
                WorkspaceStatus.REWORKING,
                WorkspaceStatus.COMPLETED,
                WorkspaceStatus.ARCHIVED,
                WorkspaceStatus.FAILED
        ));

        // REWORKING -> ACTIVE, WAITING_FOR_REVIEW, FAILED
        map.put(WorkspaceStatus.REWORKING, EnumSet.of(
                WorkspaceStatus.ACTIVE,
                WorkspaceStatus.WAITING_FOR_REVIEW,
                WorkspaceStatus.FAILED
        ));

        // COMPLETED -> ARCHIVED, CLEANUP_PENDING, REWORKING, CLOSED
        map.put(WorkspaceStatus.COMPLETED, EnumSet.of(
                WorkspaceStatus.ARCHIVED,
                WorkspaceStatus.CLEANUP_PENDING,
                WorkspaceStatus.REWORKING,
                WorkspaceStatus.CLOSED
        ));

        // ARCHIVED -> CLEANUP_PENDING, READY
        map.put(WorkspaceStatus.ARCHIVED, EnumSet.of(
                WorkspaceStatus.CLEANUP_PENDING,
                WorkspaceStatus.READY
        ));

        // CLEANUP_PENDING -> ARCHIVED
        map.put(WorkspaceStatus.CLEANUP_PENDING, EnumSet.of(
                WorkspaceStatus.ARCHIVED
        ));

        // FAILED -> REWORKING, CLEANUP_PENDING, ARCHIVED, READY
        map.put(WorkspaceStatus.FAILED, EnumSet.of(
                WorkspaceStatus.REWORKING,
                WorkspaceStatus.CLEANUP_PENDING,
                WorkspaceStatus.ARCHIVED,
                WorkspaceStatus.READY
        ));

        // Legacy compatibility transitions
        map.put(WorkspaceStatus.INACTIVE, EnumSet.of(WorkspaceStatus.ACTIVE, WorkspaceStatus.CLOSED, WorkspaceStatus.LOCKED));
        map.put(WorkspaceStatus.LOCKED, EnumSet.of(WorkspaceStatus.ACTIVE, WorkspaceStatus.CLOSED, WorkspaceStatus.INACTIVE));
        map.put(WorkspaceStatus.CLOSED, EnumSet.noneOf(WorkspaceStatus.class));

        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private WorkspaceStateTransitionValidator() {}

    public static boolean canTransition(WorkspaceStatus from, WorkspaceStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Set<WorkspaceStatus> targets = ALLOWED_TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    public static void validateTransition(WorkspaceStatus from, WorkspaceStatus to) {
        if (!canTransition(from, to)) {
            throw new TrystDomainException("Illegal workspace state transition from " + from + " to " + to);
        }
    }
}
