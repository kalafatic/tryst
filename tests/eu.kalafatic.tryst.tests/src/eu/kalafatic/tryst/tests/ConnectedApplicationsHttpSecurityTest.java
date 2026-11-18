package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.http.ConnectedApplicationsHttpHandler;
import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.harness.DefaultConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PasswordHasher;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteAuditEventRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-3.10: authorization boundary tests for the connected-applications management API.
 *
 * <p>Drives the real HTTP boundary (embedded {@link TrystHttpServer} + java.net.http
 * client) with the real {@link AuthenticationService}, Bearer session tokens and the real
 * {@link PermissionManager}, covering: missing credentials, invalid credentials,
 * authenticated principals without MANAGE_AGENTS, authorized administrators, every
 * management operation (list/select/test/config/enable), regression of authorized
 * behavior, the explicit development-mode fallback and fail-closed behavior when
 * authentication cannot be established.</p>
 */
class ConnectedApplicationsHttpSecurityTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private UserRepository userRepository;
    private UserSessionRepository sessionRepository;
    private PermissionManager permissionManager;
    private AuthenticationService authService;
    private ConnectedApplicationRegistry registry;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private String applicationsUrl;

    private String adminToken;
    private String developerToken;
    private String reviewerToken;

    private String savedDevMode;

    @BeforeEach
    void setUp() throws IOException {
        savedDevMode = System.getProperty("tryst.dev.mode");
        System.setProperty("tryst.dev.mode", "false");

        Path dbPath = tempDir.resolve("connected_apps_security.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        permissionManager = new PermissionManager(new SqliteAuditEventRepository(databaseManager));
        userRepository = new SqliteUserRepository(databaseManager);
        sessionRepository = new SqliteUserSessionRepository(databaseManager);
        authService = new AuthenticationService(userRepository, sessionRepository, permissionManager);

        authService.bootstrapAdmin("admin", "AdminPass#2026");

        String developerSalt = PasswordHasher.generateSalt();
        User developer = new User("developer",
                PasswordHasher.hashPassword("DevPass#123".toCharArray(), developerSalt), developerSalt,
                UserRole.DEVELOPER);
        userRepository.save(developer);
        String reviewerSalt = PasswordHasher.generateSalt();
        User reviewer = new User("reviewer",
                PasswordHasher.hashPassword("RevPass#123".toCharArray(), reviewerSalt), reviewerSalt,
                UserRole.REVIEWER);
        userRepository.save(reviewer);

        adminToken = authService.login("admin", "AdminPass#2026", "127.0.0.1", "Test/1.0").token();
        developerToken = authService.login("developer", "DevPass#123", "127.0.0.1", "Test/1.0").token();
        reviewerToken = authService.login("reviewer", "RevPass#123", "127.0.0.1", "Test/1.0").token();
        assertNotNull(adminToken);
        assertNotNull(developerToken);
        assertNotNull(reviewerToken);

        registry = new DefaultConnectedApplicationRegistry(null);
        registry.registerAdapter(new StubHarnessAdapter("opencode", "OpenCode CLI", "OPENCODE", true));

        ConnectedApplicationsHttpHandler handler =
                new ConnectedApplicationsHttpHandler(registry, authService, permissionManager);

        httpServer = new TrystHttpServer("127.0.0.1", 0, null, null, null, null, null, null, permissionManager);
        httpServer.setPhase4Dependencies(authService, userRepository, sessionRepository,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        httpServer.start();
        httpServer.registerHandler("/api/integration/applications", handler);

        applicationsUrl = "http://127.0.0.1:" + httpServer.getPort() + "/tryst/api/integration/applications";
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (savedDevMode != null) {
            System.setProperty("tryst.dev.mode", savedDevMode);
        } else {
            System.clearProperty("tryst.dev.mode");
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    private HttpResponse<String> send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(applicationsUrl + path));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testGetListWithoutCredentialsRejected() throws Exception {
        HttpResponse<String> response = send("GET", "", null, null);
        assertEquals(401, response.statusCode(), "Missing credentials must be rejected with 401");
        assertTrue(response.body().contains("Authentication required"));
    }

    @Test
    void testGetListWithInvalidTokenRejected() throws Exception {
        HttpResponse<String> response = send("GET", "", "invalid-token-00000000000000000000000000000000", null);
        assertEquals(401, response.statusCode(), "Invalid bearer token must be rejected with 401");
    }

    @Test
    void testGetListWithRevokedTokenRejected() throws Exception {
        AuthenticationService.AuthResult revoked =
                authService.login("admin", "AdminPass#2026", "127.0.0.1", "Test/1.0");
        assertNotNull(revoked.token());
        authService.logout(revoked.token());

        HttpResponse<String> response = send("GET", "", revoked.token(), null);
        assertEquals(401, response.statusCode(), "Revoked session token must be rejected with 401");
    }

    @Test
    void testAuthenticatedReviewerWithoutPermissionRejected() throws Exception {
        HttpResponse<String> response = send("GET", "", reviewerToken, null);
        assertEquals(403, response.statusCode(), "Authenticated user without MANAGE_AGENTS must be rejected with 403");
        assertTrue(response.body().contains("MANAGE_AGENTS"));
    }

    @Test
    void testAuthenticatedPlainUserWithoutPermissionRejected() throws Exception {
        String plainSalt = PasswordHasher.generateSalt();
        User plainUser = new User("plainuser",
                PasswordHasher.hashPassword("UserPass#123".toCharArray(), plainSalt), plainSalt,
                UserRole.USER);
        userRepository.save(plainUser);
        String userToken = authService.login("plainuser", "UserPass#123", "127.0.0.1", "Test/1.0").token();
        assertNotNull(userToken);

        HttpResponse<String> response = send("GET", "", userToken, null);
        assertEquals(403, response.statusCode());
    }

    @Test
    void testAuthorizedAdministratorCanList() throws Exception {
        HttpResponse<String> response = send("GET", "", adminToken, null);
        assertEquals(200, response.statusCode(), "Authorized administrator must be permitted");
        assertTrue(response.body().contains("\"id\":\"opencode\""));
        assertTrue(response.body().contains("\"name\":\"OpenCode CLI\""));
    }

    @Test
    void testAuthorizedDeveloperCanList() throws Exception {
        HttpResponse<String> response = send("GET", "", developerToken, null);
        assertEquals(200, response.statusCode(), "DEVELOPER role holds MANAGE_AGENTS and must be permitted");
        assertTrue(response.body().contains("\"id\":\"opencode\""));
    }

    @Test
    void testSelectOperationProtectedAndAuthorized() throws Exception {
        HttpResponse<String> denied = send("POST", "/select", reviewerToken, "{\"applicationId\":\"opencode\"}");
        assertEquals(403, denied.statusCode(), "Selection must be denied for principals without MANAGE_AGENTS");

        HttpResponse<String> unauthorized = send("POST", "/select", null, "{\"applicationId\":\"opencode\"}");
        assertEquals(401, unauthorized.statusCode(), "Selection without credentials must be rejected");

        HttpResponse<String> selected = send("POST", "/select", adminToken, "{\"applicationId\":\"opencode\"}");
        assertEquals(200, selected.statusCode());
        assertTrue(selected.body().contains("\"opencode\""));

        HttpResponse<String> list = send("GET", "", adminToken, null);
        assertTrue(list.body().contains("\"selected\":true"), "Authorized selection must be reflected in the list");
    }

    @Test
    void testConnectionTestOperationProtectedAndAuthorized() throws Exception {
        HttpResponse<String> denied = send("POST", "/opencode/test", reviewerToken, "");
        assertEquals(403, denied.statusCode());

        HttpResponse<String> unauthorized = send("POST", "/opencode/test", null, "");
        assertEquals(401, unauthorized.statusCode());

        HttpResponse<String> tested = send("POST", "/opencode/test", adminToken, "");
        assertEquals(200, tested.statusCode());
        assertTrue(tested.body().contains("\"success\":true"), "Stub adapter connection test must succeed");
    }

    @Test
    void testConfigurationOperationProtectedAndAuthorized() throws Exception {
        HttpResponse<String> denied = send("POST", "/opencode/config", reviewerToken,
                "{\"agent\":\"build\",\"model\":\"qwen3-coder-30b-32k:latest\"}");
        assertEquals(403, denied.statusCode());

        HttpResponse<String> configured = send("POST", "/opencode/config", adminToken,
                "{\"agent\":\"build\",\"model\":\"qwen3-coder-30b-32k:latest\"}");
        assertEquals(200, configured.statusCode());

        HttpResponse<String> list = send("GET", "", adminToken, null);
        assertTrue(list.body().contains("qwen3-coder-30b-32k:latest"),
                "Authorized configuration update must be visible in the list");
    }

    @Test
    void testEnableDisableOperationProtectedAndAuthorized() throws Exception {
        HttpResponse<String> denied = send("POST", "/opencode/enable", reviewerToken, "{\"enabled\":false}");
        assertEquals(403, denied.statusCode());

        HttpResponse<String> disabled = send("POST", "/opencode/enable", adminToken, "{\"enabled\":false}");
        assertEquals(200, disabled.statusCode());

        HttpResponse<String> list = send("GET", "", developerToken, null);
        assertTrue(list.body().contains("\"enabled\":false"),
                "Authorized enable/disable must persist and be visible to authorized readers");

        HttpResponse<String> enabled = send("POST", "/opencode/enable", adminToken, "{\"enabled\":true}");
        assertEquals(200, enabled.statusCode());
        HttpResponse<String> listAfter = send("GET", "", adminToken, null);
        assertTrue(listAfter.body().contains("\"enabled\":true"));
    }

    @Test
    void testAllOperationsDeniedWithoutTokenWhenDevModeDisabled() throws Exception {
        for (String[] op : new String[][]{
                {"GET", ""},
                {"POST", "/select"},
                {"POST", "/opencode/test"},
                {"POST", "/opencode/config"},
                {"POST", "/opencode/enable"}
        }) {
            HttpResponse<String> response = send(op[0], op[1], null, "{\"applicationId\":\"opencode\"}");
            assertEquals(401, response.statusCode(), op[0] + " " + op[1] + " must fail closed without credentials");
        }
    }

    @Test
    void testExplicitDevelopmentModeBypassPreservesLocalBehavior() throws Exception {
        System.setProperty("tryst.dev.mode", "true");
        try {
            HttpResponse<String> response = send("GET", "", null, null);
            assertEquals(200, response.statusCode(),
                    "Explicit emergency development mode must preserve unauthenticated local access (DEV admin context)");
            assertTrue(response.body().contains("\"id\":\"opencode\""));
        } finally {
            System.setProperty("tryst.dev.mode", "false");
        }
    }

    @Test
    void testFailsClosedWhenAuthenticationServiceMissing() throws Exception {
        ConnectedApplicationRegistry isolatedRegistry = new DefaultConnectedApplicationRegistry(null);
        isolatedRegistry.registerAdapter(new StubHarnessAdapter("opencode", "OpenCode CLI", "OPENCODE", true));
        ConnectedApplicationsHttpHandler handler =
                new ConnectedApplicationsHttpHandler(isolatedRegistry, null, permissionManager);

        TrystHttpServer isolatedServer = new TrystHttpServer("127.0.0.1", 0, null, null, null, null, null, null, permissionManager);
        try {
            isolatedServer.start();
            isolatedServer.registerHandler("/api/integration/applications", handler);
            String url = "http://127.0.0.1:" + isolatedServer.getPort() + "/api/integration/applications";

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(),
                    "Without an AuthenticationService the gate must fail closed");
        } finally {
            isolatedServer.stop();
        }
    }

    @Test
    void testCorsPreflightRemainsOpen() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(URI.create(applicationsUrl))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:38080")
                .build();
        HttpResponse<String> response = httpClient.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), "CORS preflight must not require credentials");
    }

    private static class StubHarnessAdapter implements CodingHarnessAdapter {
        private final String id;
        private final String name;
        private final String type;
        private final boolean available;
        private final boolean connectionOk;
        private final Map<String, String> receivedConfig = new java.util.HashMap<>();

        StubHarnessAdapter(String id, String name, String type, boolean available) {
            this(id, name, type, available, true);
        }

        StubHarnessAdapter(String id, String name, String type, boolean available, boolean connectionOk) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.available = available;
            this.connectionOk = connectionOk;
        }

        @Override public String getApplicationId() { return id; }
        @Override public String getApplicationName() { return name; }
        @Override public String getApplicationType() { return type; }
        @Override public List<String> getCapabilities() { return List.of("CODE_IMPLEMENTATION"); }
        @Override public boolean isAvailable() { return available; }
        @Override public ConnectionTestResult testConnection() {
            return connectionOk ? ConnectionTestResult.ok("ok") : ConnectionTestResult.error("unreachable");
        }
        @Override public void updateConnectionConfiguration(Map<String, String> configuration) {
            receivedConfig.putAll(configuration);
        }
        @Override public Map<String, String> getConnectionConfiguration() {
            return new java.util.LinkedHashMap<>(receivedConfig);
        }
        @Override public HarnessExecutionResult executeTask(Task task, Workspace workspace, String instructions,
                String prompt, Consumer<String> logConsumer, BooleanSupplier cancellationSupplier, Duration timeout) {
            return HarnessExecutionResult.ok("stub executed", List.of());
        }
    }
}
