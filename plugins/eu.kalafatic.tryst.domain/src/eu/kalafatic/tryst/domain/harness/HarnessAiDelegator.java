package eu.kalafatic.tryst.domain.harness;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService;
import eu.kalafatic.tryst.domain.ai.ExternalAiDelegator;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ExternalAiDelegator bridge over the connected-application registry (Phase 3, A-14).
 *
 * <p>Preserves the existing ExternalAiDelegator seam used by TaskWorker: when a connected
 * coding application (e.g. OpenCode, DCode, DStudio or EVO) is selected, enabled and
 * available, the implementation step is delegated to its coding-harness adapter; otherwise
 * the built-in local CodeAgent/CodeChangeExecutor fallback continues unchanged.</p>
 *
 * <p>Unified multi-harness dimension (Phase 9): a task-level execution configuration
 * (GLOBAL → PROJECT → TASK) can pin a specific harness per task; an explicitly configured
 * harness is never silently replaced by the global selection. When the configured harness
 * is unavailable the task fails with a clear error instead of switching environments.</p>
 */
public class HarnessAiDelegator implements ExternalAiDelegator {

    private final ConnectedApplicationRegistry registry;
    private final ExecutionConfigurationService executionConfigurationService;

    public HarnessAiDelegator(ConnectedApplicationRegistry registry) {
        this(registry, null);
    }

    public HarnessAiDelegator(ConnectedApplicationRegistry registry,
            ExecutionConfigurationService executionConfigurationService) {
        this.registry = registry;
        this.executionConfigurationService = executionConfigurationService;
    }

    /**
     * Resolves the harness for a task: task/project/global execution configuration first
     * (when a harness is explicitly configured), then the registry default selection.
     */
    public Optional<CodingHarnessAdapter> resolveAdapterForTask(Task task) {
        if (registry == null) {
            return Optional.empty();
        }
        String configuredHarness = "";
        if (executionConfigurationService != null && task != null && task.getId() != null) {
            ExecutionConfiguration resolved = executionConfigurationService.resolve(
                    task.getProjectId(), task.getId());
            if (resolved != null && resolved.harnessId() != null
                    && !resolved.harnessId().isBlank()) {
                configuredHarness = resolved.harnessId();
            }
        }
        if (!configuredHarness.isBlank()) {
            Optional<CodingHarnessAdapter> configured = registry.findAdapter(configuredHarness);
            if (configured.isPresent()) {
                return configured;
            }
            // Explicit task configuration wins: report unavailability, never fall back
            // silently to a different harness.
            return Optional.empty();
        }
        return registry.findSelectedAdapter();
    }

    @Override
    public boolean isAvailableForTask(Task task) {
        return resolveAdapterForTask(task).isPresent();
    }

    @Override
    public DelegationResult delegateImplementation(
            Task task,
            Workspace workspace,
            String instructions,
            String prompt,
            Consumer<String> logConsumer
    ) {
        if (registry == null) {
            return DelegationResult.notHandled();
        }
        String configuredHarness = "";
        ExecutionConfiguration resolvedConfig = null;
        if (executionConfigurationService != null && task != null && task.getId() != null) {
            resolvedConfig = executionConfigurationService.resolve(
                    task.getProjectId(), task.getId());
            if (resolvedConfig != null && resolvedConfig.harnessId() != null
                    && !resolvedConfig.harnessId().isBlank()) {
                configuredHarness = resolvedConfig.harnessId();
            }
        }
        Optional<CodingHarnessAdapter> selected = resolveAdapterForTask(task);
        if (selected.isEmpty()) {
            if (!configuredHarness.isBlank()) {
                return DelegationResult.error("Configured coding harness is unavailable: "
                        + configuredHarness);
            }
            return DelegationResult.notHandled();
        }
        CodingHarnessAdapter adapter = selected.get();
        // Persist the immutable execution-configuration snapshot BEFORE the harness starts:
        // later configuration changes never modify this execution, and the runtime
        // monitor reports the actual environment of the task.
        if (executionConfigurationService != null && task != null && task.getId() != null) {
            ExecutionConfiguration snapshot = resolvedConfig != null ? resolvedConfig
                    : executionConfigurationService.resolve(task.getProjectId(),
                            task.getId());
            executionConfigurationService.saveSnapshot(task.getId(), snapshot);
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        BooleanSupplier cancellationSupplier = () -> cancelled.get()
                || (task.getStatus() != null && isCancellationStatus(task.getStatus().name()));

        CodingHarnessAdapter.HarnessExecutionResult result;
        try {
            result = adapter.executeTask(
                    task,
                    workspace,
                    instructions,
                    prompt,
                    logConsumer != null ? logConsumer : s -> {},
                    cancellationSupplier,
                    Duration.ofMinutes(30)
            );
        } catch (Exception e) {
            TrystLog.error(HarnessAiDelegator.class,
                    "Coding harness " + adapter.getApplicationId() + " failed: " + e.getMessage(), e);
            return DelegationResult.error("Coding harness failed: " + e.getMessage());
        }

        if (!result.handled()) {
            return DelegationResult.notHandled();
        }
        if (result.timedOut()) {
            return DelegationResult.error("Coding harness timed out: " + result.errorMessage());
        }
        if (result.cancelled()) {
            return DelegationResult.error("Coding harness execution cancelled: " + result.errorMessage());
        }
        if (result.success()) {
            return DelegationResult.ok(result.summary());
        }
        return DelegationResult.error(result.errorMessage());
    }

    private static boolean isCancellationStatus(String statusName) {
        return "CANCELLED".equalsIgnoreCase(statusName) || "CANCELLING".equalsIgnoreCase(statusName);
    }
}
