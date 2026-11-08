package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.server.TrystHttpService;
import eu.kalafatic.tryst.core.server.TrystServerLifecycleService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests verifying authoritative HTTP server lifecycle,
 * OSGi Service Registry publication of TrystServerLifecycleService, UI-layer
 * consumption via TrystUiServices without classloader reflection, socket release on shutdown,
 * concurrent Start serialization, and /api/status HTTP 200 response verification.
 */
class ServerLifecycleAndUiAccessTest {

    @TempDir
    Path tempDir;

    private ApplicationLifecycleCoordinator coordinator;
    private String originalUserHome;
    private String originalAdminPass;

    @BeforeEach
    void setUp() {
        cleanEnvironment();
        originalUserHome = System.getProperty("user.home");
        originalAdminPass = System.getProperty("tryst.admin.password");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        System.setProperty("tryst.admin.password", "admin123");
    }

    @AfterEach
    void tearDown() {
        cleanEnvironment();

        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
        if (originalAdminPass != null) {
            System.setProperty("tryst.admin.password", originalAdminPass);
        } else {
            System.clearProperty("tryst.admin.password");
        }
    }

    private void cleanEnvironment() {
        if (coordinator != null) {
            try {
                coordinator.stopHttpServer();
                while (coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.APPLICATION_TERMINATED.ordinal()) {
                    ApplicationLifecycleStage next = ApplicationLifecycleStage.values()[coordinator.getCurrentStage().ordinal() + 1];
                    coordinator.transitionTo(next);
                }
            } catch (Exception ignored) {
            }
            coordinator = null;
        }
        TrystUiServices.dispose();
    }

    @Test
    void testStandardStartupAndOsgiServiceRegistration() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        assertNotNull(context, "OSGi BundleContext must be available");

        coordinator = new ApplicationLifecycleCoordinator(context);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        // Verify TrystServerLifecycleService is registered in OSGi
        TrystServerLifecycleService service = TrystUiServices.getServerLifecycleService();
        assertNotNull(service, "TrystServerLifecycleService must be resolvable via OSGi Service Registry");
        assertSame(coordinator, service, "Registered service must be the authoritative coordinator");

        // Verify TrystHttpService is also registered
        TrystHttpService http = TrystUiServices.getHttpService();
        assertNotNull(http, "TrystHttpService must be resolvable via OSGi Service Registry");
        assertTrue(http.isRunning(), "HTTP server must be running");

        // Verify HTTP GET /api/status returns 200 OK
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + http.getHost() + ":" + http.getPort() + "/api/status"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\": \"UP\""));

        // Only one canonical service registration of TrystHttpService
        Collection<ServiceReference<TrystHttpService>> allRefs = context.getServiceReferences(TrystHttpService.class, null);
        assertEquals(1, allRefs.size(), "There must be exactly one TrystHttpService registered in OSGi");
    }

    @Test
    void testManualStartWhenAutomaticStartupHasNotRun() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        assertNotNull(context, "OSGi BundleContext must be available");

        // Coordinator instantiated without BundleContext, driving startup manually
        coordinator = new ApplicationLifecycleCoordinator();

        // Directly invoke startHttpServer on coordinator
        TrystHttpService http = coordinator.startHttpServer();
        assertNotNull(http);
        assertTrue(http.isRunning());
        assertTrue(coordinator.isHttpServerRunning());

        // Verify /api/status works
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + http.getHost() + ":" + http.getPort() + "/api/status"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void testStartAfterStopAndRestart() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        coordinator = new ApplicationLifecycleCoordinator(context);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        TrystHttpService s1 = TrystUiServices.getHttpService();
        assertNotNull(s1);
        assertTrue(s1.isRunning());
        int port1 = s1.getPort();

        // 1. Stop via UI Services
        TrystServerLifecycleService lifecycle = TrystUiServices.getServerLifecycleService();
        assertNotNull(lifecycle);
        assertSame(coordinator, lifecycle);

        TrystUiServices.stopHttpServer();
        assertFalse(coordinator.isHttpServerRunning());
        assertNull(TrystUiServices.getHttpService(), "Http service must be unregistered upon stop");

        // 2. Start after Stop via UI Services
        TrystHttpService s2 = TrystUiServices.startHttpServer();
        assertNotNull(s2);
        assertTrue(s2.isRunning());
        assertTrue(coordinator.isHttpServerRunning());

        // Verify /api/status responds on s2
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + s2.getHost() + ":" + s2.getPort() + "/api/status"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        // 3. Restart via UI Services
        TrystHttpService s3 = TrystUiServices.restartHttpServer();
        assertNotNull(s3);
        assertTrue(s3.isRunning());

        HttpResponse<String> resp2 = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp2.statusCode());
    }

    @Test
    void testConcurrentAndRepeatedStartRequests() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        coordinator = new ApplicationLifecycleCoordinator(context);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<TrystHttpService>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return TrystUiServices.startHttpServer();
            }));
        }

        List<TrystHttpService> results = new ArrayList<>();
        for (Future<TrystHttpService> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Verify that all threads received the exact same running instance
        TrystHttpService first = results.get(0);
        assertNotNull(first);
        assertTrue(first.isRunning());
        for (TrystHttpService svc : results) {
            assertSame(first, svc, "All concurrent start requests must resolve the same singleton server instance");
        }

        // Verify exactly one registration
        Collection<ServiceReference<TrystHttpService>> refs = context.getServiceReferences(TrystHttpService.class, null);
        assertEquals(1, refs.size(), "Must not register duplicate HTTP services");
    }

    @Test
    void testShutdownReleasesPortAndUnregistersServices() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(getClass()).getBundleContext();
        coordinator = new ApplicationLifecycleCoordinator(context);
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        int boundPort = coordinator.getHttpService().getPort();
        assertTrue(coordinator.isHttpServerRunning());

        // Dispose services
        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
        coordinator = null;

        // Verify services unregistered from OSGi
        ServiceReference<TrystServerLifecycleService> lRef = context.getServiceReference(TrystServerLifecycleService.class);
        assertNull(lRef, "TrystServerLifecycleService must be unregistered on disposal");

        ServiceReference<TrystHttpService> hRef = context.getServiceReference(TrystHttpService.class);
        assertNull(hRef, "TrystHttpService must be unregistered on disposal");

        // Verify socket is actually released and port can be bound again
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress("127.0.0.1", boundPort));
            assertTrue(ss.isBound(), "Port must be unbound and free for reuse after shutdown");
        }
    }
}
