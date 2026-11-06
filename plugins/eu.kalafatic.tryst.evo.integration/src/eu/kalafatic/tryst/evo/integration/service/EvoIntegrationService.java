package eu.kalafatic.tryst.evo.integration.service;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.EvoExecutionEvent;
import eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository;
import eu.kalafatic.tryst.domain.repository.EvoExecutionRepository;
import eu.kalafatic.tryst.evo.integration.client.TeipClient;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Cohesive service coordinating TEIP 1.0 EVO integration lifecycle, execution tracking,
 * event replay, reconciliation after restart, and idempotency guarantees.
 */
public class EvoIntegrationService {

    private final EvoConfigurationRepository configurationRepository;
    private final EvoExecutionRepository executionRepository;
    private final TeipClient client;
    private final ScheduledExecutorService pollScheduler;
    private final Map<String, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();

    public EvoIntegrationService(
            EvoConfigurationRepository configurationRepository,
            EvoExecutionRepository executionRepository,
            TeipClient client
    ) {
        this.configurationRepository = Objects.requireNonNull(configurationRepository, "configurationRepository must not be null");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository must not be null");
        this.client = client != null ? client : new TeipClient();
        this.pollScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "tryst-evo-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public EvoConfiguration getConfiguration() {
        return configurationRepository.getConfiguration().orElseGet(EvoConfiguration::createDefault);
    }

    public void updateConfiguration(String endpoint, String authToken, boolean enabled, int timeoutSeconds, int maxRetries) {
        EvoConfiguration config = getConfiguration();
        config.setEndpoint(endpoint);
        config.setAuthToken(authToken);
        config.setEnabled(enabled);
        config.setTimeoutSeconds(timeoutSeconds);
        config.setMaxRetries(maxRetries);
        configurationRepository.save(config);
        TrystLog.info(EvoIntegrationService.class, "Updated EVO integration configuration: endpoint=" + endpoint + ", enabled=" + enabled);
    }

    public TeipHealthResponse checkHealth() throws Exception {
        EvoConfiguration config = getConfiguration();
        return client.checkHealth(config.getEndpoint(), config.getAuthToken());
    }

    public TeipCapabilitiesResponse getCapabilities() throws Exception {
        EvoConfiguration config = getConfiguration();
        return client.getCapabilities(config.getEndpoint(), config.getAuthToken());
    }

    public EvoExecution createExecution(
            String trystTaskId,
            String trystSessionId,
            String capability,
            String aiMode,
            String model,
            String instruction,
            List<String> contextReferences,
            List<String> mcpToolScopes,
            String idempotencyKey
    ) throws Exception {
        EvoConfiguration config = getConfiguration();
        if (!config.isEnabled()) {
            throw new IllegalStateException("EVO integration is disabled in configuration.");
        }

        // Idempotency check: if an execution already exists for this idempotency key, return it
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<EvoExecution> existing = executionRepository.findExecutionByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                TrystLog.info(EvoIntegrationService.class, "Reusing existing execution for idempotency key: " + idempotencyKey);
                return existing.get();
            }
        }

        EvoExecution execution = EvoExecution.create(trystTaskId, trystSessionId, capability, aiMode, idempotencyKey);
        executionRepository.saveExecution(execution);

        TeipExecutionRequest req = new TeipExecutionRequest(
                "TEIP",
                "1.0",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                idempotencyKey,
                Instant.now().toString(),
                new TeipCaller("TRYST", "default", trystTaskId),
                capability,
                aiMode,
                model,
                instruction,
                contextReferences != null ? contextReferences : List.of(),
                mcpToolScopes != null ? mcpToolScopes : List.of(),
                Map.of()
        );

        try {
            TeipExecutionResponse resp = client.createExecution(config.getEndpoint(), config.getAuthToken(), req);
            execution.setEvoExecutionId(resp.executionId());
            execution.setEvoSessionId(resp.evoSessionId());
            execution.updateStatus(EvoExecution.ExecutionStatus.valueOf(resp.status()), "QUEUED", 0.0);
            executionRepository.saveExecution(execution);

            // Start background poller for this execution
            startPoller(execution.getId(), resp.executionId());
            return execution;
        } catch (Exception e) {
            execution.updateStatus(EvoExecution.ExecutionStatus.FAILED, "CREATION_FAILED", 0.0);
            execution.setError("Failed to create remote EVO execution: " + e.getMessage());
            executionRepository.saveExecution(execution);
            throw e;
        }
    }

    public void cancelExecution(String localExecutionId) throws Exception {
        Optional<EvoExecution> opt = executionRepository.findExecutionById(localExecutionId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Execution not found: " + localExecutionId);
        }
        EvoExecution exec = opt.get();
        EvoConfiguration config = getConfiguration();

        if (exec.getEvoExecutionId() != null && !exec.getEvoExecutionId().isBlank()) {
            try {
                client.cancelExecution(config.getEndpoint(), config.getAuthToken(), exec.getEvoExecutionId());
            } catch (Exception e) {
                TrystLog.warn(EvoIntegrationService.class, "Remote cancellation failed, setting local cancelled state: " + e.getMessage());
            }
        }
        exec.updateStatus(EvoExecution.ExecutionStatus.CANCELLED, "CANCELLED", exec.getProgress());
        executionRepository.saveExecution(exec);
        stopPoller(localExecutionId);
    }

    public Optional<EvoExecution> getExecution(String localExecutionId) {
        return executionRepository.findExecutionById(localExecutionId);
    }

    public List<EvoExecution> getExecutionsForTask(String trystTaskId) {
        return executionRepository.findExecutionsByTaskId(trystTaskId);
    }

    public List<EvoExecution> getAllExecutions() {
        return executionRepository.findAllExecutions();
    }

    public List<EvoExecutionEvent> getExecutionEvents(String localExecutionId, long afterSequence, int limit) {
        return executionRepository.findEventsByExecutionId(localExecutionId, afterSequence, limit);
    }

    public TeipExecutionResult getRemoteResult(String localExecutionId) throws Exception {
        Optional<EvoExecution> opt = executionRepository.findExecutionById(localExecutionId);
        if (opt.isEmpty()) throw new IllegalArgumentException("Execution not found: " + localExecutionId);
        EvoExecution exec = opt.get();
        if (exec.getEvoExecutionId() == null) throw new IllegalStateException("Execution has no remote EVO ID");
        EvoConfiguration config = getConfiguration();
        return client.getExecutionResult(config.getEndpoint(), config.getAuthToken(), exec.getEvoExecutionId());
    }

    public void reconcileUnfinishedExecutions() {
        List<EvoExecution> all = executionRepository.findAllExecutions();
        for (EvoExecution exec : all) {
            if (exec.getStatus() == EvoExecution.ExecutionStatus.RUNNING || exec.getStatus() == EvoExecution.ExecutionStatus.QUEUED || exec.getStatus() == EvoExecution.ExecutionStatus.WAITING) {
                if (exec.getEvoExecutionId() != null) {
                    TrystLog.info(EvoIntegrationService.class, "Reconciling active EVO execution: " + exec.getId() + " (evo: " + exec.getEvoExecutionId() + ")");
                    startPoller(exec.getId(), exec.getEvoExecutionId());
                }
            }
        }
    }

    private synchronized void startPoller(String localId, String evoId) {
        if (activePollers.containsKey(localId)) return;
        ScheduledFuture<?> future = pollScheduler.scheduleWithFixedDelay(() -> {
            try {
                pollExecutionProgress(localId, evoId);
            } catch (Exception e) {
                TrystLog.warn(EvoIntegrationService.class, "Polling error for execution " + localId + ": " + e.getMessage());
            }
        }, 1, 2, TimeUnit.SECONDS);
        activePollers.put(localId, future);
    }

    private synchronized void stopPoller(String localId) {
        ScheduledFuture<?> future = activePollers.remove(localId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void pollExecutionProgress(String localId, String evoId) {
        Optional<EvoExecution> opt = executionRepository.findExecutionById(localId);
        if (opt.isEmpty()) {
            stopPoller(localId);
            return;
        }
        EvoExecution exec = opt.get();
        EvoConfiguration config = getConfiguration();

        try {
            // 1. Fetch remote status
            TeipExecutionStatus status = client.getExecutionStatus(config.getEndpoint(), config.getAuthToken(), evoId);
            EvoExecution.ExecutionStatus externalStatus = EvoExecution.ExecutionStatus.valueOf(status.status());
            exec.updateStatus(externalStatus, status.phase(), status.progress());

            // 2. Fetch and replay sequential events
            long lastSeq = executionRepository.getLatestSequence(localId);
            TeipEventsResponse eventsResp = client.getExecutionEvents(config.getEndpoint(), config.getAuthToken(), evoId, lastSeq, 100);

            for (TeipEvent evt : eventsResp.events()) {
                EvoExecutionEvent domainEvt = new EvoExecutionEvent(
                        evt.eventId(),
                        localId,
                        evt.sequence(),
                        evt.type(),
                        evt.correlationId(),
                        Instant.parse(evt.timestamp()),
                        evt.dataJson()
                );
                executionRepository.saveEvent(domainEvt);
            }
            exec.setLastSequence(eventsResp.latestSequence());

            // 3. If finished, retrieve structured result and stop poller
            if (externalStatus == EvoExecution.ExecutionStatus.COMPLETED) {
                try {
                    TeipExecutionResult res = client.getExecutionResult(config.getEndpoint(), config.getAuthToken(), evoId);
                    exec.setSummary(res.summary());
                } catch (Exception ignored) {}
                stopPoller(localId);
            } else if (externalStatus == EvoExecution.ExecutionStatus.FAILED || externalStatus == EvoExecution.ExecutionStatus.CANCELLED) {
                stopPoller(localId);
            }

            executionRepository.saveExecution(exec);
        } catch (Exception e) {
            TrystLog.warn(EvoIntegrationService.class, "Failed to poll EVO execution " + evoId + ": " + e.getMessage());
        }
    }

    public void stop() {
        pollScheduler.shutdownNow();
    }
}
