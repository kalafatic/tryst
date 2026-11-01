package eu.kalafatic.tryst.core.event;

import java.time.Instant;

/**
 * Root interface for all strongly-typed immutable events in TRYST.
 */
public interface TrystTypedEvent {

    String eventId();

    Instant timestamp();

    String correlationId();
}
