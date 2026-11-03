package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ServerBootstrapAndNetworkingTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testDynamicPortAllocationAndFallbackOnCollision() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        int occupiedPort = socket.getLocalPort();

        Path dbPath = tempDir.resolve("tryst.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        ConfigurationService configService = new ConfigurationService(tempDir);
        ApplicationConfiguration cfg = configService.getConfiguration().withServerSettings("127.0.0.1", occupiedPort, 9090);
        configService.updateConfiguration(cfg);

        httpServer = new TrystHttpServer("127.0.0.1", occupiedPort, null);
        httpServer.setDynamicPortConfiguration(true, 8080, 8180);
        httpServer.start();

        int actualPort = httpServer.getPort();
        assertNotEquals(occupiedPort, actualPort, "HttpServer should have dynamically bound to an available port");
        assertTrue(httpServer.isRunning(), "HttpServer should be running");

        socket.close();
    }

    @Test
    void testDatabaseIntegrityAndV4Migrations() {
        Path dbPath = tempDir.resolve("tryst_mig.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        assertTrue(databaseManager.verifyDatabaseIntegrity(), "Database integrity should pass PRAGMA integrity_check");

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        assertEquals(5, migrationService.getCurrentVersion(), "Database should be migrated to V5");
    }

    @Test
    void testSecureAdministratorBootstrapAndOneTimeSetupToken() throws Exception {
        Path dbPath = tempDir.resolve("tryst_auth.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        SqliteUserRepository userRepo = new SqliteUserRepository(databaseManager);
        SqliteUserSessionRepository sessionRepo = new SqliteUserSessionRepository(databaseManager);
        AuthenticationService authService = new AuthenticationService(userRepo, sessionRepo, new PermissionManager());

        assertTrue(authService.isSetupRequired(), "Setup must be required when no admin exists");
        String setupToken = authService.generateOneTimeSetupToken();
        assertNotNull(setupToken, "One-time setup token should be generated");

        ConfigurationService configService = new ConfigurationService(tempDir);
        httpServer = new TrystHttpServer("127.0.0.1", 0, null);
        httpServer.setPhase4Dependencies(
                authService, userRepo, sessionRepo, null, null, null, null, null, null, null, null, null, configService, databaseManager, null, null
        );
        httpServer.start();

        int port = httpServer.getPort();

        // Check status endpoint exposes setupRequired: true
        HttpRequest statusReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/status")).GET().build();
        HttpResponse<String> statusRes = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertTrue(statusRes.body().contains("\"setupRequired\": true"), "Status endpoint should report setupRequired true");

        // Perform setup with token
        String setupJson = String.format("{\"token\": \"%s\", \"username\": \"admin\", \"password\": \"SuperSecretPass123!\"}", setupToken);
        HttpRequest setupReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/setup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(setupJson)).build();
        HttpResponse<String> setupRes = httpClient.send(setupReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, setupRes.statusCode(), "Setup submission should succeed");

        assertFalse(authService.isSetupRequired(), "Setup should no longer be required");
        assertNull(authService.getOneTimeSetupToken(), "One-time setup token must be cleared");

        // Repeated setup submission must be rejected
        HttpResponse<String> setupRes2 = httpClient.send(setupReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, setupRes2.statusCode(), "Repeated setup submission must be rejected");
    }

    @Test
    void testAiModelRegistryServiceCatalogAndRoleFiltering() {
        AiModelRegistryService registry = new AiModelRegistryService("codellama");
        registry.registerProvider("ollama-1", "Local Ollama", "OLLAMA", "http://localhost:11434", true);

        registry.publishModel(new AiModelRegistryService.PublishedModel(
                "ollama-1:codellama", "ollama-1", "codellama", "CodeLlama 7B", 4096, true, true, UserRole.USER, null
        ));
        registry.publishModel(new AiModelRegistryService.PublishedModel(
                "ollama-1:llama-admin", "ollama-1", "llama-admin", "Llama Admin 70B", 8192, true, true, UserRole.ADMIN, null
        ));

        List<AiModelRegistryService.PublishedModel> userModels = registry.getModelsForRole(UserRole.USER);
        assertEquals(1, userModels.size(), "Standard USER should only see user-accessible models");
        assertEquals("codellama", userModels.get(0).modelIdentifier());

        List<AiModelRegistryService.PublishedModel> adminModels = registry.getModelsForRole(UserRole.ADMIN);
        assertEquals(2, adminModels.size(), "ADMIN should see both user and admin models");
    }

    @Test
    void testServerReadinessServiceEvaluation() {
        ServerReadinessService readiness = new ServerReadinessService();
        readiness.registerComponent("database", true);
        readiness.registerComponent("ai-provider", false);

        readiness.markHealthy("database", Map.of("version", "V4"));
        readiness.markDegraded("ai-provider", "Ollama unreachable", Map.of());

        ServerReadinessService.ReadinessReport report = readiness.evaluateReadiness(ApplicationLifecycleStage.APPLICATION_READY);
        assertTrue(report.ready(), "Server should be ready since mandatory database is healthy");
        assertTrue(report.degraded(), "Server should report degraded status due to optional AI provider");
    }
}
