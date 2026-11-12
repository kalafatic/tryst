package eu.kalafatic.tryst.domain.studio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StudioPlanStep(
        String id,
        String taskId,
        int stepOrder,
        String description,
        StepStatus status,
        Instant updatedAt
) {
    public enum StepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        BLOCKED
    }

    public StudioPlanStep {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (status == null) status = StepStatus.PENDING;
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public static StudioPlanStep create(String taskId, int order, String description, StepStatus status) {
        return new StudioPlanStep(UUID.randomUUID().toString(), taskId, order, description, status, Instant.now());
    }
}
