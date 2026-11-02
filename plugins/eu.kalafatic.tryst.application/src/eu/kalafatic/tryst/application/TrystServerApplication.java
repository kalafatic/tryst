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
        this.coordinator = (plugin != null) ? plugin.getLifecycleCoordinator() : new ApplicationLifecycleCoordinator();

        try {
            running.set(true);
            coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

            TrystLog.info(TrystServerApplication.class, "TRYST Headless Server running. Awaiting shutdown signal...");

            // Register JVM shutdown hook for graceful termination
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "tryst-server-shutdown-hook"));

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
