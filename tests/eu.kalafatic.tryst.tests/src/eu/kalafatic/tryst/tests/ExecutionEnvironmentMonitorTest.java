package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.AIProvider;
import eu.kalafatic.tryst.domain.env.EnvironmentEvents;
import eu.kalafatic.tryst.domain.env.ExecutionEnvironmentMonitor;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution environment monitoring and task blocking/release verification:
 * - component health evaluation (server, harness, provider, model),
 * - authoritative readiness and problem reporting,
 * - recovery detection via readiness events,
 * - incoming tasks blocked when the environment is not ready and released on recovery.
 */
public class ExecutionEnvironmentMonitorTest {

    private static final class FakeHarnessAdapter implements CodingHarnessAdapter {
        private final String id;
        private final String name;
        private volatile boolean connectionOk;

        FakeHarnessAdapter(String id, String name, boolean connectionOk) {
            this.id = id;
            this.name = name;
            this.connectionOk = connectionOk;
        }

        void setConnectionOk(boolean ok) {
            this.connectionOk = ok;
        }

        @Override
        public String getApplicationId() {
            return id;
        }

        @Override
        public String getApplicationName() {
            return name;
        }

        @Override
        public String getApplicationType() {
            return "TEST";
        }

        @Override
        public List<String> getCapabilities() {
            return List.of("CODE_IMPLEMENTATION");
        }

        @Override
        public boolean isAvailable() {
            return connectionOk;
        }

        @Override
        public ConnectionTestResult testConnection() {
            return connectionOk
                    ? ConnectionTestResult.ok(name + " reachable")
                    : ConnectionTestResult.error(name + " is stopped");
        }

        @Override
        public HarnessExecutionResult executeTask(Task task, eu.kalafatic.tryst.domain.model.Workspace workspace,
                                                   String instructions, String prompt,
                                                   java.util.function.Consumer<String> logConsumer,
                                                   java.util.function.BooleanSupplier cancellationSupplier,
                                                   Duration timeout) {
            return HarnessExecutionResult.notHandled();
        }
    }

    private static final class FakeHarnessRegistry implements ConnectedApplicationRegistry {
        private final FakeHarnessAdapter adapter;
        private boolean selected = true;

        FakeHarnessRegistry(FakeHarnessAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public void registerAdapter(CodingHarnessAdapter adapter) {}

        @Override
        public void unregisterApplication(String applicationId) {}

        @Override
        public List<ConnectedApplicationDescriptor> listApplications() {
            return adapter == null ? List.of() : List.of(new ConnectedApplicationDescriptor(
                    adapter.getApplicationId(), adapter.getApplicationName(), adapter.getApplicationType(),
                    adapter.getCapabilities(), true, ConnectedApplicationDescriptor.ApplicationStatus.UNKNOWN,
                    "", java.time.Instant.now(), java.time.Instant.now()));
        }

        @Override
        public Optional<ConnectedApplicationDescriptor> findApplication(String applicationId) {
            return listApplications().stream().filter(a -> a.getId().equals(applicationId)).findFirst();
        }

        @Override
        public Optional<CodingHarnessAdapter> findAdapter(String applicationId) {
            return adapter != null && adapter.getApplicationId().equals(applicationId)
                    ? Optional.of(adapter) : Optional.empty();
        }

        @Override
        public void selectApplication(String applicationId) {
            selected = applicationId != null && !applicationId.isBlank();
        }

        @Override
        public Optional<String> getSelectedApplicationId() {
            return selected && adapter != null ? Optional.of(adapter.getApplicationId()) : Optional.empty();
        }

        @Override
        public Optional<CodingHarnessAdapter> findSelectedAdapter() {
            if (!selected || adapter == null) {
                return Optional.empty();
            }
            return adapter.isAvailable() ? Optional.of(adapter) : Optional.of(adapter);
        }

        @Override
        public CodingHarnessAdapter.ConnectionTestResult testConnection(String applicationId) {
            return adapter != null ? adapter.testConnection()
                    : CodingHarnessAdapter.ConnectionTestResult.error("No adapter");
        }

        @Override
        public void updateConfiguration(String applicationId, Map<String, String> configuration) {}

        @Override
        public void setApplicationEnabled(String applicationId, boolean enabled) {}

        @Override
        public void clearAll() {}
    }

    private static final class FakeProviderRegistry implements AiProviderRegistry {
        private volatile boolean connectionOk = true;
        private final AiProviderDescriptor descriptor = new AiProviderDescriptor(
                "test-provider", "Test Provider", "TEST", "http://localhost:1",
                true, "", Map.of(), java.time.Instant.now(), java.time.Instant.now());

        void setConnectionOk(boolean ok) {
            this.connectionOk = ok;
        }

        @Override
        public void registerProvider(String id, String name, String type, String endpoint, boolean enabled, AIProvider adapter) {}

        @Override
        public void unregisterProvider(String id) {}

        @Override
        public List<AiProviderDescriptor> listProviders() {
            return List.of(descriptor);
        }

        @Override
        public Optional<AiProviderDescriptor> findProvider(String id) {
            return descriptor.id().equals(id) ? Optional.of(descriptor) : Optional.empty();
        }

        @Override
        public Optional<AIProvider> findAdapter(String id) {
            return Optional.empty();
        }

        @Override
        public void setProviderEnabled(String id, boolean enabled) {}

        @Override
        public void selectDefaultProvider(String id) {}

        @Override
        public void updateDefaultModel(String id, String modelId) {}

        @Override
        public Optional<String> getDefaultProviderId() {
            return Optional.of(descriptor.id());
        }

        @Override
        public Optional<AIProvider> getDefaultAdapter() {
            return Optional.empty();
        }

        @Override
        public void updateConfiguration(String id, Map<String, String> configuration) {}

        @Override
        public Map<String, String> getConfiguration(String id) {
            return Map.of();
        }

        @Override
        public ConnectionStatus testConnection(String id) {
            return connectionOk
                    ? ConnectionStatus.ok("Connected")
                    : ConnectionStatus.error("Connection refused");
        }

        @Override
        public CompletableFuture<Void> discoverModels(String id) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private EventBus eventBus;
    private FakeHarnessAdapter harnessAdapter;
    private FakeHarnessRegistry harnessRegistry;
    private FakeProviderRegistry providerRegistry;
    private AiModelRegistryService modelRegistry;
    private ExecutionEnvironmentMonitor monitor;
    private final List<EnvironmentEvents.ReadinessChangedEvent> readinessEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setup() {
        eventBus = new EventBus();
        harnessAdapter = new FakeHarnessAdapter("test-harness", "Test Harness", true);
        harnessRegistry = new FakeHarnessRegistry(harnessAdapter);
        providerRegistry = new FakeProviderRegistry();
        modelRegistry = new AiModelRegistryService("test-model");
        modelRegistry.publishModel(new AiModelRegistryService.PublishedModel(
                "test-provider:test-model", "test-provider", "test-model", "Test Model",
                4096, true, true, UserRole.USER, java.time.Instant.now()));
        eventBus.subscribe(EnvironmentEvents.ReadinessChangedEvent.class, readinessEvents::add);
    }

    @AfterEach
    public void tearDown() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
    }

    private ExecutionEnvironmentMonitor createMonitor(boolean serverOperational) {
        monitor = new ExecutionEnvironmentMonitor(
                harnessRegistry,
                providerRegistry,
                modelRegistry,
                eventBus,
                () -> serverOperational,
                () -> serverOperational ? "Server operational" : "Server stopped",
                Duration.ofHours(1)
        );
        return monitor;
    }

    @Test
    public void testReadyEnvironmentAllComponentsOperational() {
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var snapshot = m.refreshNow();

        assertTrue(snapshot.ready(), "All components operational -> ready");
        assertEquals("READY", snapshot.episodeKey());
        assertTrue(snapshot.problems().isEmpty());
        assertEquals(ExecutionEnvironmentMonitor.Health.OPERATIONAL,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.SERVER).health());
        assertEquals(ExecutionEnvironmentMonitor.Health.OPERATIONAL,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.HARNESS).health());
        assertEquals(ExecutionEnvironmentMonitor.Health.OPERATIONAL,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.PROVIDER).health());
        assertEquals(ExecutionEnvironmentMonitor.Health.OPERATIONAL,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.MODEL).health());
        assertNotNull(snapshot.lastSuccessfulCheck());
        assertNull(snapshot.lastFailedCheck());
    }

    @Test
    public void testHarnessUnavailableBlocksReadiness() {
        harnessAdapter.setConnectionOk(false);
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var snapshot = m.refreshNow();

        assertFalse(snapshot.ready(), "Unavailable required harness -> NOT READY");
        assertEquals(ExecutionEnvironmentMonitor.Health.UNAVAILABLE,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.HARNESS).health());
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("Test Harness")),
                "Problem must name the missing harness");
        assertFalse(snapshot.episodeKey().startsWith("READY"));
        assertNotNull(snapshot.lastFailedCheck());
    }

    @Test
    public void testProviderUnavailableBlocksReadiness() {
        providerRegistry.setConnectionOk(false);
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var snapshot = m.refreshNow();

        assertFalse(snapshot.ready(), "Unreachable provider -> NOT READY");
        assertEquals(ExecutionEnvironmentMonitor.Health.UNAVAILABLE,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.PROVIDER).health());
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("Test Provider")));
    }

    @Test
    public void testModelUnavailableBlocksReadiness() {
        // Remove the published model: the selected model is no longer available.
        modelRegistry.removeModel("test-provider:test-model");
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var snapshot = m.refreshNow();

        assertFalse(snapshot.ready(), "Selected model unavailable and no fallback -> NOT READY");
        assertEquals(ExecutionEnvironmentMonitor.Health.UNAVAILABLE,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.MODEL).health());
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("test-model")));
    }

    @Test
    public void testSelectedModelUnavailableBlocksReadiness() {
        // Selected model "test-model" unavailable even though another model is
        // published: TRYST never silently substitutes another model, so the
        // environment is NOT READY and the model component is UNAVAILABLE.
        modelRegistry.removeModel("test-provider:test-model");
        modelRegistry.publishModel(new AiModelRegistryService.PublishedModel(
                "test-provider:fallback-model", "test-provider", "fallback-model", "Fallback Model",
                4096, true, true, UserRole.USER, java.time.Instant.now()));
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var snapshot = m.refreshNow();

        assertFalse(snapshot.ready(), "a missing selected model must block readiness (no substitution)");
        assertEquals(ExecutionEnvironmentMonitor.Health.UNAVAILABLE,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.MODEL).health());
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.contains("test-model")));
    }

    @Test
    public void testServerUnavailableBlocksReadiness() {
        ExecutionEnvironmentMonitor m = createMonitor(false);
        var snapshot = m.refreshNow();

        assertFalse(snapshot.ready(), "Server not operational -> NOT READY");
        assertEquals(ExecutionEnvironmentMonitor.Health.UNAVAILABLE,
                snapshot.component(ExecutionEnvironmentMonitor.ComponentId.SERVER).health());
    }

    @Test
    public void testRecoveryDetectionPublishesReadyTransition() {
        harnessAdapter.setConnectionOk(false);
        ExecutionEnvironmentMonitor m = createMonitor(true);
        var notReady = m.refreshNow();
        assertFalse(notReady.ready());

        // Operator restores the harness; the next check must publish recovery.
        harnessAdapter.setConnectionOk(true);
        var recovered = m.refreshNow();
        assertTrue(recovered.ready());

        var readyEvents = readinessEvents.stream().filter(EnvironmentEvents.ReadinessChangedEvent::ready).toList();
        assertFalse(readyEvents.isEmpty(), "Recovery must publish a ready=true readiness event");
    }

    @Test
    public void testBlockedTaskFlowAndRelease(@TempDir Path tempDir) throws InterruptedException {
        Path dbPath = tempDir.resolve("env_block_test.db");
        SqliteDatabaseManager databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        SqliteProjectRepository projectRepository = new SqliteProjectRepository(databaseManager);
        SqliteTaskRepository taskRepository = new SqliteTaskRepository(databaseManager);
        EventBus localBus = new EventBus();
        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, localBus);
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        TaskOrchestrator orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, null, projectRepository,
                null, null, TestAiFakes.codingAi(), null, null, null, null, null, 1);
        TestAiFakes.configureModelSelection(orchestrator, "test-model");
        orchestrator.setEventBus(localBus);
        orchestrator.start();

        // Environment unavailable: harness stopped.
        harnessAdapter.setConnectionOk(false);
        ExecutionEnvironmentMonitor blockingMonitor = new ExecutionEnvironmentMonitor(
                harnessRegistry, providerRegistry, modelRegistry, localBus,
                () -> true, () -> "Server operational", Duration.ofHours(1));
        orchestrator.setExecutionEnvironmentMonitor(blockingMonitor);
        blockingMonitor.refreshNow();
        assertFalse(blockingMonitor.isReady());

        // The task must be accepted and persisted, but BLOCKED (never enqueued).
        Task task = orchestrator.submitTask(null, null, "hi hi", "Test task",
                TaskPriority.NORMAL, "dev-user", null);
        assertNotNull(task);
        assertEquals(TaskStatus.BLOCKED, task.getStatus(), "Task must be blocked when environment is not ready");
        assertEquals(0, orchestrator.getQueueDepth());
        assertEquals(TaskStatus.BLOCKED, taskRepository.findTaskById(task.getId()).orElseThrow().getStatus());
        assertFalse(taskRepository.findTaskById(task.getId()).orElseThrow().getFailureReason().isBlank(),
                "Block reason must be persisted");

        // Recovery: harness restored, readiness re-evaluated -> task released to QUEUED.
        harnessAdapter.setConnectionOk(true);
        blockingMonitor.refreshNow();
        assertTrue(blockingMonitor.isReady());

        long deadline = System.currentTimeMillis() + 5000;
        Task released = null;
        while (System.currentTimeMillis() < deadline) {
            var current = taskRepository.findTaskById(task.getId()).orElseThrow();
            if (current.getStatus() == TaskStatus.QUEUED || current.getStatus() == TaskStatus.CLAIMED) {
                released = current;
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(released, "Task must be released after environment recovery");
        assertNotEquals(TaskStatus.BLOCKED, released.getStatus());

        orchestrator.shutdown();
        blockingMonitor.stop();
        databaseManager.close();
    }

    @Test
    public void testStatusChangeAndBlockedEventsArePublished(@TempDir Path tempDir) {
        List<EnvironmentEvents.StatusChangedEvent> statusEvents = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EnvironmentEvents.StatusChangedEvent.class, statusEvents::add);
        List<EnvironmentEvents.TaskBlockedEvent> blockedEvents = new CopyOnWriteArrayList<>();
        eventBus.subscribe(EnvironmentEvents.TaskBlockedEvent.class, blockedEvents::add);

        ExecutionEnvironmentMonitor m = createMonitor(true);
        m.refreshNow();
        assertFalse(statusEvents.isEmpty(), "First assessment publishes a status event");

        // TaskBlockedEvent is published by the orchestrator flow; verify the record contract here.
        EnvironmentEvents.TaskBlockedEvent event = new EnvironmentEvents.TaskBlockedEvent(
                "t1", "Sample", "Environment not ready", List.of("Harness down"), "BLOCKED:Harness down");
        assertEquals("t1", event.taskId());
        assertEquals("BLOCKED:Harness down", event.episodeKey());
        assertEquals(List.of("Harness down"), event.problems());
    }
}
