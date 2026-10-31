package eu.kalafatic.tryst.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Output or status message entity.
 */
public record ConsoleMessage(
        Instant timestamp,
        String level,
        String source,
        String message
) {
    public ConsoleMessage {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static ConsoleMessage info(String source, String message) {
        return new ConsoleMessage(Instant.now(), "INFO", source, message);
    }

    public static ConsoleMessage warn(String source, String message) {
        return new ConsoleMessage(Instant.now(), "WARN", source, message);
    }

    public static ConsoleMessage error(String source, String message) {
        return new ConsoleMessage(Instant.now(), "ERROR", source, message);
    }
}
