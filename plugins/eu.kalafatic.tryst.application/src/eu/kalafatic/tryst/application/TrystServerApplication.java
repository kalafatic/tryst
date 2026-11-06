package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless Equinox execution entry point for TRYST server operations.
 * Starts the application lifecycle coordinator and embedded communication services (TCP, HTTP)
 * without requiring SWT or a graphical display, preserving the existing RCP/OSGi architecture.
 */
public class TrystServerApplication implements IApplication {

    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ApplicationLifecycleCoordinator coordinator;

    @Override
    public Object start(IApplicationContext context) throws Exception {
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
