package eu.kalafatic.tryst.domain.env;

import eu.kalafatic.tryst.core.event.TrystTypedEvent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed execution-environment events published by {@link ExecutionEnvironmentMonitor}
 * and by the task orchestration layer when tasks are blocked or released.
 *
 * <p>All events implement the shared {@link TrystTypedEvent} contract and are dispatched
 * through the existing application-wide {@link eu.kalafatic.tryst.core.event.EventBus}.
 * No new event infrastructure is introduced.</p>
 */
public final class EnvironmentEvents {

    private EnvironmentEvents() {}

    /**
     * Published whenever the authoritative environment snapshot changes (any component
     * state, the readiness flag or the problem set changed).
     */
    public record StatusChangedEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            ExecutionEnvironmentMonitor.EnvironmentSnapshot snapshot
    ) implements TrystTypedEvent {
        public StatusChangedEvent(ExecutionEnvironmentMonitor.EnvironmentSnapshot snapshot) {
            this(UUID.randomUUID().toString(), Instant.now(), null,
                    Objects.requireNonNull(snapshot, "snapshot must not be null"));
        }
    }

    /**
     * Published when overall execution readiness changes, or when the readiness problem
     * set changes while the environment stays unavailable (a new blocking episode).
     * A {@code ready == true} event signals environment recovery and authorizes the
     * release of eligible blocked tasks.
     */
    public record ReadinessChangedEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            boolean ready,
            String message,
            List<String> problems,
            String episodeKey
    ) implements TrystTypedEvent {
        public ReadinessChangedEvent(boolean ready, String message, List<String> problems, String episodeKey) {
            this(UUID.randomUUID().toString(), Instant.now(), null, ready,
                    message != null ? message : (ready ? "Execution environment is ready." : "Execution environment is not ready."),
                    problems != null ? List.copyOf(problems) : List.of(),
                    episodeKey != null ? episodeKey : "");
        }

        @Override
        public List<String> problems() {
            return problems == null ? List.of() : problems;
        }
    }

    /**
     * Published when an incoming task is accepted and persisted but blocked because the
     * execution environment is not ready. Used by the RCP notification layer to inform
     * the server administrator without polling.
     */
    public record TaskBlockedEvent(
            String eventId,
            Instant timestamp,
            String correlationId,
            String taskId,
            String title,
            String reason,
            List<String> problems,
            String episodeKey
    ) implements TrystTypedEvent {
        public TaskBlockedEvent(String taskId, String title, String reason, List<String> problems, String episodeKey) {
            this(UUID.randomUUID().toString(), Instant.now(), null,
                    Objects.requireNonNull(taskId, "taskId must not be null"),
                    title != null ? title : "",
                    reason != null ? reason : "Execution environment is not ready.",
                    problems != null ? List.copyOf(problems) : List.of(),
                    episodeKey != null ? episodeKey : "");
        }

        @Override
        public List<String> problems() {
            return problems == null ? List.of() : problems;
        }
    }
}
