package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.TelemetrySnapshot;

import java.time.Instant;
import java.util.List;

public interface TelemetryRepository {
    void save(TelemetrySnapshot snapshot);
    List<TelemetrySnapshot> findSince(Instant since, int limit);
    List<TelemetrySnapshot> findRecent(int limit);
    void deleteOlderThan(Instant cutoff);
}
