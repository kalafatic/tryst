package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.TaskStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates deterministic state transitions according to the TRYST Task Lifecycle.
 * Rejects illegal transitions with descriptive domain exceptions.
 */
public final class TaskStateTransitionValidator {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        // From CREATED
        ALLOWED_TRANSITIONS.put(TaskStatus.CREATED, EnumSet.of(
                TaskStatus.PLANNED,
                TaskStatus.READY,
                TaskStatus.QUEUED,
                TaskStatus.RUNNING,
                TaskStatus.CANCELLED
        ));

        // From PLANNED
        ALLOWED_TRANSITIONS.put(TaskStatus.PLANNED, EnumSet.of(
                TaskStatus.READY,
                TaskStatus.QUEUED,
                TaskStatus.RUNNING,
                TaskStatus.CANCELLED
        ));

        // From READY
        ALLOWED_TRANSITIONS.put(TaskStatus.READY, EnumSet.of(
                TaskStatus.QUEUED,
                TaskStatus.CLAIMED,
                TaskStatus.RUNNING,
                TaskStatus.CANCELLED
        ));

        // From QUEUED
        ALLOWED_TRANSITIONS.put(TaskStatus.QUEUED, EnumSet.of(
                TaskStatus.CLAIMED,
                TaskStatus.RUNNING,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From CLAIMED
        ALLOWED_TRANSITIONS.put(TaskStatus.CLAIMED, EnumSet.of(
                TaskStatus.RUNNING,
                TaskStatus.CLONING,
                TaskStatus.ANALYZING,
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From RUNNING (backward compatibility)
        ALLOWED_TRANSITIONS.put(TaskStatus.RUNNING, EnumSet.of(
                TaskStatus.CLONING,
                TaskStatus.ANALYZING,
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.BUILDING,
                TaskStatus.TESTING,
                TaskStatus.WAITING_FOR_APPROVAL,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From CLONING
        ALLOWED_TRANSITIONS.put(TaskStatus.CLONING, EnumSet.of(
                TaskStatus.ANALYZING,
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From ANALYZING
        ALLOWED_TRANSITIONS.put(TaskStatus.ANALYZING, EnumSet.of(
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From PLANNING
        ALLOWED_TRANSITIONS.put(TaskStatus.PLANNING, EnumSet.of(
                TaskStatus.READY,
                TaskStatus.IMPLEMENTING,
                TaskStatus.WAITING_FOR_APPROVAL,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From IMPLEMENTING
        ALLOWED_TRANSITIONS.put(TaskStatus.IMPLEMENTING, EnumSet.of(
                TaskStatus.BUILDING,
                TaskStatus.TESTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From BUILDING
        ALLOWED_TRANSITIONS.put(TaskStatus.BUILDING, EnumSet.of(
                TaskStatus.TESTING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From TESTING
        ALLOWED_TRANSITIONS.put(TaskStatus.TESTING, EnumSet.of(
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.IMPLEMENTING,
                TaskStatus.BUILDING,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From WAITING_FOR_APPROVAL
        ALLOWED_TRANSITIONS.put(TaskStatus.WAITING_FOR_APPROVAL, EnumSet.of(
                TaskStatus.RUNNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.APPROVED,
                TaskStatus.REJECTED,
                TaskStatus.CANCELLED
        ));

        // From WAITING_FOR_REVIEW
        ALLOWED_TRANSITIONS.put(TaskStatus.WAITING_FOR_REVIEW, EnumSet.of(
                TaskStatus.APPROVED,
                TaskStatus.REJECTED,
                TaskStatus.REWORK_REQUESTED,
                TaskStatus.CANCELLED
        ));

        // From REWORK_REQUESTED
        ALLOWED_TRANSITIONS.put(TaskStatus.REWORK_REQUESTED, EnumSet.of(
                TaskStatus.QUEUED,
                TaskStatus.REWORKING,
                TaskStatus.CLAIMED,
                TaskStatus.CANCELLED
        ));

        // From REWORKING
        ALLOWED_TRANSITIONS.put(TaskStatus.REWORKING, EnumSet.of(
                TaskStatus.BUILDING,
                TaskStatus.TESTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From APPROVED
        ALLOWED_TRANSITIONS.put(TaskStatus.APPROVED, EnumSet.of(
                TaskStatus.COMMITTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.REJECTED,
                TaskStatus.CANCELLED
        ));

        // From REJECTED
        ALLOWED_TRANSITIONS.put(TaskStatus.REJECTED, EnumSet.of(
                TaskStatus.QUEUED,
                TaskStatus.REWORK_REQUESTED,
                TaskStatus.CANCELLED
        ));

        // From COMMITTING
        ALLOWED_TRANSITIONS.put(TaskStatus.COMMITTING, EnumSet.of(
                TaskStatus.COMPLETED,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
                TaskStatus.RECOVERY_REQUIRED
        ));

        // From RECOVERY_REQUIRED
        ALLOWED_TRANSITIONS.put(TaskStatus.RECOVERY_REQUIRED, EnumSet.of(
                TaskStatus.QUEUED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED
        ));

        // Terminal states: COMPLETED, FAILED, CANCELLED have no outward normal transitions
        ALLOWED_TRANSITIONS.put(TaskStatus.COMPLETED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(TaskStatus.FAILED, Collections.emptySet());
        ALLOWED_TRANSITIONS.put(TaskStatus.CANCELLED, Collections.emptySet());
    }

    private TaskStateTransitionValidator() {}

    /**
     * Checks whether transition from source to target is permitted.
     */
    public static boolean isValidTransition(TaskStatus current, TaskStatus target) {
        if (current == null || target == null) {
            return false;
        }
        if (current == target) {
            return true;
        }
        Set<TaskStatus> allowed = ALLOWED_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(target);
    }

    /**
     * Validates transition or throws TrystDomainException if illegal.
     */
    public static void validateTransition(TaskStatus current, TaskStatus target) {
        if (!isValidTransition(current, target)) {
            throw new TrystDomainException(String.format(
                    "Illegal task state transition from %s to %s", current, target));
        }
    }
}
