package eu.kalafatic.tryst.evo.integration.service;

import eu.kalafatic.tryst.domain.ai.ExternalAiDelegator;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * EVO coding-harness adapter (Phase 3, A-14/A-15): exposes the existing TEIP-based
 * EvoAiDelegator through the connected-application coding-harness SPI. The local
 * CodeAgent/CodeChangeExecutor remains the built-in fallback; this adapter is additive.
 */
public class EvoCodingHarnessAdapter implements CodingHarnessAdapter {

    public static final String APPLICATION_ID = "evo";
    public static final String APPLICATION_NAME = "EVO Darwin Engine";
    public static final String APPLICATION_TYPE = "EVO";

    private final EvoIntegrationService evoIntegrationService;
    private final EvoAiDelegator delegator;

    public EvoCodingHarnessAdapter(EvoIntegrationService evoIntegrationService) {
        this.evoIntegrationService = evoIntegrationService;
        this.delegator = new EvoAiDelegator(evoIntegrationService);
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
        EvoConfiguration config = evoIntegrationService.getConfiguration();
        return config != null && config.isEnabled();
    }

    @Override
    public ConnectionTestResult testConnection() {
        try {
            var health = evoIntegrationService.checkHealth();
            return ConnectionTestResult.ok("EVO reachable (status=" + health.status() + ", version=" + health.version() + ")");
        } catch (Exception e) {
            return ConnectionTestResult.error("EVO connection failed: " + e.getMessage());
        }
    }

    @Override
    public void updateConnectionConfiguration(Map<String, String> configuration) {
        if (configuration == null) return;
        EvoConfiguration config = evoIntegrationService.getConfiguration();
        if (configuration.containsKey("endpoint")) {
            config.setEndpoint(configuration.get("endpoint"));
        }
        if (configuration.containsKey("authToken")) {
            config.setAuthToken(configuration.get("authToken"));
        }
        if (configuration.containsKey("enabled")) {
            config.setEnabled(Boolean.parseBoolean(configuration.get("enabled")));
        }
        if (configuration.containsKey("timeoutSeconds")) {
            try {
                config.setTimeoutSeconds(Integer.parseInt(configuration.get("timeoutSeconds")));
            } catch (NumberFormatException ignored) {}
        }
        if (configuration.containsKey("maxRetries")) {
            try {
                config.setMaxRetries(Integer.parseInt(configuration.get("maxRetries")));
            } catch (NumberFormatException ignored) {}
        }
        evoIntegrationService.saveConfiguration(config);
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        EvoConfiguration config = evoIntegrationService.getConfiguration();
        String authToken = config.getAuthToken();
        return Map.of(
                "endpoint", config.getEndpoint() != null ? config.getEndpoint() : "",
                "enabled", Boolean.toString(config.isEnabled()),
                "timeoutSeconds", Integer.toString(config.getTimeoutSeconds()),
                "maxRetries", Integer.toString(config.getMaxRetries()),
                "hasAuthToken", Boolean.toString(authToken != null && !authToken.isBlank())
        );
    }

    @Override
    public HarnessExecutionResult executeTask(
            Task task,
            Workspace workspace,
            String instructions,
            String prompt,
            Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier,
            Duration timeout
    ) {
        ExternalAiDelegator.DelegationResult result = delegator.delegateImplementation(
                task, workspace, instructions, prompt, logConsumer);
        if (!result.handled()) {
            return HarnessExecutionResult.notHandled();
        }
        if (result.success()) {
            return HarnessExecutionResult.ok(result.summary(), List.of());
        }
        return HarnessExecutionResult.error(result.errorMessage());
    }
}
