package eu.kalafatic.tryst.infrastructure.dcode;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.model.DCodeConfiguration;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.DCodeConfigurationRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * DCode coding-harness adapter for TRYST (Phase 6, WP-6.6). Delegates the IMPLEMENTING
 * stage to the native DCode harness through its stable /api/v1 surface (FHC 1.0 wire
 * mapping). TRYST remains sovereign over task lifecycle, sandbox isolation, repository
 * operations and approval: DCode executes inside the TRYST workspace, never commits and
 * never pushes. Sessions with changes end in WAITING_FOR_APPROVAL; the resulting diff is
 * collected and returned for the normal TRYST review/approval flow.
 */
public class DCodeCodingHarnessAdapter implements CodingHarnessAdapter {

    public static final String APPLICATION_ID = "dcode";
    public static final String APPLICATION_NAME = "DCode - Darwin Code";
    public static final String APPLICATION_TYPE = "DCODE";

    public static final String DEFAULT_ENDPOINT = "http://localhost:48080";
    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_AUTH_TOKEN = "authToken";
    public static final String CONFIG_PROVIDER_ID = "providerId";
    public static final String CONFIG_MODEL = "model";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String CONFIG_ENABLED = "enabled";

    private final DCodeConfigurationRepository repository;
    private volatile String endpoint = DEFAULT_ENDPOINT;
    private volatile String authToken = "";
    private volatile String providerId = "";
    private volatile String model = "";
    private volatile long timeoutSeconds = 1800L;
    private volatile boolean enabled = true;
    private volatile boolean hydrated = false;

    public DCodeCodingHarnessAdapter() {
        this(null);
    }

    public DCodeCodingHarnessAdapter(DCodeConfigurationRepository repository) {
        this.repository = repository;
        hydrateFromPersistence();
    }

    private synchronized void hydrateFromPersistence() {
        if (hydrated || repository == null) {
            return;
        }
        try {
            repository.findById("default").ifPresent(config -> {
                endpoint = config.getEndpoint();
                providerId = config.getProviderId();
                model = config.getModel();
                enabled = config.isEnabled();
                timeoutSeconds = config.getTimeoutSeconds();
            });
        } catch (Exception e) {
            TrystLog.warn(DCodeCodingHarnessAdapter.class,
                    "DCode configuration hydration failed: " + e.getMessage());
        }
        hydrated = true;
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
        if (!enabled) {
            return false;
        }
        return new DCodeHttpClient(endpoint, authToken, Duration.ofSeconds(10)).health();
    }

    @Override
    public ConnectionTestResult testConnection() {
        if (!enabled) {
            return ConnectionTestResult.error("DCode harness is disabled");
        }
        try {
            if (new DCodeHttpClient(endpoint, authToken, Duration.ofSeconds(10)).health()) {
                return ConnectionTestResult.ok("DCode harness available at " + endpoint);
            }
            return ConnectionTestResult.error("DCode at " + endpoint + " is not healthy");
        } catch (Exception e) {
            return ConnectionTestResult.error("DCode harness unreachable at " + endpoint
                    + ": " + e.getMessage());
        }
    }

    @Override
    public void updateConnectionConfiguration(Map<String, String> configuration) {
        if (configuration == null) {
            return;
        }
        if (configuration.containsKey(CONFIG_ENDPOINT)
                && !configuration.get(CONFIG_ENDPOINT).isBlank()) {
            endpoint = configuration.get(CONFIG_ENDPOINT).trim();
        }
        if (configuration.containsKey(CONFIG_AUTH_TOKEN)) {
            authToken = configuration.get(CONFIG_AUTH_TOKEN) == null ? ""
                    : configuration.get(CONFIG_AUTH_TOKEN).trim();
        }
        if (configuration.containsKey(CONFIG_PROVIDER_ID)) {
            providerId = configuration.get(CONFIG_PROVIDER_ID) == null ? ""
                    : configuration.get(CONFIG_PROVIDER_ID).trim();
        }
        if (configuration.containsKey(CONFIG_MODEL)) {
            model = configuration.get(CONFIG_MODEL) == null ? ""
                    : configuration.get(CONFIG_MODEL).trim();
        }
        if (configuration.containsKey(CONFIG_TIMEOUT_SECONDS)) {
            try {
                timeoutSeconds = Long.parseLong(configuration.get(CONFIG_TIMEOUT_SECONDS));
            } catch (NumberFormatException ignored) {
                timeoutSeconds = 1800L;
            }
        }
        if (configuration.containsKey(CONFIG_ENABLED)) {
            enabled = Boolean.parseBoolean(configuration.get(CONFIG_ENABLED));
        }
        persist();
    }

    private synchronized void persist() {
        if (repository == null) {
            return;
        }
        try {
            DCodeConfiguration persisted = DCodeConfiguration.createDefault();
            persisted.setEndpoint(endpoint);
            persisted.setProviderId(providerId);
            persisted.setModel(model);
            persisted.setEnabled(enabled);
            persisted.setTimeoutSeconds((int) timeoutSeconds);
            repository.save(persisted);
        } catch (Exception e) {
            TrystLog.warn(DCodeCodingHarnessAdapter.class,
                    "DCode configuration persistence failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(CONFIG_ENDPOINT, endpoint);
        config.put(CONFIG_AUTH_TOKEN, authToken.isBlank() ? ""
                : "****** (configured)");
        config.put(CONFIG_PROVIDER_ID, providerId);
        config.put(CONFIG_MODEL, model);
        config.put(CONFIG_TIMEOUT_SECONDS, String.valueOf(timeoutSeconds));
        config.put(CONFIG_ENABLED, String.valueOf(enabled));
        return config;
    }

    @Override
    public HarnessExecutionResult executeTask(Task task, Workspace workspace,
            String instructions, String prompt, Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier, Duration timeout) {
        if (workspace == null || workspace.getRootPath() == null
                || !java.nio.file.Files.isDirectory(workspace.getRootPath())) {
            return HarnessExecutionResult.error(
                    "DCode workspace does not exist: " + (workspace == null
                            ? "null" : workspace.getRootPath()));
        }
        if (!enabled) {
            return HarnessExecutionResult.error("DCode harness is disabled");
        }
        java.nio.file.Path workspaceDir = workspace.getRootPath();
        Duration effectiveTimeout = timeout != null && !timeout.isZero()
                && !timeout.isNegative()
                ? Duration.ofSeconds(Math.min(timeout.toSeconds(), timeoutSeconds))
                : Duration.ofSeconds(timeoutSeconds);
        DCodeHttpClient client = new DCodeHttpClient(endpoint, authToken,
                effectiveTimeout);
        String sessionId = null;
        try {
            String workspaceId = client.createWorkspace(
                    "tryst-" + Integer.toHexString(workspaceDir.toAbsolutePath()
                            .hashCode()), workspaceDir.toAbsolutePath().toString());
            client.addRepository(workspaceId, workspaceDir.getFileName().toString(),
                    workspaceDir.toAbsolutePath().toString());
            sessionId = client.createSession("TRYST task delegation", workspaceId,
                    providerId, model);
            String effectivePrompt = prompt != null && !prompt.isBlank()
                    ? prompt : instructions;
            client.startSession(sessionId, effectivePrompt);
            logConsumer.accept("[DCode] Execution submitted: session " + sessionId
                    + " (harness " + endpoint + ")");

            long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();
            int loggedMessages = 0;
            while (true) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    client.cancelSession(sessionId);
                    return HarnessExecutionResult.cancelled("DCode execution cancelled by TRYST");
                }
                if (System.currentTimeMillis() > deadline) {
                    client.cancelSession(sessionId);
                    return HarnessExecutionResult.timedOut("DCode execution timed out after "
                            + effectiveTimeout.toSeconds() + " s");
                }
                List<String> messages = client.messages(sessionId);
                for (int i = loggedMessages; i < messages.size(); i++) {
                    logConsumer.accept("[DCode] " + messages.get(i));
                }
                loggedMessages = messages.size();

                String state = client.sessionState(sessionId);
                switch (state) {
                    case "COMPLETED", "WAITING_FOR_APPROVAL" -> {
                        List<String> modifiedFiles = client.changedFiles(sessionId);
                        String summary = lastAssistantContent(messages);
                        if ("WAITING_FOR_APPROVAL".equals(state)) {
                            summary = summary.isBlank()
                                    ? "Execution completed with changes pending review"
                                    : summary + " (changes pending review)";
                        }
                        logConsumer.accept("[DCode] Execution completed: " + summary
                                + " (modified files: " + modifiedFiles.size() + ")");
                        return HarnessExecutionResult.ok(
                                "DCode execution completed in " + workspaceDir.getFileName()
                                        + ": " + summary, modifiedFiles);
                    }
                    case "FAILED" -> {
                        String summary = lastAssistantContent(messages);
                        return HarnessExecutionResult.error(
                                "DCode execution failed: " + summary);
                    }
                    case "CANCELLED" -> {
                        return HarnessExecutionResult.cancelled(
                                "DCode execution was cancelled");
                    }
                    default -> Thread.sleep(2000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HarnessExecutionResult.cancelled("DCode execution interrupted");
        } catch (Exception e) {
            return HarnessExecutionResult.error("DCode execution failed: "
                    + e.getMessage());
        }
    }

    private static String lastAssistantContent(List<String> messages) {
        String content = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            content = messages.get(i);
            if (!content.isBlank()) {
                return content;
            }
        }
        return content;
    }
}
