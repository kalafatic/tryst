package eu.kalafatic.tryst.evo.integration.service;

import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.model.DStudioConfiguration;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.DStudioConfigurationRepository;
import eu.kalafatic.tryst.evo.integration.client.TeipClient;
import eu.kalafatic.tryst.evo.integration.model.TeipModels;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.TeipCaller;
import eu.kalafatic.tryst.evo.integration.model.TeipModels.TeipExecutionRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * DStudio coding-harness adapter (Phase 4, WP-4.10): delegates TRYST task implementation
 * to the DStudio Harness Platform through the canonical TEIP 1.1 protocol. DStudio owns
 * harness adapters (OpenCode, Aider) and execution lifecycle; TRYST retains task
 * sovereignty, sandbox preparation, review and Git finalization.
 */
public class DStudioCodingHarnessAdapter implements CodingHarnessAdapter {

    public static final String APPLICATION_ID = "dstudio";
    public static final String APPLICATION_NAME = "DStudio Harness Platform";
    public static final String APPLICATION_TYPE = "DSTUDIO";

    public static final String DEFAULT_ENDPOINT = "http://localhost:48080";
    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_AUTH_TOKEN = "authToken";
    public static final String CONFIG_HARNESS = "harness";
    public static final String CONFIG_MODEL = "model";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";

    private final TeipClient teipClient = new TeipClient();
    private final DStudioConfigurationRepository configurationRepository;
    private final eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService
            executionConfigurationService;
    private volatile String endpoint = DEFAULT_ENDPOINT;
    private volatile String authToken = "";
    private volatile String harness = "";
    private volatile String model = "";
    private volatile boolean enabled = true;
    private volatile long timeoutSeconds = 1800L;
    private volatile boolean hydrated = false;

    public DStudioCodingHarnessAdapter() {
        this(null);
    }

    public DStudioCodingHarnessAdapter(
            DStudioConfigurationRepository configurationRepository) {
        this(configurationRepository, null);
    }

    public DStudioCodingHarnessAdapter(
            DStudioConfigurationRepository configurationRepository,
            eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService
                    executionConfigurationService) {
        this.configurationRepository = configurationRepository;
        this.executionConfigurationService = executionConfigurationService;
    }

    /**
     * Resolves the effective execution environment for a task: task-level overrides
     * (GLOBAL → PROJECT → TASK) win over the adapter defaults; DStudio validates
     * compatibility on its side and never silently switches.
     */
    private eu.kalafatic.tryst.domain.ai.ExecutionConfiguration taskConfiguration(Task task) {
        if (executionConfigurationService == null || task == null || task.getId() == null) {
            return null;
        }
        return executionConfigurationService.resolve(task.getProjectId(), task.getId());
    }

    private synchronized void hydrateFromPersistence() {
        if (hydrated || configurationRepository == null) {
            return;
        }
        hydrated = true;
        configurationRepository.findById("default").ifPresent(config -> {
            this.endpoint = config.getEndpoint();
            this.harness = config.getHarness();
            this.model = config.getModel();
            this.enabled = config.isEnabled();
            this.timeoutSeconds = config.getTimeoutSeconds();
        });
    }

    private void persistConfiguration() {
        if (configurationRepository == null) {
            return;
        }
        DStudioConfiguration config = new DStudioConfiguration(
                "default", endpoint, harness, model, enabled,
                (int) timeoutSeconds, java.time.Instant.now());
        configurationRepository.save(config);
    }

    @Override
    public String getApplicationId() {
        return APPLICATION_ID;
    }

    @Override
    public String getApplicationName() {
        return APPLICATION_NAME;
    }

    @Override
    public String getApplicationType() {
        return APPLICATION_TYPE;
    }

    @Override
    public List<String> getCapabilities() {
        return List.of("CODE_IMPLEMENTATION", "CODE_ANALYSIS");
    }

    @Override
    public boolean isAvailable() {
        hydrateFromPersistence();
        if (!enabled) {
            return false;
        }
        return testConnection().success();
    }

    @Override
    public ConnectionTestResult testConnection() {
        hydrateFromPersistence();
        if (!enabled) {
            return ConnectionTestResult.error(
                    "DStudio Harness Platform connection is disabled");
        }
        try {
            var health = teipClient.checkHealth(endpoint, authToken);
            String status = health.status() == null ? "DOWN" : health.status();
            if ("UP".equals(status) || "DEGRADED".equals(status)) {
                return ConnectionTestResult.ok("DStudio Harness Platform available at "
                        + endpoint + " (status " + status + ")");
            }
            return ConnectionTestResult.error("DStudio at " + endpoint
                    + " reports status " + status);
        } catch (Exception e) {
            return ConnectionTestResult.error("DStudio Harness Platform unreachable at "
                    + endpoint + ": " + e.getMessage());
        }
    }

    @Override
    public void updateConnectionConfiguration(Map<String, String> configuration) {
        hydrateFromPersistence();
        if (configuration == null) {
            return;
        }
        if (configuration.containsKey(CONFIG_ENDPOINT)
                && configuration.get(CONFIG_ENDPOINT) != null
                && !configuration.get(CONFIG_ENDPOINT).isBlank()) {
            this.endpoint = configuration.get(CONFIG_ENDPOINT).trim();
        }
        if (configuration.containsKey(CONFIG_AUTH_TOKEN)) {
            this.authToken = configuration.get(CONFIG_AUTH_TOKEN) == null ? ""
                    : configuration.get(CONFIG_AUTH_TOKEN).trim();
        }
        if (configuration.containsKey(CONFIG_HARNESS)) {
            this.harness = configuration.get(CONFIG_HARNESS) == null ? ""
                    : configuration.get(CONFIG_HARNESS).trim();
        }
        if (configuration.containsKey(CONFIG_MODEL)) {
            this.model = configuration.get(CONFIG_MODEL) == null ? ""
                    : configuration.get(CONFIG_MODEL).trim();
        }
        if (configuration.containsKey("enabled")) {
            this.enabled = Boolean.parseBoolean(configuration.get("enabled"));
        }
        if (configuration.containsKey(CONFIG_TIMEOUT_SECONDS)) {
            try {
                this.timeoutSeconds = Long.parseLong(
                        configuration.get(CONFIG_TIMEOUT_SECONDS));
            } catch (NumberFormatException ignored) {
                this.timeoutSeconds = 1800L;
            }
        }
        persistConfiguration();
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        hydrateFromPersistence();
        Map<String, String> config = new LinkedHashMap<>();
        config.put(CONFIG_ENDPOINT, endpoint);
        config.put(CONFIG_AUTH_TOKEN, authToken.isBlank() ? ""
                : "****** (configured)");
        config.put(CONFIG_HARNESS, harness);
        config.put(CONFIG_MODEL, model);
        config.put("enabled", String.valueOf(enabled));
        config.put(CONFIG_TIMEOUT_SECONDS, String.valueOf(timeoutSeconds));
        return config;
    }

    @Override
    public HarnessExecutionResult executeTask(Task task, Workspace workspace,
            String instructions, String prompt, Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier, Duration timeout) {
        hydrateFromPersistence();
        if (!enabled) {
            return HarnessExecutionResult.error(
                    "DStudio Harness Platform connection is disabled");
        }
        if (workspace == null || workspace.getRootPath() == null) {
            return HarnessExecutionResult.error("DStudio delegation requires a workspace");
        }
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(timeoutSeconds)
                : Duration.ofSeconds(Math.min(timeout.toSeconds(), timeoutSeconds));
        try {
            eu.kalafatic.tryst.domain.ai.ExecutionConfiguration taskConfig =
                    taskConfiguration(task);
            String effectiveModel = model;
            String effectiveHarness = harness;
            String effectiveProvider = "";
            String effectiveAgent = "";
            if (taskConfig != null) {
                if (taskConfig.harnessId() != null && !taskConfig.harnessId().isBlank()) {
                    effectiveHarness = taskConfig.harnessId();
                }
                if (taskConfig.modelId() != null && !taskConfig.modelId().isBlank()) {
                    effectiveModel = taskConfig.modelId();
                }
                effectiveProvider = taskConfig.providerId() == null ? ""
                        : taskConfig.providerId();
                effectiveAgent = taskConfig.agentProfileId() == null ? ""
                        : taskConfig.agentProfileId();
            }
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("workingDirectory", workspace.getRootPath().toString());
            if (effectiveHarness != null && !effectiveHarness.isBlank()) {
                parameters.put("harness", effectiveHarness);
            }
            if (!effectiveProvider.isBlank()) {
                parameters.put("provider", effectiveProvider);
            }
            if (!effectiveAgent.isBlank()) {
                parameters.put("agent", effectiveAgent);
            }
            parameters.put("timeoutSeconds", String.valueOf(
                    Math.min(effectiveTimeout.toSeconds(), 3600)));
            TeipExecutionRequest request = new TeipExecutionRequest(
                    "TEIP", "1.1",
                    "tryst-req-" + UUID.randomUUID().toString().replace("-", "")
                            .substring(0, 8),
                    "tryst-task-" + (task == null ? "unknown" : task.getId()),
                    "tryst-dstudio-" + Integer.toHexString(
                            workspace.getRootPath().toString().hashCode()),
                    Instant.now().toString(),
                    new TeipCaller("TRYST",
                            task == null ? "" : task.getId(),
                            task == null ? "" : task.getId()),
                    "CODE_IMPLEMENTATION", "DEFAULT",
                    effectiveModel == null || effectiveModel.isBlank() ? null : effectiveModel,
                    prompt != null && !prompt.isBlank() ? prompt : instructions,
                    List.of(), List.of("read:workspace", "write:workspace",
                            "execute:build"),
                    parameters);
            TeipModels.TeipExecutionResponse response =
                    teipClient.createExecution(endpoint, authToken, request);
            String executionId = response.executionId();
            logConsumer.accept("[DStudio] Execution submitted: " + executionId
                    + " (harness platform " + endpoint + ")");
            long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();
            long cursor = 0;
            while (true) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    teipClient.cancelExecution(endpoint, authToken, executionId);
                    return HarnessExecutionResult.cancelled(
                            "DStudio execution cancelled by TRYST");
                }
                if (System.currentTimeMillis() > deadline) {
                    teipClient.cancelExecution(endpoint, authToken, executionId);
                    return HarnessExecutionResult.timedOut(
                            "DStudio execution timed out after "
                                    + effectiveTimeout.toSeconds() + " s");
                }
                TeipModels.TeipEventsResponse events = teipClient.getExecutionEvents(
                        endpoint, authToken, executionId, cursor, 100);
                for (TeipModels.TeipEvent event : events.events()) {
                    cursor = Math.max(cursor, event.sequence());
                    String message = TeipClient.extractJsonString(event.dataJson(),
                            "message");
                    if (message == null || message.isBlank()) {
                        message = event.type();
                    }
                    logConsumer.accept("[DStudio] " + message);
                }
                TeipModels.TeipExecutionStatus status =
                        teipClient.getExecutionStatus(endpoint, authToken, executionId);
                String state = status.status();
                if ("COMPLETED".equals(state)) {
                    TeipModels.TeipExecutionResult result =
                            teipClient.getExecutionResult(endpoint, authToken,
                                    executionId);
                    List<String> modifiedFiles = result.modifiedFiles() == null
                            ? List.of() : result.modifiedFiles();
                    String summary = result.summary() == null ? "" : result.summary();
                    logConsumer.accept("[DStudio] Execution completed: " + summary
                            + " (modified files: " + modifiedFiles.size() + ")");
                    return HarnessExecutionResult.ok("DStudio execution completed: "
                            + summary, modifiedFiles);
                }
                if ("FAILED".equals(state)) {
                    TeipModels.TeipExecutionResult result =
                            teipClient.getExecutionResult(endpoint, authToken,
                                    executionId);
                    return HarnessExecutionResult.error("DStudio execution failed: "
                            + (result == null ? "unknown failure"
                                    : result.summary()));
                }
                if ("CANCELLED".equals(state)) {
                    return HarnessExecutionResult.cancelled(
                            "DStudio execution was cancelled");
                }
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HarnessExecutionResult.cancelled("DStudio delegation interrupted");
        } catch (Exception e) {
            return HarnessExecutionResult.error("DStudio delegation failed: "
                    + e.getMessage());
        }
    }
}
