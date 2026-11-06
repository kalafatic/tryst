package eu.kalafatic.tryst.evo.integration.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.ExternalAiDelegator;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.TeipExecutionResult;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ExternalAiDelegator bridge implementing TEIP 1.0 delegation for TaskWorker.
 */
public class EvoAiDelegator implements ExternalAiDelegator {

    private final EvoIntegrationService evoIntegrationService;

    public EvoAiDelegator(EvoIntegrationService evoIntegrationService) {
        this.evoIntegrationService = Objects.requireNonNull(evoIntegrationService, "evoIntegrationService must not be null");
    }

    @Override
    public boolean isAvailableForTask(Task task) {
        if (evoIntegrationService == null) return false;
        EvoConfiguration config = evoIntegrationService.getConfiguration();
        return config != null && config.isEnabled();
    }

    @Override
    public DelegationResult delegateImplementation(
            Task task,
            Workspace workspace,
            String instructions,
            String prompt,
            Consumer<String> logConsumer
    ) {
        try {
            if (logConsumer != null) {
                logConsumer.accept("[TEIP 1.0] Initiating autonomous execution with EVO Darwin Engine...\n");
            }
            String idemKey = "task-" + task.getId() + "-rework-" + task.getReworkCount() + "-" + UUID.randomUUID().toString().substring(0, 8);
            EvoExecution exec = evoIntegrationService.createExecution(
                    task.getId(),
                    task.getSessionId(),
                    "CODE_IMPLEMENTATION",
                    "DEFAULT",
                    null,
                    prompt,
                    List.of(),
                    List.of("read:workspace", "read:task", "write:workspace", "execute:build"),
                    idemKey
            );

            if (logConsumer != null) {
                logConsumer.accept("[TEIP 1.0] Created EVO execution: " + exec.getEvoExecutionId() + " (status: " + exec.getStatus() + ")\n");
            }

            // Wait for remote execution completion with bounded polling
            long start = System.currentTimeMillis();
            long maxWaitMs = 120_000; // 2 minutes max wait

            while (System.currentTimeMillis() - start < maxWaitMs) {
                Thread.sleep(1500);
                var opt = evoIntegrationService.getExecution(exec.getId());
                if (opt.isPresent()) {
                    EvoExecution current = opt.get();
                    if (current.getStatus() == EvoExecution.ExecutionStatus.COMPLETED) {
                        TeipExecutionResult res = null;
                        try {
                            res = evoIntegrationService.getRemoteResult(current.getId());
                        } catch (Exception ignored) {}
                        String summary = res != null ? res.summary() : current.getSummary();
                        if (logConsumer != null) {
                            logConsumer.accept("[TEIP 1.0] EVO execution completed. Summary: " + summary + "\n");
                        }
                        return DelegationResult.ok(summary != null && !summary.isBlank() ? summary : "EVO execution completed successfully.");
                    } else if (current.getStatus() == EvoExecution.ExecutionStatus.FAILED) {
                        return DelegationResult.error("EVO execution failed: " + current.getError());
                    } else if (current.getStatus() == EvoExecution.ExecutionStatus.CANCELLED) {
                        return DelegationResult.error("EVO execution was cancelled.");
                    }
                }
            }

            return DelegationResult.error("EVO execution timed out waiting for completion");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DelegationResult.error("Execution interrupted");
        } catch (Exception e) {
            TrystLog.error(EvoAiDelegator.class, "Delegation to EVO failed: " + e.getMessage(), e);
            return DelegationResult.error("Delegation failed: " + e.getMessage());
        }
    }
}
