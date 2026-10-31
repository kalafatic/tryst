package eu.kalafatic.tryst.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Fundamental immutable event abstraction for application state notifications.
 */
public record ApplicationEvent<T>(
        String eventType,
        T payload,
        Instant timestamp
) {
    public ApplicationEvent {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static <T> ApplicationEvent<T> of(String eventType, T payload) {
        return new ApplicationEvent<>(eventType, payload, Instant.now());
    }
}
