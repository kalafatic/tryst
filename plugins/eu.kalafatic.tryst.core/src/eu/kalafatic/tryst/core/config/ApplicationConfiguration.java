package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.error.TrystApplicationException;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Baseline authoritative application configuration with validation, safe defaults,
 * and support for AI provider settings, timeouts, limits, and retry policies.
 */
public record ApplicationConfiguration(
        String applicationName,
        String version,
        Path userHomeDirectory,
        Path configurationDirectory,
        Path defaultWorkspaceDirectory,
        String ollamaEndpoint,
        String defaultModel,
        int requestTimeoutSeconds,
        int maxIterations,
        int maxContextTokens,
        int maxOutputTokens,
        int maxRetryAttempts,
        boolean secureSandboxEnabled,
        String serverHost,
        int httpPort,
        int tcpPort
) {
    public static final String DEFAULT_APP_NAME = "TRYST – AI Development Environment";
    public static final String DEFAULT_VERSION = "0.1.0";
    public static final String DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "codellama";
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_ITERATIONS = 5;
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 4096;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;
    public static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    public static final boolean DEFAULT_SECURE_SANDBOX = true;
    public static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    public static final int DEFAULT_HTTP_PORT = 8080;
    public static final int DEFAULT_TCP_PORT = 9090;

    public ApplicationConfiguration {
        Objects.requireNonNull(applicationName, "applicationName must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(userHomeDirectory, "userHomeDirectory must not be null");
        Objects.requireNonNull(configurationDirectory, "configurationDirectory must not be null");
        Objects.requireNonNull(defaultWorkspaceDirectory, "defaultWorkspaceDirectory must not be null");
        Objects.requireNonNull(ollamaEndpoint, "ollamaEndpoint must not be null");
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(serverHost, "serverHost must not be null");

        if (applicationName.isBlank()) {
            throw new TrystApplicationException("applicationName must not be blank");
        }
        if (ollamaEndpoint.isBlank() || (!ollamaEndpoint.startsWith("http://") && !ollamaEndpoint.startsWith("https://"))) {
            throw new TrystApplicationException("ollamaEndpoint must be a valid HTTP/HTTPS URL");
        }
        if (defaultModel.isBlank()) {
            throw new TrystApplicationException("defaultModel must not be blank");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new TrystApplicationException("requestTimeoutSeconds must be greater than zero");
        }
        if (maxIterations <= 0) {
            throw new TrystApplicationException("maxIterations must be greater than zero");
        }
        if (maxContextTokens <= 0) {
            throw new TrystApplicationException("maxContextTokens must be greater than zero");
        }
        if (maxOutputTokens <= 0) {
            throw new TrystApplicationException("maxOutputTokens must be greater than zero");
        }
        if (maxRetryAttempts < 0) {
            throw new TrystApplicationException("maxRetryAttempts must not be negative");
        }
        if (serverHost.isBlank()) {
            throw new TrystApplicationException("serverHost must not be blank");
        }
        if (httpPort < 0 || httpPort > 65535) {
            throw new TrystApplicationException("httpPort must be between 0 and 65535");
        }
        if (tcpPort < 0 || tcpPort > 65535) {
            throw new TrystApplicationException("tcpPort must be between 0 and 65535");
        }
    }

    public ApplicationConfiguration(
            String applicationName,
            String version,
            Path userHomeDirectory,
            Path configurationDirectory,
            Path defaultWorkspaceDirectory
    ) {
        this(
                applicationName,
                version,
                userHomeDirectory,
                configurationDirectory,
                defaultWorkspaceDirectory,
                DEFAULT_OLLAMA_ENDPOINT,
                DEFAULT_MODEL,
                DEFAULT_REQUEST_TIMEOUT_SECONDS,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MAX_CONTEXT_TOKENS,
                DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_RETRY_ATTEMPTS,
                DEFAULT_SECURE_SANDBOX,
                DEFAULT_SERVER_HOST,
                DEFAULT_HTTP_PORT,
                DEFAULT_TCP_PORT
        );
    }

    public static ApplicationConfiguration createDefault() {
        String homeProp = System.getProperty("user.home", ".");
        Path home = Path.of(homeProp).toAbsolutePath().normalize();
        Path configDir = home.resolve(".tryst").normalize();
        Path defaultWsDir = configDir.resolve("workspace").normalize();

        return new ApplicationConfiguration(
                DEFAULT_APP_NAME,
                DEFAULT_VERSION,
                home,
                configDir,
                defaultWsDir,
                DEFAULT_OLLAMA_ENDPOINT,
                DEFAULT_MODEL,
                DEFAULT_REQUEST_TIMEOUT_SECONDS,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MAX_CONTEXT_TOKENS,
                DEFAULT_MAX_OUTPUT_TOKENS,
                DEFAULT_MAX_RETRY_ATTEMPTS,
                DEFAULT_SECURE_SANDBOX,
                DEFAULT_SERVER_HOST,
                DEFAULT_HTTP_PORT,
                DEFAULT_TCP_PORT
        );
    }

    public ApplicationConfiguration withOllamaEndpoint(String newEndpoint) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, newEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort
        );
    }

    public ApplicationConfiguration withDefaultModel(String newModel) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, newModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort
        );
    }

    public ApplicationConfiguration withLimits(int timeoutSecs, int iterations, int retryAttempts) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, timeoutSecs,
                iterations, maxContextTokens, maxOutputTokens, retryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort
        );
    }

    public ApplicationConfiguration withServerSettings(String host, int httpPort, int tcpPort) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                host, httpPort, tcpPort
        );
    }
}
