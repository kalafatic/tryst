package eu.kalafatic.tryst.core.lifecycle;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable lifecycle transition event.
 */
public record ApplicationLifecycleEvent(
        ApplicationLifecycleStage stage,
        String description,
        Instant timestamp
) {
    public ApplicationLifecycleEvent {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static ApplicationLifecycleEvent of(ApplicationLifecycleStage stage) {
        return new ApplicationLifecycleEvent(stage, stage.name(), Instant.now());
    }

    public static ApplicationLifecycleEvent of(ApplicationLifecycleStage stage, String description) {
        return new ApplicationLifecycleEvent(stage, description, Instant.now());
    }
}
