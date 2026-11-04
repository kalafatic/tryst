package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain entity representing an execution recovery checkpoint.
 * Captures the exact lifecycle stage, execution metadata, and timestamps
 * enabling deterministic restart recovery and resumption decisions.
 */
public record TaskRecoveryCheckpoint(
        String id,
        String taskId,
        TaskStatus stage,
        String checkpointDataJson,
        Instant timestamp
) {
    public TaskRecoveryCheckpoint {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (checkpointDataJson == null) {
            checkpointDataJson = "{}";
        }
    }

    public static TaskRecoveryCheckpoint of(String taskId, TaskStatus stage, String checkpointDataJson) {
        return new TaskRecoveryCheckpoint(
                UUID.randomUUID().toString(),
                taskId,
                stage,
                checkpointDataJson,
                Instant.now()
        );
    }
}
