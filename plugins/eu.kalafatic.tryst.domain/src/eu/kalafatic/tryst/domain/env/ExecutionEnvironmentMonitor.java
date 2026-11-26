package eu.kalafatic.tryst.domain.env;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Authoritative execution environment monitor.
 *
 * <p>One authoritative snapshot of the execution environment (TRYST server, coding
 * harness, inference provider and selected model) is produced here and consumed by the
 * RCP static status menu, the Dashboard, the Navigator, the Task Scheduler, the HTTP API
 * and the web client. No component computes its own health state.</p>
 *
 * <p>The monitor performs an initial assessment at startup and then periodic checks on a
 * configurable interval. Each external check is bounded by a timeout so a hanging
 * endpoint can never stall the monitoring thread indefinitely. Status changes and
 * readiness changes (including recovery detection) are published on the shared
 * {@link EventBus}.</p>
 *
 * <p>Readiness semantics: a component is required when the default execution pipeline
 * needs it. The TRYST server and an inference provider with a selected model are always
 * required; the coding harness is required only when a connected coding application is
 * selected and enabled. A component-level indicator may be GREEN while the environment
 * is NOT READY because another required component is unavailable.</p>
 *
 * <p>Threading: checks run on a single daemon scheduler thread plus a dedicated check
 * executor used to enforce per-check timeouts. The monitor never touches the SWT UI
 * thread; UI consumers dispatch updates via {@code Display.asyncExec}.</p>
 */
public class ExecutionEnvironmentMonitor {

    /** Coarse health of a single environment component. */
    public enum Health {
        /** Not checked yet or state unknown. */
        UNKNOWN,
        /** Starting or configuration in progress. */
        STARTING,
        /** Operational and verified by an actual health check. */
        OPERATIONAL,
        /** Partially available or degraded. */
        DEGRADED,
        /** Stopped, unavailable or failed. */
        UNAVAILABLE
    }

    /** Stable identifiers of the monitored execution environment components. */
    public enum ComponentId {
        SERVER,
        HARNESS,
        PROVIDER,
        MODEL
    }

    /** Health state of one execution environment component. */
    public record ComponentState(
            ComponentId id,
            String name,
            Health health,
            String detail,
            Instant lastCheckedAt
    ) {
        public ComponentState {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(health, "health must not be null");
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * Immutable authoritative environment snapshot. {@code problems} lists the readiness
     * blockers in display order; {@code episodeKey} is a stable key identifying the
     * current readiness episode ("READY" or "BLOCKED:<sorted problems>") used for
     * administrator notification deduplication.
     */
    public record EnvironmentSnapshot(
            Instant timestamp,
            Map<ComponentId, ComponentState> components,
            List<String> problems,
            List<ComponentId> requiredComponents,
            boolean ready,
            String readyMessage,
            Instant lastSuccessfulCheck,
            Instant lastFailedCheck,
            String episodeKey
    ) {
        public EnvironmentSnapshot {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(components, "components must not be null");
            components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
            problems = problems == null ? List.of() : List.copyOf(problems);
            requiredComponents = requiredComponents == null ? List.of() : List.copyOf(requiredComponents);
            readyMessage = readyMessage == null ? "" : readyMessage;
            episodeKey = episodeKey == null ? "" : episodeKey;
        }

        /** Returns the state of one component, or {@code null} when unknown. */
        public ComponentState component(ComponentId id) {
            return components.get(id);
        }
    }

    /** Default periodic check interval. */
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(15);

    /** Per-check timeout bound for external health probes. */
    public static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(10);

    /** Every Nth periodic cycle also refreshes the provider model catalog. */
    public static final int MODEL_DISCOVERY_CYCLE = 4;

    private final ConnectedApplicationRegistry harnessRegistry;
    private final AiProviderRegistry providerRegistry;
    private final AiModelRegistryService modelRegistry;
    private final eu.kalafatic.tryst.domain.ai.AiExecutionResolver aiExecutionResolver;
    private final EventBus eventBus;
    private final Supplier<Boolean> serverOperational;
    private final Supplier<String> serverDetail;
    private final Duration checkInterval;
    private final Duration checkTimeout;

    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ExecutorService checkExecutor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile EnvironmentSnapshot snapshot = initialSnapshot();
    private volatile boolean providerWasConnected = false;
    private volatile long cycleCount = 0;

    public ExecutionEnvironmentMonitor(
            ConnectedApplicationRegistry harnessRegistry,
            AiProviderRegistry providerRegistry,
            AiModelRegistryService modelRegistry,
            EventBus eventBus,
            Supplier<Boolean> serverOperational,
            Supplier<String> serverDetail,
            Duration checkInterval) {
        this(harnessRegistry, providerRegistry, modelRegistry, null, eventBus,
                serverOperational, serverDetail, checkInterval);
    }

    /**
     * Extended constructor with the single authoritative AI execution-configuration
     * resolver: the MODEL component then reflects the actually selected model instead
     * of the legacy catalog default.
     */
    public ExecutionEnvironmentMonitor(
            ConnectedApplicationRegistry harnessRegistry,
            AiProviderRegistry providerRegistry,
            AiModelRegistryService modelRegistry,
            eu.kalafatic.tryst.domain.ai.AiExecutionResolver aiExecutionResolver,
            EventBus eventBus,
            Supplier<Boolean> serverOperational,
            Supplier<String> serverDetail,
            Duration checkInterval) {
        this.harnessRegistry = harnessRegistry;
        this.providerRegistry = providerRegistry;
        this.modelRegistry = modelRegistry;
        this.aiExecutionResolver = aiExecutionResolver;
        this.eventBus = eventBus;
        this.serverOperational = serverOperational;
        this.serverDetail = serverDetail;
        this.checkInterval = checkInterval != null ? checkInterval : DEFAULT_CHECK_INTERVAL;
        this.checkTimeout = DEFAULT_CHECK_TIMEOUT;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tryst-env-monitor");
            t.setDaemon(true);
            return t;
        });
        this.checkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tryst-env-check");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The authoritative selected model from the unified execution configuration
     * resolver; falls back to the model catalog default when no resolver is wired.
     */
    private String resolveSelectedModel() {
        if (aiExecutionResolver != null) {
            try {
                String model = aiExecutionResolver.resolve(null, null).modelId();
                if (model != null && !model.isBlank()) {
                    return model;
                }
                return "";
            } catch (Exception e) {
                TrystLog.warn(ExecutionEnvironmentMonitor.class,
                        "Selected model resolution failed: " + e.getMessage());
                return "";
            }
        }
        return modelRegistry != null ? modelRegistry.getDefaultModel() : "";
    }

    private static EnvironmentSnapshot initialSnapshot() {
        Instant now = Instant.now();
        Map<ComponentId, ComponentState> components = new LinkedHashMap<>();
        for (ComponentId id : ComponentId.values()) {
            components.put(id, new ComponentState(id, displayName(id), Health.UNKNOWN,
                    "Not checked yet", now));
        }
        return new EnvironmentSnapshot(now, components, List.of(), List.of(), false,
                "Execution environment has not been assessed yet.", null, null, "UNASSESSED");
    }

    private static String displayName(ComponentId id) {
        return switch (id) {
            case SERVER -> "TRYST Server";
            case HARNESS -> "Coding Harness";
            case PROVIDER -> "Inference Provider";
            case MODEL -> "Model";
        };
    }

    /** Starts the initial assessment and the periodic health-check cycle. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::scheduledCycle, 0,
                Math.max(1, checkInterval.toSeconds()), TimeUnit.SECONDS);
        TrystLog.info(ExecutionEnvironmentMonitor.class,
                "ExecutionEnvironmentMonitor started (interval " + checkInterval.toSeconds() + "s, timeout "
                        + checkTimeout.toSeconds() + "s per check)");
    }

    /** Stops the periodic health-check cycle and releases executor resources. */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdownNow();
        checkExecutor.shutdownNow();
        TrystLog.info(ExecutionEnvironmentMonitor.class, "ExecutionEnvironmentMonitor stopped");
    }

    /** Returns the latest authoritative snapshot without performing any checks. */
    public EnvironmentSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Authoritative overall execution readiness for the default execution pipeline.
     *
     * <p>Before the very first assessment the environment is treated as ready: no check
     * has failed yet, and the monitor's startup assessment runs within the application
     * lifecycle. This avoids blocking submissions during the brief window between server
     * start and the initial assessment; from the first assessment onward the evaluated
     * readiness is authoritative.</p>
     */
    public boolean isReady() {
        EnvironmentSnapshot current = snapshot;
        return current.ready() || "UNASSESSED".equals(current.episodeKey());
    }

    /** Current readiness blockers in display order (empty when ready). */
    public List<String> getProblems() {
        return snapshot.problems();
    }

    /**
     * Performs a full health assessment now and publishes change events when the
     * snapshot changed. Callers on the SWT UI thread should invoke this from a
     * background thread; the published events allow UI consumers to stay event-driven.
     */
    public EnvironmentSnapshot refreshNow() {
        return performAssessment(false);
    }

    /**
     * Performs a full health assessment including provider model-catalog discovery.
     * Intended for explicit operator refresh actions ("Refresh Availability").
     */
    public EnvironmentSnapshot refreshWithModelDiscovery() {
        return performAssessment(true);
    }

    private void scheduledCycle() {
        try {
            long cycle = ++cycleCount;
            performAssessment(cycle % MODEL_DISCOVERY_CYCLE == 0);
        } catch (Exception e) {
            TrystLog.error(ExecutionEnvironmentMonitor.class,
                    "Scheduled environment check failed: " + e.getMessage(), e);
        }
    }

    private EnvironmentSnapshot performAssessment(boolean withModelDiscovery) {
        EnvironmentSnapshot next = evaluate(withModelDiscovery);
        EnvironmentSnapshot previous = snapshot;
        snapshot = next;

        boolean componentsChanged = !sameComponents(previous.components(), next.components());
        boolean readinessChanged = previous.ready() != next.ready();
        boolean episodeChanged = !Objects.equals(previous.episodeKey(), next.episodeKey());
        boolean problemsChanged = !new ArrayList<>(previous.problems()).equals(next.problems());

        if (componentsChanged || readinessChanged || episodeChanged || problemsChanged) {
            if (eventBus != null) {
                eventBus.publish(new EnvironmentEvents.StatusChangedEvent(next));
            }
        }
        if (readinessChanged || episodeChanged) {
            TrystLog.info(ExecutionEnvironmentMonitor.class,
                    "Execution readiness changed: ready=" + next.ready()
                            + " episode=" + next.episodeKey()
                            + " problems=" + next.problems());
            if (eventBus != null) {
                eventBus.publish(new EnvironmentEvents.ReadinessChangedEvent(
                        next.ready(), next.readyMessage(), next.problems(), next.episodeKey()));
            }
        }
        return next;
    }

    private static boolean sameComponents(Map<ComponentId, ComponentState> a,
                                          Map<ComponentId, ComponentState> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<ComponentId, ComponentState> e : a.entrySet()) {
            ComponentState other = b.get(e.getKey());
            if (other == null
                    || e.getValue().health() != other.health()
                    || !Objects.equals(e.getValue().detail(), other.detail())) {
                return false;
            }
        }
        return true;
    }

    private EnvironmentSnapshot evaluate(boolean withModelDiscovery) {
        Instant now = Instant.now();
        Map<ComponentId, ComponentState> components = new LinkedHashMap<>();
        List<ComponentId> required = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Instant lastSuccess = snapshot.lastSuccessfulCheck();
        Instant lastFailure = snapshot.lastFailedCheck();
        boolean allChecksPassed = true;

        // --- SERVER ---
        boolean serverOk = serverOperational != null && Boolean.TRUE.equals(serverOperational.get());
        String serverDetailText = serverDetail != null ? serverDetail.get() : "";
        if (serverDetailText == null) {
            serverDetailText = "";
        }
        if (!serverOk && serverDetailText.isBlank()) {
            serverDetailText = "Server is not operational (startup incomplete or HTTP server stopped)";
        }
        components.put(ComponentId.SERVER, new ComponentState(ComponentId.SERVER, "TRYST Server",
                serverOk ? Health.OPERATIONAL : Health.UNAVAILABLE,
                serverOk && serverDetailText.isBlank() ? "Server operational" : serverDetailText, now));
        required.add(ComponentId.SERVER);
        if (!serverOk) {
            allChecksPassed = false;
            problems.add("TRYST server is not operational.");
        }

        // --- HARNESS ---
        // Resolve the SELECTED application explicitly (not findSelectedAdapter(), which
        // returns empty when the harness is unavailable): a selected-but-unreachable
        // harness is exactly the readiness problem the operator must see.
        Optional<CodingHarnessAdapter> harnessAdapter = Optional.empty();
        String selectedHarnessName = null;
        if (harnessRegistry != null) {
            try {
                Optional<String> selectedId = harnessRegistry.getSelectedApplicationId();
                if (selectedId.isPresent()) {
                    harnessAdapter = harnessRegistry.findAdapter(selectedId.get());
                    boolean enabled = harnessRegistry.findApplication(selectedId.get())
                            .map(ConnectedApplicationDescriptor::isEnabled)
                            .orElse(true);
                    if (!enabled) {
                        // A selected-but-disabled harness is intentionally not required:
                        // execution falls back to the built-in local agent.
                        harnessAdapter = Optional.empty();
                        selectedHarnessName = selectedId.get() + " (disabled)";
                    }
                }
            } catch (Exception e) {
                TrystLog.warn(ExecutionEnvironmentMonitor.class,
                        "Harness selection resolution failed: " + e.getMessage());
            }
        }
        if (harnessAdapter.isPresent()) {
            CodingHarnessAdapter adapter = harnessAdapter.get();
            required.add(ComponentId.HARNESS);
            CodingHarnessAdapter.ConnectionTestResult result = withTimeout(
                    adapter::testConnection, checkTimeout,
                    CodingHarnessAdapter.ConnectionTestResult.error("Connection test timed out"));
            String harnessName = safeName(adapter.getApplicationName(), adapter.getApplicationId());
            if (result != null && result.success()) {
                components.put(ComponentId.HARNESS, new ComponentState(ComponentId.HARNESS,
                        "Coding Harness", Health.OPERATIONAL, harnessName + ": " + result.message(), now));
            } else {
                allChecksPassed = false;
                String message = result != null ? result.message() : "Connection test failed";
                components.put(ComponentId.HARNESS, new ComponentState(ComponentId.HARNESS,
                        "Coding Harness", Health.UNAVAILABLE, harnessName + ": " + message, now));
                problems.add("Required coding harness " + harnessName + " is unavailable: " + message);
            }
        } else if (selectedHarnessName != null) {
            components.put(ComponentId.HARNESS, new ComponentState(ComponentId.HARNESS,
                    "Coding Harness", Health.UNKNOWN,
                    "Selected harness " + selectedHarnessName + " is disabled (built-in local agent fallback)", now));
        } else {
            components.put(ComponentId.HARNESS, new ComponentState(ComponentId.HARNESS,
                    "Coding Harness", Health.UNKNOWN,
                    "No coding harness selected (built-in local agent fallback)", now));
        }

        // --- PROVIDER ---
        if (providerRegistry != null) {
            required.add(ComponentId.PROVIDER);
            Optional<String> defaultProviderId = safeGet(providerRegistry::getDefaultProviderId);
            if (defaultProviderId.isEmpty()) {
                // No default provider selected yet: fall back to any enabled provider that
                // is ONLINE in the shared model catalog (mirrors the /api/health rule).
                // Reported as DEGRADED so the configuration gap stays visible without
                // blocking a working installation.
                java.util.List<AiModelRegistryService.AiProviderInfo> infos =
                        modelRegistry != null ? modelRegistry.getAllProviders() : java.util.List.of();
                Optional<AiModelRegistryService.AiProviderInfo> online = infos.stream()
                        .filter(AiModelRegistryService.AiProviderInfo::enabled)
                        .filter(i -> i.health() == AiModelRegistryService.ProviderHealth.ONLINE)
                        .findFirst();
                if (online.isPresent()) {
                    components.put(ComponentId.PROVIDER, new ComponentState(ComponentId.PROVIDER,
                            "Inference Provider", Health.DEGRADED,
                            "No default provider selected; using reachable provider \""
                                    + online.get().name() + "\" (" + online.get().id() + ")", now));
                } else {
                    allChecksPassed = false;
                    components.put(ComponentId.PROVIDER, new ComponentState(ComponentId.PROVIDER,
                            "Inference Provider", Health.UNAVAILABLE,
                            "No default inference provider configured and no reachable provider detected", now));
                    problems.add("No reachable inference provider is configured.");
                }
            } else {
                String providerId = defaultProviderId.get();
                AiProviderRegistry.ConnectionStatus status = withTimeout(
                        () -> providerRegistry.testConnection(providerId), checkTimeout,
                        AiProviderRegistry.ConnectionStatus.error("Connection test timed out"));
                String providerName = providerId;
                try {
                    providerName = providerRegistry.findProvider(providerId)
                            .map(p -> p.name() != null && !p.name().isBlank() ? p.name() : p.id())
                            .orElse(providerId);
                } catch (Exception ignored) {}
                if (status != null && status.success()) {
                    components.put(ComponentId.PROVIDER, new ComponentState(ComponentId.PROVIDER,
                            "Inference Provider", Health.OPERATIONAL,
                            providerName + ": " + status.message(), now));
                    if (!providerWasConnected) {
                        // Recovery: the provider just came back; refresh the model catalog
                        // so the model indicator recovers without an operator round-trip.
                        providerWasConnected = true;
                        withModelDiscovery = true;
                        TrystLog.info(ExecutionEnvironmentMonitor.class,
                                "Inference provider connectivity recovered; refreshing model catalog");
                    }
                } else {
                    allChecksPassed = false;
                    providerWasConnected = false;
                    String message = status != null ? status.message() : "Provider unreachable";
                    components.put(ComponentId.PROVIDER, new ComponentState(ComponentId.PROVIDER,
                            "Inference Provider", Health.UNAVAILABLE,
                            providerName + ": " + message, now));
                    problems.add("Inference provider " + providerName + " is unreachable: " + message);
                }
            }
        } else {
            components.put(ComponentId.PROVIDER, new ComponentState(ComponentId.PROVIDER,
                    "Inference Provider", Health.UNKNOWN,
                    "Provider registry is not available", now));
        }

        // --- MODEL ---
        if (modelRegistry != null) {
            required.add(ComponentId.MODEL);
            if (withModelDiscovery && providerRegistry != null) {
                try {
                    Optional<String> defaultProviderId = safeGet(providerRegistry::getDefaultProviderId);
                    defaultProviderId.ifPresent(id -> providerRegistry.discoverModels(id));
                } catch (Exception e) {
                    TrystLog.warn(ExecutionEnvironmentMonitor.class,
                            "Model discovery during environment check failed: " + e.getMessage());
                }
            }
            // The authoritative selected model comes from the unified execution
            // configuration resolver (never the legacy catalog default).
            String selectedModel = resolveSelectedModel();
            List<AiModelRegistryService.PublishedModel> published;
            try {
                published = modelRegistry.getAllModels();
            } catch (Exception e) {
                published = List.of();
            }
            boolean selectedAvailable = false;
            AiModelRegistryService.PublishedModel match = null;
            if (selectedModel != null && !selectedModel.isBlank()) {
                for (AiModelRegistryService.PublishedModel m : published) {
                    if (m.available() && selectedModel.equals(m.modelIdentifier())) {
                        selectedAvailable = true;
                        match = m;
                        break;
                    }
                }
            }
            if (selectedModel == null || selectedModel.isBlank()) {
                allChecksPassed = false;
                components.put(ComponentId.MODEL, new ComponentState(ComponentId.MODEL,
                        "Model", Health.UNAVAILABLE, "No model selected", now));
                problems.add("No model is selected for execution.");
            } else if (selectedAvailable) {
                String providerPart = match != null && match.providerId() != null ? match.providerId() : "";
                components.put(ComponentId.MODEL, new ComponentState(ComponentId.MODEL,
                        "Model", Health.OPERATIONAL,
                        "Selected model \"" + selectedModel + "\" available"
                                + (providerPart.isBlank() ? "" : " via " + providerPart), now));
            } else {
                // Single source of truth: a missing selected model blocks execution.
                // TRYST never silently substitutes another model.
                allChecksPassed = false;
                components.put(ComponentId.MODEL, new ComponentState(ComponentId.MODEL,
                        "Model", Health.UNAVAILABLE,
                        "Selected model \"" + selectedModel + "\" is not installed", now));
                problems.add("Selected model \"" + selectedModel + "\" is not installed.");
            }
        } else {
            components.put(ComponentId.MODEL, new ComponentState(ComponentId.MODEL,
                    "Model", Health.UNKNOWN, "Model registry is not available", now));
        }

        // --- Rollup ---
        if (allChecksPassed) {
            lastSuccess = now;
        } else {
            lastFailure = now;
        }
        boolean ready = problems.isEmpty();
        String readyMessage = ready
                ? "Execution environment is ready."
                : "Execution environment is not ready: " + String.join(" ", problems);
        List<String> sortedProblems = new ArrayList<>(problems);
        Collections.sort(sortedProblems);
        String episodeKey = ready ? "READY" : "BLOCKED:" + String.join("|", sortedProblems);

        return new EnvironmentSnapshot(now, components, problems, required, ready,
                readyMessage, lastSuccess, lastFailure, episodeKey);
    }

    private static <T> Optional<T> safeGet(Supplier<Optional<T>> supplier) {
        try {
            Optional<T> value = supplier.get();
            return value == null ? Optional.empty() : value;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String safeName(String name, String fallback) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return fallback != null ? fallback : "unknown";
    }

    /**
     * Executes an external check with a hard timeout bound. A hanging endpoint can
     * therefore never stall the monitoring cycle indefinitely.
     */
    private <T> T withTimeout(Callable<T> task, Duration timeout, T fallback) {
        try {
            Future<T> future = checkExecutor.submit(task);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                TrystLog.warn(ExecutionEnvironmentMonitor.class,
                        "Environment check timed out after " + timeout.toMillis() + " ms");
                return fallback;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            TrystLog.warn(ExecutionEnvironmentMonitor.class,
                    "Environment check failed: " + e.getMessage());
            return fallback;
        }
    }
}
