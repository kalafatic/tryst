package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Centralized configuration service providing configuration loading, persistence,
 * validation, and dynamic change notification.
 */
public class ConfigurationService {

    private static final String PREF_FILE_NAME = "tryst.properties";

    private final Path configFilePath;
    private volatile ApplicationConfiguration currentConfiguration;
    private final CopyOnWriteArrayList<Consumer<ApplicationConfiguration>> listeners = new CopyOnWriteArrayList<>();
    private final RuntimeEndpointRegistry endpointRegistry;

    public ConfigurationService(Path configurationDirectory) {
        Objects.requireNonNull(configurationDirectory, "configurationDirectory must not be null");
        this.configFilePath = configurationDirectory.resolve(PREF_FILE_NAME);
        this.currentConfiguration = loadConfiguration(configurationDirectory);
        this.endpointRegistry = new RuntimeEndpointRegistry(
                RuntimeEndpoint.uninitialized(
                        currentConfiguration.applicationName(),
                        currentConfiguration.serverHost(),
                        currentConfiguration.httpPort()
                )
        );
    }

    public ConfigurationService(ApplicationConfiguration initialConfig) {
        Objects.requireNonNull(initialConfig, "initialConfig must not be null");
        this.configFilePath = initialConfig.configurationDirectory().resolve(PREF_FILE_NAME);
        this.currentConfiguration = initialConfig;
        this.endpointRegistry = new RuntimeEndpointRegistry(
                RuntimeEndpoint.uninitialized(
                        initialConfig.applicationName(),
                        initialConfig.serverHost(),
                        initialConfig.httpPort()
                )
        );
    }

    public ApplicationConfiguration getConfiguration() {
        return currentConfiguration;
    }

    public RuntimeEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public synchronized void updateConfiguration(ApplicationConfiguration newConfiguration) {
        Objects.requireNonNull(newConfiguration, "newConfiguration must not be null");
        this.currentConfiguration = newConfiguration;
        saveConfiguration(newConfiguration);
        notifyListeners(newConfiguration);
    }

    public void addConfigurationListener(Consumer<ApplicationConfiguration> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeConfigurationListener(Consumer<ApplicationConfiguration> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(ApplicationConfiguration config) {
        for (Consumer<ApplicationConfiguration> listener : listeners) {
            try {
                listener.accept(config);
            } catch (Throwable t) {
                TrystLog.error(ConfigurationService.class, "Configuration listener threw exception", t);
            }
        }
    }

    private ApplicationConfiguration loadConfiguration(Path configDir) {
        ApplicationConfiguration base = ApplicationConfiguration.createDefault();
        if (!Files.exists(configFilePath)) {
            return base;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFilePath.toFile())) {
            props.load(fis);
            String endpoint = props.getProperty("ollama.endpoint", base.ollamaEndpoint());
            String model = props.getProperty("ollama.model", base.defaultModel());
            int timeout = Integer.parseInt(props.getProperty("execution.timeoutSeconds", String.valueOf(base.requestTimeoutSeconds())));
            int maxIter = Integer.parseInt(props.getProperty("execution.maxIterations", String.valueOf(base.maxIterations())));
            int maxTokens = Integer.parseInt(props.getProperty("ai.maxContextTokens", String.valueOf(base.maxContextTokens())));
            int maxOut = Integer.parseInt(props.getProperty("ai.maxOutputTokens", String.valueOf(base.maxOutputTokens())));
            int retry = Integer.parseInt(props.getProperty("execution.maxRetryAttempts", String.valueOf(base.maxRetryAttempts())));
            boolean sandbox = Boolean.parseBoolean(props.getProperty("security.sandboxEnabled", String.valueOf(base.secureSandboxEnabled())));
            String host = props.getProperty("server.host", base.serverHost());
            int httpPort = Integer.parseInt(props.getProperty("server.http.port", String.valueOf(base.httpPort())));
            int tcpPort = Integer.parseInt(props.getProperty("server.tcp.port", String.valueOf(base.tcpPort())));
            boolean dynamicFallback = Boolean.parseBoolean(props.getProperty("server.dynamicPortFallback", String.valueOf(base.dynamicPortFallbackEnabled())));
            int rangeStart = Integer.parseInt(props.getProperty("server.portRangeStart", String.valueOf(base.portRangeStart())));
            int rangeEnd = Integer.parseInt(props.getProperty("server.portRangeEnd", String.valueOf(base.portRangeEnd())));
            String publicUrl = props.getProperty("server.publicUrl", base.publicServerUrl());
            boolean reverseProxy = Boolean.parseBoolean(props.getProperty("server.reverseProxyEnabled", String.valueOf(base.reverseProxyEnabled())));

            return new ApplicationConfiguration(
                    base.applicationName(),
                    base.version(),
                    base.userHomeDirectory(),
                    configDir,
                    base.defaultWorkspaceDirectory(),
                    endpoint,
                    model,
                    timeout,
                    maxIter,
                    maxTokens,
                    maxOut,
                    retry,
                    sandbox,
                    host,
                    httpPort,
                    tcpPort,
                    dynamicFallback,
                    rangeStart,
                    rangeEnd,
                    publicUrl,
                    reverseProxy
            );
        } catch (Exception e) {
            TrystLog.warn(ConfigurationService.class, "Failed to load preferences from " + configFilePath + "; using defaults: " + e.getMessage());
            return base;
        }
    }

    private void saveConfiguration(ApplicationConfiguration config) {
        try {
            Files.createDirectories(config.configurationDirectory());
            Properties props = new Properties();
            props.setProperty("ollama.endpoint", config.ollamaEndpoint());
            props.setProperty("ollama.model", config.defaultModel());
            props.setProperty("execution.timeoutSeconds", String.valueOf(config.requestTimeoutSeconds()));
            props.setProperty("execution.maxIterations", String.valueOf(config.maxIterations()));
            props.setProperty("ai.maxContextTokens", String.valueOf(config.maxContextTokens()));
            props.setProperty("ai.maxOutputTokens", String.valueOf(config.maxOutputTokens()));
            props.setProperty("execution.maxRetryAttempts", String.valueOf(config.maxRetryAttempts()));
            props.setProperty("security.sandboxEnabled", String.valueOf(config.secureSandboxEnabled()));
            props.setProperty("server.host", config.serverHost());
            props.setProperty("server.http.port", String.valueOf(config.httpPort()));
            props.setProperty("server.tcp.port", String.valueOf(config.tcpPort()));
            props.setProperty("server.dynamicPortFallback", String.valueOf(config.dynamicPortFallbackEnabled()));
            props.setProperty("server.portRangeStart", String.valueOf(config.portRangeStart()));
            props.setProperty("server.portRangeEnd", String.valueOf(config.portRangeEnd()));
            props.setProperty("server.publicUrl", config.publicServerUrl());
            props.setProperty("server.reverseProxyEnabled", String.valueOf(config.reverseProxyEnabled()));

            try (FileOutputStream fos = new FileOutputStream(configFilePath.toFile())) {
                props.store(fos, "TRYST Configuration Properties");
            }
        } catch (IOException e) {
            TrystLog.error(ConfigurationService.class, "Failed to persist configuration to " + configFilePath, e);
        }
    }
}
