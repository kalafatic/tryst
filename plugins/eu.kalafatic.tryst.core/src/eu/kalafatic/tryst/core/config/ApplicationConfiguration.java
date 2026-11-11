package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.error.TrystApplicationException;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Baseline authoritative application configuration with validation, safe defaults,
 * and support for AI provider settings, timeouts, limits, retry policies,
 * and LAN/reverse-proxy network deployment.
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
        int tcpPort,
        boolean dynamicPortFallbackEnabled,
        int portRangeStart,
        int portRangeEnd,
        String publicServerUrl,
        boolean reverseProxyEnabled,
        String contextPath,
        String canonicalUrl,
        NetworkMode networkMode,
        String bindAddress,
        String externalBaseUrl
) {
    public static final boolean DEFAULT_DYNAMIC_PORT_FALLBACK = true;
    public static final int DEFAULT_PORT_RANGE_START = 38080;
    public static final int DEFAULT_PORT_RANGE_END = 38580;
    public static final String DEFAULT_PUBLIC_URL = "";
    public static final boolean DEFAULT_REVERSE_PROXY = false;
    public static final String DEFAULT_CONTEXT_PATH = "/tryst";
    public static final String DEFAULT_CANONICAL_URL = "http://localhost/tryst";
    public static final String PROPERTY_DEV_MODE = "tryst.dev.mode";
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
    public static final int DEFAULT_HTTP_PORT = 38080;
    public static final int DEFAULT_TCP_PORT = 38280;
    public static final NetworkMode DEFAULT_NETWORK_MODE = NetworkMode.LOOPBACK;

    public ApplicationConfiguration {
        Objects.requireNonNull(applicationName, "applicationName must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(userHomeDirectory, "userHomeDirectory must not be null");
        Objects.requireNonNull(configurationDirectory, "configurationDirectory must not be null");
        Objects.requireNonNull(defaultWorkspaceDirectory, "defaultWorkspaceDirectory must not be null");
        Objects.requireNonNull(ollamaEndpoint, "ollamaEndpoint must not be null");
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(serverHost, "serverHost must not be null");
        if (networkMode == null) {
            networkMode = DEFAULT_NETWORK_MODE;
        }

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
        if (httpPort == 8080) {
            throw new TrystApplicationException("Port 8080 is strictly reserved and forbidden for TRYST. TRYST HTTP must never bind to port 8080.");
        }
        if (tcpPort < 0 || tcpPort > 65535) {
            throw new TrystApplicationException("tcpPort must be between 0 and 65535");
        }
        if (tcpPort == 8080) {
            throw new TrystApplicationException("Port 8080 is strictly reserved and forbidden for TRYST. TRYST TCP must never bind to port 8080.");
        }
        if (portRangeStart < 0 || portRangeStart > 65535) {
            throw new TrystApplicationException("portRangeStart must be between 0 and 65535");
        }
        if (portRangeEnd < portRangeStart || portRangeEnd > 65535) {
            throw new TrystApplicationException("portRangeEnd must be >= portRangeStart and <= 65535");
        }
        if (portRangeStart <= 8080 && 8080 <= portRangeEnd && portRangeStart != 0) {
            throw new TrystApplicationException("Port 8080 is strictly reserved and forbidden for TRYST. Fallback range must not include port 8080.");
        }
        if (contextPath == null || contextPath.isBlank()) {
            throw new TrystApplicationException("contextPath must not be blank");
        }
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            throw new TrystApplicationException("canonicalUrl must not be blank");
        }
    }

    /**
     * Backward-compatible 22-parameter constructor delegating to full canonical constructor.
     */
    public ApplicationConfiguration(
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
            int tcpPort,
            boolean dynamicPortFallbackEnabled,
            int portRangeStart,
            int portRangeEnd,
            String publicServerUrl,
            boolean reverseProxyEnabled,
            String contextPath,
            String canonicalUrl
    ) {
        this(
                applicationName,
                version,
                userHomeDirectory,
                configurationDirectory,
                defaultWorkspaceDirectory,
                ollamaEndpoint,
                defaultModel,
                requestTimeoutSeconds,
                maxIterations,
                maxContextTokens,
                maxOutputTokens,
                maxRetryAttempts,
                secureSandboxEnabled,
                serverHost,
                httpPort,
                tcpPort,
                dynamicPortFallbackEnabled,
                portRangeStart,
                portRangeEnd,
                publicServerUrl,
                reverseProxyEnabled,
                contextPath,
                canonicalUrl,
                DEFAULT_NETWORK_MODE,
                null,
                publicServerUrl
        );
    }

    public ApplicationConfiguration(
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
            int tcpPort,
            boolean dynamicPortFallbackEnabled,
            int portRangeStart,
            int portRangeEnd,
            String publicServerUrl,
            boolean reverseProxyEnabled
    ) {
        this(
                applicationName,
                version,
                userHomeDirectory,
                configurationDirectory,
                defaultWorkspaceDirectory,
                ollamaEndpoint,
                defaultModel,
                requestTimeoutSeconds,
                maxIterations,
                maxContextTokens,
                maxOutputTokens,
                maxRetryAttempts,
                secureSandboxEnabled,
                serverHost,
                httpPort,
                tcpPort,
                dynamicPortFallbackEnabled,
                portRangeStart,
                portRangeEnd,
                publicServerUrl,
                reverseProxyEnabled,
                DEFAULT_CONTEXT_PATH,
                DEFAULT_CANONICAL_URL
        );
    }

    public ApplicationConfiguration(
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
        this(
                applicationName,
                version,
                userHomeDirectory,
                configurationDirectory,
                defaultWorkspaceDirectory,
                ollamaEndpoint,
                defaultModel,
                requestTimeoutSeconds,
                maxIterations,
                maxContextTokens,
                maxOutputTokens,
                maxRetryAttempts,
                secureSandboxEnabled,
                serverHost,
                httpPort,
                tcpPort,
                DEFAULT_DYNAMIC_PORT_FALLBACK,
                DEFAULT_PORT_RANGE_START,
                DEFAULT_PORT_RANGE_END,
                DEFAULT_PUBLIC_URL,
                DEFAULT_REVERSE_PROXY
        );
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
                serverHost, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                publicServerUrl, reverseProxyEnabled, contextPath, canonicalUrl,
                networkMode, bindAddress, externalBaseUrl
        );
    }

    public ApplicationConfiguration withDefaultModel(String newModel) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, newModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                publicServerUrl, reverseProxyEnabled, contextPath, canonicalUrl,
                networkMode, bindAddress, externalBaseUrl
        );
    }

    public ApplicationConfiguration withLimits(int timeoutSecs, int iterations, int retryAttempts) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, timeoutSecs,
                iterations, maxContextTokens, maxOutputTokens, retryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                publicServerUrl, reverseProxyEnabled, contextPath, canonicalUrl,
                networkMode, bindAddress, externalBaseUrl
        );
    }

    public ApplicationConfiguration withServerSettings(String host, int httpPort, int tcpPort) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                host, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                publicServerUrl, reverseProxyEnabled, contextPath, canonicalUrl,
                networkMode, bindAddress, externalBaseUrl
        );
    }

    public ApplicationConfiguration withDynamicNetworking(boolean fallback, int startPort, int endPort, String publicUrl, boolean reverseProxy) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort, fallback, startPort, endPort,
                publicUrl != null ? publicUrl : "", reverseProxy, contextPath, canonicalUrl,
                networkMode, bindAddress, publicUrl != null ? publicUrl : externalBaseUrl
        );
    }

    public ApplicationConfiguration withNetworkModeSettings(NetworkMode mode, String explicitBindAddress, String externalBaseUrl) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                externalBaseUrl != null ? externalBaseUrl : publicServerUrl, reverseProxyEnabled, contextPath, canonicalUrl,
                mode != null ? mode : DEFAULT_NETWORK_MODE, explicitBindAddress, externalBaseUrl
        );
    }

    public ApplicationConfiguration withCanonicalUrlSettings(String contextPath, String canonicalUrl) {
        return new ApplicationConfiguration(
                applicationName, version, userHomeDirectory, configurationDirectory,
                defaultWorkspaceDirectory, ollamaEndpoint, defaultModel, requestTimeoutSeconds,
                maxIterations, maxContextTokens, maxOutputTokens, maxRetryAttempts, secureSandboxEnabled,
                serverHost, httpPort, tcpPort, dynamicPortFallbackEnabled, portRangeStart, portRangeEnd,
                publicServerUrl, reverseProxyEnabled,
                contextPath != null ? contextPath : DEFAULT_CONTEXT_PATH,
                canonicalUrl != null ? canonicalUrl : DEFAULT_CANONICAL_URL,
                networkMode, bindAddress, externalBaseUrl
        );
    }

    /**
     * Resolves the actual effective IP address to which the HTTP server must bind.
     * - LOOPBACK: binds to 127.0.0.1
     * - LAN: binds to explicit bindAddress if provided; otherwise resolves primary LAN IP (e.g. 192.168.0.2).
     * - ALL_INTERFACES: binds to 0.0.0.0.
     */
    public String resolveEffectiveBindAddress() {
        if (bindAddress != null && !bindAddress.isBlank()) {
            return bindAddress.trim();
        }
        return switch (networkMode != null ? networkMode : DEFAULT_NETWORK_MODE) {
            case LOOPBACK -> "127.0.0.1";
            case LAN -> NetworkAddressDetector.detectLanIpAddress();
            case ALL_INTERFACES -> "0.0.0.0";
        };
    }

    /**
     * Resolves the effective host or IP name to display or advertise to external clients.
     */
    public String resolveEffectiveHostName() {
        NetworkMode mode = networkMode != null ? networkMode : DEFAULT_NETWORK_MODE;
        if (mode == NetworkMode.LAN) {
            if (bindAddress != null && !bindAddress.isBlank() && !"0.0.0.0".equals(bindAddress.trim())) {
                return bindAddress.trim();
            }
            return NetworkAddressDetector.detectLanIpAddress();
        } else if (mode == NetworkMode.ALL_INTERFACES) {
            return NetworkAddressDetector.detectLanIpAddress();
        }
        return serverHost != null && !serverHost.isBlank() ? serverHost : "127.0.0.1";
    }

    /**
     * Determines whether emergency development mode is active.
     * By default, it is enabled (unless explicitly disabled via -Dtryst.dev.mode=false).
     */
    public static boolean isDevelopmentMode() {
        String prop = System.getProperty(PROPERTY_DEV_MODE);
        if (prop == null) {
            String env = System.getenv("TRYST_DEV_MODE");
            if (env != null) {
                return !"false".equalsIgnoreCase(env.trim());
            }
            return true; // Active by default in emergency development mode
        }
        return !"false".equalsIgnoreCase(prop.trim());
    }

    public String resolveEffectiveExternalUrl(int actualPort) {
        String effectivePub = (externalBaseUrl != null && !externalBaseUrl.isBlank())
                ? externalBaseUrl
                : publicServerUrl;

        if (reverseProxyEnabled) {
            if (effectivePub != null && !effectivePub.isBlank()) {
                return effectivePub.endsWith("/") ? effectivePub.substring(0, effectivePub.length() - 1) : effectivePub;
            }
            if (canonicalUrl != null && !canonicalUrl.isBlank()) {
                return canonicalUrl.endsWith("/") ? canonicalUrl.substring(0, canonicalUrl.length() - 1) : canonicalUrl;
            }
        }

        // If an explicit externalBaseUrl is configured (e.g. for custom domain or reverse proxy), use it
        if (effectivePub != null && !effectivePub.isBlank()) {
            return effectivePub.endsWith("/") ? effectivePub.substring(0, effectivePub.length() - 1) : effectivePub;
        }

        String cleanContext = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        if (cleanContext.endsWith("/")) {
            cleanContext = cleanContext.substring(0, cleanContext.length() - 1);
        }

        String host = resolveEffectiveHostName();
        return "http://" + host + ":" + actualPort + cleanContext;
    }
}
