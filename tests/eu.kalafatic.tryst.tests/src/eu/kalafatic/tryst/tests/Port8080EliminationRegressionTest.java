package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.config.RuntimeEndpoint;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.port.*;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import eu.kalafatic.tryst.infrastructure.server.TrystTcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authoritative regression test suite validating the complete elimination of Port 8080
 * and centralized server port management across the TRYST platform.
 * <p>
 * Tests verifying the 9 criteria:
 * 1. TRYST HTTP defaults to 38080.
 * 2. TRYST TCP defaults to 38280.
 * 3. No server listener defaults to 8080 and binding to 8080 is prohibited.
 * 4. Legacy persisted 8080 configuration is migrated correctly to 38080.
 * 5. Explicit valid user configuration is preserved.
 * 6. Configuration survives restart.
 * 7. Port conflicts never cause fallback to 8080.
 * 8. Alternative allocation works and is persistent.
 * 9. All internal clients use the effective endpoint.
 */
public class Port8080EliminationRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void test1_TrystHttpDefaultsTo38080() {
        ApplicationConfiguration config = ApplicationConfiguration.createDefault();
        assertEquals(38080, config.httpPort(), "HTTP default port must be 38080");

        PortAllocationGroup group = PortAllocationGroup.createDefault("127.0.0.1");
        assertEquals(38080, group.getPort(ServicePortIdentifier.HTTP_SERVICE), "PortAllocationGroup HTTP default must be 38080");
    }

    @Test
    void test2_TrystTcpDefaultsTo38280() {
        ApplicationConfiguration config = ApplicationConfiguration.createDefault();
        assertEquals(38280, config.tcpPort(), "TCP default port must be 38280");

        PortAllocationGroup group = PortAllocationGroup.createDefault("127.0.0.1");
        assertEquals(38280, group.getPort(ServicePortIdentifier.TCP_SERVICE), "PortAllocationGroup TCP default must be 38280");
    }

    @Test
    void test3_NoServerListenerDefaultsTo8080AndBindingTo8080Prohibited() {
        // ApplicationConfiguration throws exception when port 8080 is configured
        ApplicationConfiguration base = ApplicationConfiguration.createDefault();
        assertThrows(TrystApplicationException.class, () -> base.withServerSettings("127.0.0.1", 8080, 38280));
        assertThrows(TrystApplicationException.class, () -> base.withServerSettings("127.0.0.1", 38080, 8080));
        assertThrows(TrystApplicationException.class, () -> base.withDynamicNetworking(true, 8080, 8180, "", false));

        // TrystHttpServer throws exception if port 8080 is attempted
        TrystHttpServer httpServer = new TrystHttpServer("127.0.0.1", 8080, null);
        assertThrows(TrystInfrastructureException.class, httpServer::start);

        // PortAllocationGroup throws exception if 8080 or EVO range is configured
        assertThrows(IllegalArgumentException.class, () -> {
            new PortAllocationGroup(
                    30000, 0, "127.0.0.1", 1, Instant.now(),
                    java.util.Map.of(ServicePortIdentifier.HTTP_SERVICE, new PortConfiguration(
                            ServicePortIdentifier.HTTP_SERVICE, 8080, 8080, 0, PortProtocol.HTTP, "127.0.0.1", true, 1, Instant.now()
                    ))
            );
        });
    }

    @Test
    void test4_LegacyPersisted8080ConfigurationMigratedCorrectly() throws IOException {
        Path configDir = tempDir.resolve("legacy-test");
        Files.createDirectories(configDir);

        // 1. Simulate legacy tryst.properties with server.http.port=8080
        Path propFile = configDir.resolve("tryst.properties");
        Properties legacyProps = new Properties();
        legacyProps.setProperty("server.host", "127.0.0.1");
        legacyProps.setProperty("server.http.port", "8080");
        legacyProps.setProperty("server.tcp.port", "8280");
        legacyProps.setProperty("server.portRangeStart", "8080");
        legacyProps.setProperty("server.portRangeEnd", "8180");
        try (FileOutputStream fos = new FileOutputStream(propFile.toFile())) {
            legacyProps.store(fos, "Legacy Config");
        }

        // 2. Simulate legacy port-allocations.properties with assignedPort=8080
        Path portAllocFile = configDir.resolve(PortPersistence.PORT_CONFIG_FILE_NAME);
        Properties legacyAlloc = new Properties();
        legacyAlloc.setProperty("allocation.baseOffset", "0");
        legacyAlloc.setProperty("allocation.groupOffset", "0");
        legacyAlloc.setProperty("allocation.bindingAddress", "127.0.0.1");
        legacyAlloc.setProperty("allocation.version", "1");
        legacyAlloc.setProperty("allocation.timestamp", Instant.now().toString());
        legacyAlloc.setProperty("service.http.defaultPort", "8080");
        legacyAlloc.setProperty("service.http.assignedPort", "8080");
        legacyAlloc.setProperty("service.http.offset", "0");
        legacyAlloc.setProperty("service.http.protocol", "HTTP");
        legacyAlloc.setProperty("service.http.bindingAddress", "127.0.0.1");
        legacyAlloc.setProperty("service.http.enabled", "true");
        legacyAlloc.setProperty("service.http.version", "1");
        legacyAlloc.setProperty("service.http.timestamp", Instant.now().toString());
        try (FileOutputStream fos = new FileOutputStream(portAllocFile.toFile())) {
            legacyAlloc.store(fos, "Legacy Allocations");
        }

        // 3. Initialize ConfigurationService on the legacy directory
        ConfigurationService configService = new ConfigurationService(configDir);

        // Verify configuration was migrated
        ApplicationConfiguration loadedCfg = configService.getConfiguration();
        assertEquals(38080, loadedCfg.httpPort(), "Migrated HTTP port must be 38080");
        assertEquals(38280, loadedCfg.tcpPort(), "Migrated TCP port must be 38280");
        assertEquals(38080, loadedCfg.portRangeStart(), "Migrated rangeStart must be 38080");
        assertEquals(38580, loadedCfg.portRangeEnd(), "Migrated rangeEnd must be 38580");

        // Verify PortRegistry group was migrated
        PortAllocationGroup group = configService.getPortRegistry().getAllocationGroup();
        assertEquals(38080, group.getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(30000, group.baseOffset());

        // Verify file persisted the migration
        Properties reloaded = new Properties();
        try (var in = Files.newInputStream(propFile)) {
            reloaded.load(in);
        }
        assertEquals("38080", reloaded.getProperty("server.http.port"));
    }

    @Test
    void test5_ExplicitValidUserConfigurationIsPreserved() throws IOException {
        Path configDir = tempDir.resolve("valid-user-test");
        Files.createDirectories(configDir);

        Path propFile = configDir.resolve("tryst.properties");
        Properties userProps = new Properties();
        userProps.setProperty("server.host", "127.0.0.1");
        userProps.setProperty("server.http.port", "38999");
        userProps.setProperty("server.tcp.port", "38888");
        userProps.setProperty("server.portRangeStart", "38900");
        userProps.setProperty("server.portRangeEnd", "39000");
        try (FileOutputStream fos = new FileOutputStream(propFile.toFile())) {
            userProps.store(fos, "Custom User Config");
        }

        ConfigurationService configService = new ConfigurationService(configDir);
        ApplicationConfiguration loadedCfg = configService.getConfiguration();

        assertEquals(38999, loadedCfg.httpPort(), "Explicit valid user HTTP port must be preserved");
        assertEquals(38888, loadedCfg.tcpPort(), "Explicit valid user TCP port must be preserved");
        assertEquals(38900, loadedCfg.portRangeStart(), "Explicit valid portRangeStart must be preserved");
        assertEquals(39000, loadedCfg.portRangeEnd(), "Explicit valid portRangeEnd must be preserved");
    }

    @Test
    void test6_ConfigurationSurvivesRestart() throws IOException {
        Path configDir = tempDir.resolve("restart-test");
        ConfigurationService service1 = new ConfigurationService(configDir);

        // Update configuration to a valid custom configuration
        ApplicationConfiguration custom = service1.getConfiguration()
                .withServerSettings("127.0.0.1", 38100, 38300);
        service1.updateConfiguration(custom);

        // Update PortAllocationGroup offset to +200
        PortAllocationGroup shifted = service1.getPortRegistry().getAllocationGroup().withGroupOffset(200);
        service1.getPortAllocationManager().setEffectiveGroup(shifted);

        // Reboot: instantiate a fresh ConfigurationService instance
        ConfigurationService service2 = new ConfigurationService(configDir);
        assertEquals(38100, service2.getConfiguration().httpPort(), "HTTP port must survive restart");
        assertEquals(38300, service2.getConfiguration().tcpPort(), "TCP port must survive restart");

        PortAllocationGroup reloadedGroup = service2.getPortRegistry().getAllocationGroup();
        assertEquals(200, reloadedGroup.groupOffset(), "PortAllocationGroup offset must survive restart");
        assertEquals(38280, reloadedGroup.getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(38480, reloadedGroup.getPort(ServicePortIdentifier.TCP_SERVICE));
    }

    @Test
    void test7_PortConflictsNeverCauseFallbackTo8080() throws IOException {
        PortPersistence persistence = new PortPersistence(tempDir.resolve("conflict-test"));
        PortConflictResolver resolver = new PortConflictResolver();
        PortAvailabilityChecker checker = new PortAvailabilityChecker();
        PortAllocationManager manager = new PortAllocationManager(persistence, resolver, checker);

        int defaultHttpPort = PortAllocationGroup.DEFAULT_TRYST_HTTP_PORT; // 38080

        // Occupy port 38080 with a server socket bound to 127.0.0.1
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", defaultHttpPort));

            PortAllocationGroup resolved = manager.resolveAllocations("127.0.0.1", null);

            assertNotNull(resolved);
            int resolvedHttp = resolved.getPort(ServicePortIdentifier.HTTP_SERVICE);
            assertNotEquals(defaultHttpPort, resolvedHttp, "Occupied port must not be chosen");
            assertNotEquals(8080, resolvedHttp, "Must NEVER fall back to port 8080");
            assertTrue(resolvedHttp >= 30000, "Must adhere to TRYST +30000 base offset");
            assertEquals(100, resolved.groupOffset(), "Next available group must use +100 offset");
            assertEquals(38180, resolvedHttp);
        }
    }

    @Test
    void test8_AlternativeAllocationWorksAndIsPersistent() throws IOException {
        Path storeDir = tempDir.resolve("alt-persistence-test");
        PortPersistence persistence1 = new PortPersistence(storeDir);
        PortConflictResolver resolver = new PortConflictResolver();
        PortAvailabilityChecker checker = new PortAvailabilityChecker();
        PortAllocationManager manager1 = new PortAllocationManager(persistence1, resolver, checker);

        int defaultHttpPort = PortAllocationGroup.DEFAULT_TRYST_HTTP_PORT; // 38080

        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", defaultHttpPort));

            PortAllocationGroup alt = manager1.resolveAllocations("127.0.0.1", report -> PortConflictAction.ALLOCATE_ALTERNATIVE);
            assertEquals(38180, alt.getPort(ServicePortIdentifier.HTTP_SERVICE));
            assertEquals(38380, alt.getPort(ServicePortIdentifier.TCP_SERVICE));
        }

        // Reboot and verify persistence loaded the alternative group without falling back to 38080 or 8080
        PortPersistence persistence2 = new PortPersistence(storeDir);
        Optional<PortAllocationGroup> loaded = persistence2.loadAllocations();
        assertTrue(loaded.isPresent());
        assertEquals(100, loaded.get().groupOffset());
        assertEquals(38180, loaded.get().getPort(ServicePortIdentifier.HTTP_SERVICE));
        assertEquals(38380, loaded.get().getPort(ServicePortIdentifier.TCP_SERVICE));
    }

    @Test
    void test9_AllInternalClientsUseEffectiveEndpoint() {
        ConfigurationService configService = new ConfigurationService(tempDir.resolve("client-endpoint-test"));
        TrystHttpServer server = new TrystHttpServer("127.0.0.1", 0, null);
        server.setDynamicPortConfiguration(true, 38080, 38580);
        server.setPortRegistry(configService.getPortRegistry());
        server.setPhase4Dependencies(
                null, null, null, null, null, null, null, null, null, null, null, null,
                configService, null, null, null
        );

        server.start();
        int actualBoundPort = server.getPort();
        assertTrue(actualBoundPort > 0);
        assertNotEquals(8080, actualBoundPort, "Listener must never bind to 8080");

        // Verify bound port was registered in PortRegistry
        int regPort = configService.getPortRegistry().getEffectivePort(ServicePortIdentifier.HTTP_SERVICE);
        assertEquals(actualBoundPort, regPort, "PortRegistry must reflect actual bound port");

        // Verify RuntimeEndpointRegistry was updated
        RuntimeEndpoint ep = configService.getEndpointRegistry().getCurrentEndpoint();
        assertEquals(actualBoundPort, ep.httpPort());
        assertTrue(ep.internalUrl().contains(":" + actualBoundPort + "/tryst"), "Internal URL must match bound port");
        assertEquals("http://localhost/tryst", ep.canonicalUrl(), "Canonical URL remains http://localhost/tryst");

        server.stop();
    }
}
