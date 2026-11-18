package eu.kalafatic.tryst.domain.harness;

import eu.kalafatic.tryst.core.log.TrystLog;
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
 * coding application (e.g. OpenCode or EVO) is selected, enabled and available, the
 * implementation step is delegated to its coding-harness adapter; otherwise the built-in
 * local CodeAgent/CodeChangeExecutor fallback continues unchanged.</p>
 */
public class HarnessAiDelegator implements ExternalAiDelegator {

    private final ConnectedApplicationRegistry registry;

    public HarnessAiDelegator(ConnectedApplicationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isAvailableForTask(Task task) {
        if (registry == null) return false;
        return registry.findSelectedAdapter().isPresent();
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
        Optional<CodingHarnessAdapter> selected = registry.findSelectedAdapter();
        if (selected.isEmpty()) {
            return DelegationResult.notHandled();
        }
        CodingHarnessAdapter adapter = selected.get();

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
