package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless Equinox execution entry point for TRYST server operations.
 * Starts the application lifecycle coordinator and embedded communication services (TCP, HTTP)
 * without requiring SWT or a graphical display, preserving the existing RCP/OSGi architecture.
 *
 * <p>Subcommands (first non-flag application argument):
 * <ul>
 *   <li>{@code help} — prints the available commands and configuration properties, exit 0.</li>
 *   <li>{@code status} — starts the server, prints the live host/port status, exit 0.</li>
 *   <li>unknown command — prints an error with a help hint, exit 1.</li>
 *   <li>(no argument) — starts the server and keeps serving until shutdown (unchanged).</li>
 * </ul>
 */
public class TrystServerApplication implements IApplication {

    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ApplicationLifecycleCoordinator coordinator;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        String subcommand = resolveSubcommand(context);
        if (subcommand != null) {
            switch (subcommand) {
                case "help" -> {
                    printHelp();
                    return IApplication.EXIT_OK;
                }
                case "status" -> {
                    return runStatusCommand();
                }
                default -> {
                    System.out.println("Unknown command: " + subcommand);
                    System.out.println("Run with 'help' to list the available TRYST server commands.");
                    return Integer.valueOf(1);
                }
            }
        }

        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        ApplicationLifecycleCoordinator coord = (plugin != null) ? plugin.getLifecycleCoordinator() : new ApplicationLifecycleCoordinator();
        return runHeadless(context, coord);
    }

    public Object runHeadless(IApplicationContext context, ApplicationLifecycleCoordinator coord) throws Exception {
        this.coordinator = coord != null ? coord : new ApplicationLifecycleCoordinator();

        try {
            running.set(true);
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

            logDiagnostics();

            // Register JVM shutdown hook for graceful termination
            Thread hook = new Thread(this::stop, "tryst-headless-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(hook);

            stopLatch.await();
            return IApplication.EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class, "Fatal error running TRYST Headless Server: " + t.getMessage(), t);
            throw t;
        } finally {
            cleanup();
        }
    }

    /**
     * Executes the {@code status} subcommand: brings the server up through the regular
     * lifecycle stages, prints the live host/port from the authoritative runtime endpoint
     * registry (falling back to the bound HTTP listener and the port registry) and exits.
     */
    private Object runStatusCommand() {
        try {
            TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
            ApplicationLifecycleCoordinator coord = (plugin != null) ? plugin.getLifecycleCoordinator() : null;
            if (coord == null) {
                coord = new ApplicationLifecycleCoordinator();
            }
            this.coordinator = coord;

            running.set(true);
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

            String host = null;
            int port = -1;
            var cfgService = coordinator.getConfigurationService();
            if (cfgService != null && cfgService.getEndpointRegistry() != null
                    && cfgService.getEndpointRegistry().getCurrentEndpoint() != null) {
                var ep = cfgService.getEndpointRegistry().getCurrentEndpoint();
                host = ep.bindAddress();
                port = ep.httpPort();
            }
            if (port <= 0) {
                var http = coordinator.getHttpService();
                if (http != null && http.isRunning()) {
                    port = http.getPort();
                }
            }
            if (port <= 0 && cfgService != null && cfgService.getPortRegistry() != null) {
                port = cfgService.getPortRegistry()
                        .getEffectivePort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE);
            }
            var cfg = coordinator.getConfiguration();
            if (host == null || host.isBlank()) {
                host = (cfg != null) ? cfg.serverHost() : "127.0.0.1";
            }
            if (port <= 0) {
                port = (cfg != null) ? cfg.httpPort() : -1;
            }

            boolean setupRequired = true;
            var auth = resolveService(eu.kalafatic.tryst.domain.security.AuthenticationService.class);
            if (auth != null) {
                setupRequired = auth.isSetupRequired();
            }

            System.out.println("TRYST server: UP on " + host + ":" + port + " (setup required: " + setupRequired + ")");
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            TrystLog.error(TrystServerApplication.class, "Status command failed: " + t.getMessage(), t);
            System.out.println("TRYST server: DOWN (" + t.getMessage() + ")");
            return Integer.valueOf(1);
        } finally {
            cleanup();
        }
    }

    private void printHelp() {
        System.out.println("""
                TRYST headless server

                Usage:
                  tryst -application eu.kalafatic.tryst.application.server [command]

                Commands:
                  (no arguments)  Start the TRYST server and keep serving until shutdown.
                  status          Start the server, print the live host/port status and exit.
                  help            Print this help text and exit.

                Configuration properties (tryst.properties, Java system properties or environment):

                  Server & network:
                    server.host                     Bind/display host (default 127.0.0.1)
                    server.http.port                HTTP listener port (default 38080; 8080 is forbidden)
                    server.tcp.port                 TCP service port (default 38280; 8080 is forbidden)
                    server.dynamicPortFallback      true|false (default true)
                    server.portRangeStart           Dynamic fallback range start (default 38080)
                    server.portRangeEnd             Dynamic fallback range end (default 38580)
                    server.networkMode              LOOPBACK | LAN | ALL_INTERFACES
                    server.bindAddress              Explicit bind address override
                    server.publicUrl                Advertised public URL
                    server.reverseProxyEnabled      true|false (default false)
                    server.contextPath              Web context path (default /tryst)
                    server.canonicalUrl             Canonical access URL
                    server.externalBaseUrl          External base URL override

                  AI / execution:
                    ollama.endpoint                 Ollama endpoint URL (default http://localhost:11434)
                    ollama.model                    Default model (default codellama)
                    execution.timeoutSeconds        Request timeout seconds (default 60)
                    execution.maxIterations         Maximum iterations (default 5)
                    execution.maxRetryAttempts      Maximum retry attempts (default 3)
                    ai.maxContextTokens             Maximum context tokens (default 4096)
                    ai.maxOutputTokens              Maximum output tokens (default 2048)
                    security.sandboxEnabled         true|false (default true)

                  Runtime flags (system properties / environment):
                    tryst.headless                  true|false — force headless mode
                    tryst.dev.mode                  true|false — development mode (default true)
                    TRYST_HTTP_PORT / TRYST_PORT    HTTP port override
                    TRYST_TCP_PORT                  TCP port override
                    TRYST_NETWORK_MODE              Network mode override
                    TRYST_BIND_ADDRESS              Bind address override
                    TRYST_EXTERNAL_BASE_URL         External base URL override
                    TRYST_OLLAMA_ENDPOINT           Ollama endpoint override
                    OLLAMA_HOST                     Ollama host override
                """);
    }

    /**
     * Resolves the first non-flag application argument as the subcommand.
     * Recognizes {@code -h}, {@code --help} and {@code -help} as the help command.
     */
    private static String resolveSubcommand(IApplicationContext context) {
        if (context == null || context.getArguments() == null) {
            return null;
        }
        Object argsObj = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (!(argsObj instanceof String[] args)) {
            return null;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String trimmed = arg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("-h".equals(trimmed) || "--help".equals(trimmed) || "-help".equals(trimmed)) {
                return "help";
            }
            if (trimmed.startsWith("-")) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    private <T> T resolveService(Class<T> serviceClass) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(getClass());
            if (bundle != null) {
                BundleContext context = bundle.getBundleContext();
                if (context != null) {
                    ServiceReference<T> ref = context.getServiceReference(serviceClass);
                    if (ref != null) {
                        return context.getService(ref);
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(TrystServerApplication.class, "Failed to resolve " + serviceClass.getSimpleName() + " from OSGi: " + t.getMessage());
        }
        return null;
    }

    private void logDiagnostics() {
        try {
            var cfg = coordinator.getConfiguration();
            var readiness = coordinator.getServerReadinessService();
            var http = coordinator.getHttpService();
            var tcp = coordinator.getTcpService();

            TrystLog.info(TrystServerApplication.class, "=======================================================");
            TrystLog.info(TrystServerApplication.class, " TRYST Autonomous Development Server (Headless Mode)   ");
            TrystLog.info(TrystServerApplication.class, "=======================================================");
            TrystLog.info(TrystServerApplication.class, "Java Version     : " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            TrystLog.info(TrystServerApplication.class, "Config Directory : " + (cfg != null ? cfg.configurationDirectory() : "unknown"));
            TrystLog.info(TrystServerApplication.class, "Workspace Root   : " + (cfg != null ? cfg.defaultWorkspaceDirectory() : "unknown"));
            TrystLog.info(TrystServerApplication.class, "Database Path    : " + (coordinator.getDatabaseManager() != null ? coordinator.getDatabaseManager().getDatabasePath() : "unknown"));
            if (http != null) {
                TrystLog.info(TrystServerApplication.class, "HTTP Bind Port   : " + http.getPort() + " (configured: " + (cfg != null ? cfg.httpPort() : "unknown") + ")");
            }
            if (tcp != null) {
                TrystLog.info(TrystServerApplication.class, "TCP Service      : 127.0.0.1:" + (cfg != null ? cfg.tcpPort() : "unknown"));
            }
            String canonicalUrl = (cfg != null && http != null) ? cfg.resolveEffectiveExternalUrl(http.getPort()) : "http://localhost/tryst";
            TrystLog.info(TrystServerApplication.class, "Canonical URL    : " + canonicalUrl);

            if (readiness != null) {
                var report = readiness.evaluateReadiness(coordinator.getCurrentStage());
                TrystLog.info(TrystServerApplication.class, "Server Readiness : " + (report.ready() ? "READY" : (report.degraded() ? "DEGRADED" : "NOT READY")));
                report.components().forEach((id, health) -> {
                    TrystLog.info(TrystServerApplication.class, "  - Component [" + id + "]: " + health.state() + " (mandatory=" + health.mandatory() + ")"
                            + (health.lastError() != null ? " error=" + health.lastError() : ""));
                });
            }
            TrystLog.info(TrystServerApplication.class, "TRYST Headless Server running. Awaiting shutdown signal...");
            TrystLog.info(TrystServerApplication.class, "=======================================================");
        } catch (Exception e) {
            TrystLog.warn(TrystServerApplication.class, "Diagnostics logging encountered non-fatal error: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            stopLatch.countDown();
        }
    }

    private void cleanup() {
        if (coordinator != null) {
            try {
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.APPLICATION_READY) {
                    coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.WORKBENCH_RUNNING) {
                    coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.APPLICATION_SHUTDOWN) {
                    coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
                }
                if (coordinator.getCurrentStage() == ApplicationLifecycleStage.SERVICE_DISPOSAL) {
                    coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
                }
            } catch (Exception e) {
                TrystLog.warn(TrystServerApplication.class, "Error during server application disposal: " + e.getMessage());
            }
        }
    }
}
