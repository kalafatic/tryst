package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.TrystApplication;
import eu.kalafatic.tryst.application.TrystServerApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates startup mode selection (standard RCP vs headless), headless lifecycle,
 * ServerReadinessService registration, and graceful shutdown.
 */
class StartupModeAndReadinessTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private String originalHeadlessProp;
    private String originalAdminPass;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        originalHeadlessProp = System.getProperty("tryst.headless");
        originalAdminPass = System.getProperty("tryst.admin.password");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        System.setProperty("tryst.admin.password", "admin123");
        System.clearProperty("tryst.headless");
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
        if (originalHeadlessProp != null) {
            System.setProperty("tryst.headless", originalHeadlessProp);
        } else {
            System.clearProperty("tryst.headless");
        }
        if (originalAdminPass != null) {
            System.setProperty("tryst.admin.password", originalAdminPass);
        } else {
            System.clearProperty("tryst.admin.password");
        }
    }

    public static class DummyApplicationContext implements IApplicationContext {
        private final Map<String, Object> arguments;

        public DummyApplicationContext(String[] appArgs) {
            this.arguments = Map.of(IApplicationContext.APPLICATION_ARGS, appArgs != null ? appArgs : new String[0]);
        }

        @Override
        public Map<String, Object> getArguments() {
            return arguments;
        }

        @Override
        public void applicationRunning() {}

        @Override
        public String getBrandingApplication() { return null; }

        @Override
        public String getBrandingName() { return null; }

        @Override
        public String getBrandingDescription() { return null; }

        @Override
        public String getBrandingId() { return null; }

        @Override
        public String getBrandingProperty(String key) { return null; }

        @Override
        public Bundle getBrandingBundle() { return null; }

        @Override
        public void setResult(Object result, IApplication application) {}
    }

    @Test
    void testDefaultModeIsRcp() {
        DummyApplicationContext context = new DummyApplicationContext(new String[]{"-consoleLog"});
        assertFalse(TrystApplication.isHeadlessRequested(context), "Default mode without -headless must be Standard RCP");
    }

    @Test
    void testHeadlessModeParsing() {
        DummyApplicationContext ctx1 = new DummyApplicationContext(new String[]{"-headless"});
        assertTrue(TrystApplication.isHeadlessRequested(ctx1), "Should detect -headless argument");

        DummyApplicationContext ctx2 = new DummyApplicationContext(new String[]{"--headless", "-consoleLog"});
        assertTrue(TrystApplication.isHeadlessRequested(ctx2), "Should detect --headless argument");

        System.setProperty("tryst.headless", "true");
        DummyApplicationContext ctx3 = new DummyApplicationContext(new String[]{});
        assertTrue(TrystApplication.isHeadlessRequested(ctx3), "Should detect tryst.headless=true system property");
    }

    @Test
    void testInvalidArgumentsHandling() {
        DummyApplicationContext context = new DummyApplicationContext(new String[]{"-foo", "bar"});
        assertFalse(TrystApplication.isHeadlessRequested(context), "Unrelated arguments should not trigger headless mode");
    }

    @Test
    void testServerReadinessRegistrationInLifecycle() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);

        ServerReadinessService readiness = coordinator.getServerReadinessService();
        assertNotNull(readiness, "ServerReadinessService should be instantiated at PLATFORM_INITIALIZATION");

        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        ServerReadinessService.ReadinessReport report = readiness.evaluateReadiness(coordinator.getCurrentStage());
        assertTrue(report.ready(), "All mandatory components (Database, Authentication, HttpServer) should be healthy");
        assertNotNull(readiness.getComponent("Database"));
        assertNotNull(readiness.getComponent("Authentication"));
        assertNotNull(readiness.getComponent("HttpServer"));

        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);

        assertEquals(ApplicationLifecycleStage.APPLICATION_TERMINATED, coordinator.getCurrentStage());
    }

    @Test
    void testHeadlessGracefulShutdown() throws Exception {
        TrystServerApplication serverApp = new TrystServerApplication();
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();
        DummyApplicationContext context = new DummyApplicationContext(new String[]{"-headless"});

        var executor = Executors.newSingleThreadExecutor();
        Future<Object> future = executor.submit(() -> {
            try {
                return serverApp.runHeadless(context, coordinator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait until coordinator reaches APPLICATION_READY
        long deadline = System.currentTimeMillis() + 5000;
        while (coordinator.getCurrentStage() != ApplicationLifecycleStage.APPLICATION_READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(ApplicationLifecycleStage.APPLICATION_READY, coordinator.getCurrentStage());

        // Stop the server
        serverApp.stop();

        Object result = future.get(5, TimeUnit.SECONDS);
        assertEquals(IApplication.EXIT_OK, result);
        assertEquals(ApplicationLifecycleStage.APPLICATION_TERMINATED, coordinator.getCurrentStage());
        executor.shutdown();
    }
}
