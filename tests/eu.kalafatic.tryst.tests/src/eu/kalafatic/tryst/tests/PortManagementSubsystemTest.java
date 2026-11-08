package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.port.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authoritative regression test suite for centralized, persistent, and dynamically
 * adaptive port management across the TRYST platform.
 */
public class PortManagementSubsystemTest {

    @TempDir
    Path tempDir;

    @Test
    void testDefaultPlus30000MappingAnd100PortSpacing() {
        PortAllocationGroup group = PortAllocationGroup.createDefault("127.0.0.1");

        assertEquals(30000, group.baseOffset());
        assertEquals(0, group.groupOffset());

        int http = group.getPort(ServicePortIdentifier.HTTP_SERVICE);
        int api = group.getPort(ServicePortIdentifier.API_SERVICE);
        int tcp = group.getPort(ServicePortIdentifier.TCP_SERVICE);
        int mgmt = group.getPort(ServicePortIdentifier.MANAGEMENT_SERVICE);
        int ws = group.getPort(ServicePortIdentifier.WEBSOCKET_SERVICE);

        int aux = group.getPort(ServicePortIdentifier.AUXILIARY_SERVICE);
        int debug = group.getPort(ServicePortIdentifier.DEBUG_SERVICE);

        assertEquals(38080, http, "HTTP default port must be 38080");
        assertEquals(38180, api, "API default port must be 38180");
        assertEquals(38280, tcp, "TCP default port must be 38280");
        assertEquals(38380, mgmt, "Management default port must be 38380");
        assertEquals(38480, ws, "WebSocket default port must be 38480");
        assertEquals(38580, aux, "Auxiliary default port must be 38580");
        assertEquals(38680, debug, "Debug default port must be 38680");

        // Verify 100-port spacing between services
        assertEquals(100, api - http);
        assertEquals(100, tcp - api);
        assertEquals(100, mgmt - tcp);
        assertEquals(100, ws - mgmt);
        assertEquals(100, aux - ws);
        assertEquals(100, debug - aux);
    }

    @Test
    void testCoherentPortGroupShiftPreservesSpacing() {
        PortAllocationGroup group = PortAllocationGroup.createDefault("127.0.0.1");
        PortAllocationGroup shifted = group.withGroupOffset(100);

        assertEquals(100, shifted.groupOffset());
        assertEquals(38180, shifted.getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(38280, shifted.getPort(ServicePortIdentifier.API_SERVICE));
        assertEquals(38380, shifted.getPort(ServicePortIdentifier.TCP_SERVICE));
        assertEquals(38480, shifted.getPort(ServicePortIdentifier.MANAGEMENT_SERVICE));
        assertEquals(38580, shifted.getPort(ServicePortIdentifier.WEBSOCKET_SERVICE));
        assertEquals(38680, shifted.getPort(ServicePortIdentifier.AUXILIARY_SERVICE));
        assertEquals(38780, shifted.getPort(ServicePortIdentifier.DEBUG_SERVICE));

        // Spacing preserved
        int http = shifted.getPort(ServicePortIdentifier.HTTP_SERVICE);
        int api = shifted.getPort(ServicePortIdentifier.API_SERVICE);
        int tcp = shifted.getPort(ServicePortIdentifier.TCP_SERVICE);
        int mgmt = shifted.getPort(ServicePortIdentifier.MANAGEMENT_SERVICE);
        int ws = shifted.getPort(ServicePortIdentifier.WEBSOCKET_SERVICE);
        int aux = shifted.getPort(ServicePortIdentifier.AUXILIARY_SERVICE);
        int debug = shifted.getPort(ServicePortIdentifier.DEBUG_SERVICE);

        assertEquals(100, api - http);
        assertEquals(100, tcp - api);
        assertEquals(100, mgmt - tcp);
        assertEquals(100, ws - mgmt);
        assertEquals(100, aux - ws);
        assertEquals(100, debug - aux);
    }

    @Test
    void testConflictDetectionAndProcessIdentification() throws IOException {
        PortAvailabilityChecker checker = new PortAvailabilityChecker();

        // Bind a dummy server socket on an ephemeral port
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = socket.getLocalPort();

            assertFalse(checker.isPortAvailable("127.0.0.1", port), "Occupied port must not be available");

            Optional<ProcessPortInfo> info = checker.identifyConflictingProcess(port);
            assertTrue(info.isPresent(), "Process info must be reported for occupied port");
            assertNotNull(info.get().name());
        }
    }

    @Test
    void testAlternativeGroupAllocationWhenPortOccupied() throws IOException {
        PortPersistence persistence = new PortPersistence(tempDir);
        PortConflictResolver resolver = new PortConflictResolver();
        PortAvailabilityChecker checker = new PortAvailabilityChecker();
        PortAllocationManager manager = new PortAllocationManager(persistence, resolver, checker);

        PortAllocationGroup defaultGroup = PortAllocationGroup.createDefault("127.0.0.1");
        int targetHttpPort = defaultGroup.getPort(ServicePortIdentifier.HTTP_SERVICE);

        // Occupy default HTTP port 38080
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", targetHttpPort));

            PortAllocationGroup resolved = manager.resolveAllocations("127.0.0.1", report -> {
                assertTrue(report.hasConflicts());
                assertEquals(1, report.conflicts().size());
                assertEquals(targetHttpPort, report.conflicts().get(0).port());
                assertNotNull(report.alternativeGroup());
                return PortConflictAction.ALLOCATE_ALTERNATIVE;
            });

            assertNotNull(resolved);
            assertNotEquals(targetHttpPort, resolved.getPort(ServicePortIdentifier.HTTP_SERVICE));
            assertEquals(100, resolved.groupOffset());
            assertEquals(38180, resolved.getPort(ServicePortIdentifier.HTTP_SERVICE));
            assertEquals(38380, resolved.getPort(ServicePortIdentifier.TCP_SERVICE));
        }
    }

    @Test
    void testPersistenceSurvivesRestart() {
        Path storeDir = tempDir.resolve("persistence-test");
        PortPersistence persistence1 = new PortPersistence(storeDir);
        PortAllocationGroup group = PortAllocationGroup.createDefault("127.0.0.1").withGroupOffset(200);

        persistence1.saveAllocations(group);

        // Simulate reboot with fresh persistence instance
        PortPersistence persistence2 = new PortPersistence(storeDir);
        Optional<PortAllocationGroup> loadedOpt = persistence2.loadAllocations();

        assertTrue(loadedOpt.isPresent(), "Allocations must be persisted and loaded successfully");
        PortAllocationGroup loaded = loadedOpt.get();
        assertEquals(200, loaded.groupOffset());
        assertEquals(38280, loaded.getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(38480, loaded.getPort(ServicePortIdentifier.TCP_SERVICE));
    }

    @Test
    void testProcessTerminationSafetyNeverKillsWithoutExplicitConfirmation() {
        PortConflictResolver resolver = new PortConflictResolver();
        ProcessPortInfo currentProcess = new ProcessPortInfo(ProcessHandle.current().pid(), "tryst-test", "", "", true);
        PortConflictReport.PortConflictItem item = new PortConflictReport.PortConflictItem(
                ServicePortIdentifier.HTTP_SERVICE, 38080, currentProcess
        );

        // Resolver must refuse to kill the current process
        boolean result = resolver.terminateConflictingProcess(item);
        assertFalse(result, "Resolver must refuse to terminate current process");
    }

    @Test
    void testPortRegistryRuntimeBindingAndListeners() {
        PortRegistry registry = new PortRegistry();
        AtomicInteger updatedPort = new AtomicInteger(-1);
        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        registry.addPortChangeListener((service, port) -> {
            if (ServicePortIdentifier.HTTP_SERVICE.equals(service)) {
                updatedPort.set(port);
                listenerCalled.set(true);
            }
        });

        registry.registerBoundPort(ServicePortIdentifier.HTTP_SERVICE, 38180);

        assertTrue(listenerCalled.get());
        assertEquals(38180, updatedPort.get());
        assertEquals(38180, registry.getEffectivePort(ServicePortIdentifier.HTTP_SERVICE));
    }

    @Test
    void testConfigurationServiceIntegratedPortRegistry() {
        ConfigurationService configService = new ConfigurationService(tempDir.resolve("config-test"));
        assertNotNull(configService.getPortRegistry());
        assertNotNull(configService.getPortAllocationManager());

        PortAllocationGroup group = configService.getPortRegistry().getAllocationGroup();
        assertNotNull(group);
        assertEquals(38080, group.getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(38280, group.getPort(ServicePortIdentifier.TCP_SERVICE));
    }

    @Test
    void testDuplicatePortAssignmentRejection() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PortAllocationGroup(
                    30000, 0, "127.0.0.1", 1, java.time.Instant.now(),
                    java.util.Map.of(
                            ServicePortIdentifier.HTTP_SERVICE, new PortConfiguration(
                                    ServicePortIdentifier.HTTP_SERVICE, 38080, 38080, 0, PortProtocol.HTTP, "127.0.0.1", true, 1, java.time.Instant.now()
                            ),
                            ServicePortIdentifier.API_SERVICE, new PortConfiguration(
                                    ServicePortIdentifier.API_SERVICE, 38180, 38080, 0, PortProtocol.API, "127.0.0.1", true, 1, java.time.Instant.now()
                            )
                    )
            );
        });
    }

    @Test
    void testConcurrentPortAllocationThreadSafety() throws InterruptedException {
        PortPersistence persistence = new PortPersistence(tempDir.resolve("concurrent-alloc"));
        PortConflictResolver resolver = new PortConflictResolver();
        PortAvailabilityChecker checker = new PortAvailabilityChecker();
        PortAllocationManager manager = new PortAllocationManager(persistence, resolver, checker);

        int threadCount = 8;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    PortAllocationGroup group = manager.resolveAllocations("127.0.0.1", null);
                    if (group != null && group.getPort(ServicePortIdentifier.HTTP_SERVICE) >= 30000) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount, successCount.get());
    }

    @Test
    void testEvoPortRangeIsolation() {
        // EVO range is standard 8080-8480
        // TRYST standard uses base offset +30000
        PortAllocationGroup trystGroup = PortAllocationGroup.createDefault("127.0.0.1");

        for (PortConfiguration cfg : trystGroup.configurations().values()) {
            int port = cfg.assignedPort();
            assertTrue(port >= 30000, "TRYST ports must be segregated by at least +30000 offset: " + port);
            assertFalse(port >= 8080 && port <= 8500, "TRYST must never overlap with EVO port range (8080-8500): " + port);
        }
    }
}
