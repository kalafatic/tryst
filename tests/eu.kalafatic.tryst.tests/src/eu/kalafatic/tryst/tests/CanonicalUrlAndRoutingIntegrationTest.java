package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.config.RuntimeEndpoint;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated tests for canonical URL /tryst routing, dynamic port allocation,
 * session cookies, redirects, and reverse proxy / external URL publication.
 */
public class CanonicalUrlAndRoutingIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private TrystHttpServer httpServer;
    private HttpClient httpClient;
    private ConfigurationService configurationService;
    private AuthenticationService authenticationService;
    private SqliteUserRepository userRepository;
    private SqliteUserSessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        Path dbPath = tempDir.resolve("tryst_url_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        userRepository = new SqliteUserRepository(databaseManager);
        sessionRepository = new SqliteUserSessionRepository(databaseManager);
        authenticationService = new AuthenticationService(userRepository, sessionRepository, new PermissionManager());
        authenticationService.bootstrapAdmin("admin", "AdminPass123!");

        configurationService = new ConfigurationService(tempDir);
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
    void testContextPathRoutingAndRedirectFromRoot() throws Exception {
        httpServer = new TrystHttpServer("127.0.0.1", 0, null);
        httpServer.setPhase4Dependencies(
                authenticationService, userRepository, sessionRepository,
                null, null, null, null, null, null, null, null, null,
                configurationService, databaseManager, null, null
        );
        httpServer.start();

        int port = httpServer.getPort();

        // 1. Root "/" request must redirect to canonical context path "/tryst"
        HttpRequest rootReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        HttpResponse<String> rootRes = httpClient.send(rootReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(302, rootRes.statusCode(), "Root request must return 302 redirect");
        assertEquals("/tryst", rootRes.headers().firstValue("Location").orElse(""), "Redirect must point to /tryst");

        // 2. Request directly to "/tryst" serves the web application HTML
        HttpRequest appReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/tryst")).GET().build();
        HttpResponse<String> appRes = httpClient.send(appReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, appRes.statusCode(), "Access to /tryst must succeed");
        assertTrue(appRes.body().contains("TRYST Production Platform"), "Response must contain TRYST application UI");

        // 3. Status endpoint accessible under /tryst/api/status
        HttpRequest statusReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/tryst/api/status")).GET().build();
        HttpResponse<String> statusRes = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusRes.statusCode(), "/tryst/api/status must be reachable");
        assertTrue(statusRes.body().contains("\"status\": \"UP\""), "Status endpoint must return UP");

        // 4. Backward-compatible /api/status also reachable
        HttpRequest compatReq = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + "/api/status")).GET().build();
        HttpResponse<String> compatRes = httpClient.send(compatReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, compatRes.statusCode(), "/api/status must remain reachable for backward compatibility");
    }

    @Test
    void testAuthenticationAndCookieHandlingUnderContextPath() throws Exception {
        httpServer = new TrystHttpServer("127.0.0.1", 0, null);
        httpServer.setPhase4Dependencies(
                authenticationService, userRepository, sessionRepository,
                null, null, null, null, null, null, null, null, null,
                configurationService, databaseManager, null, null
        );
        httpServer.start();

        int port = httpServer.getPort();

        // 1. Login via /tryst/api/auth/login
        String loginPayload = "{\"username\": \"admin\", \"password\": \"AdminPass123!\"}";
        HttpRequest loginReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/tryst/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginPayload))
                .build();
        HttpResponse<String> loginRes = httpClient.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginRes.statusCode(), "Login under /tryst/api/auth/login must succeed");

        // Verify Set-Cookie header sets tryst_session with Path=/tryst
        String cookieHeader = loginRes.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookieHeader.contains("tryst_session="), "Set-Cookie must set tryst_session");
        assertTrue(cookieHeader.contains("Path=/tryst"), "Cookie Path must be scoped to /tryst");
        assertTrue(cookieHeader.contains("HttpOnly"), "Cookie must be HttpOnly");

        // Extract token
        String sessionCookie = cookieHeader.split(";")[0].trim();

        // 2. Access /tryst/api/auth/me using Cookie instead of Authorization header
        HttpRequest meReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/tryst/api/auth/me"))
                .header("Cookie", sessionCookie)
                .GET()
                .build();
        HttpResponse<String> meRes = httpClient.send(meReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, meRes.statusCode(), "Cookie-authenticated /tryst/api/auth/me must return 200");
        assertTrue(meRes.body().contains("\"username\": \"admin\""), "Response should identify authenticated admin");

        // 3. Logout via /tryst/api/auth/logout clears cookie
        HttpRequest logoutReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/tryst/api/auth/logout"))
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> logoutRes = httpClient.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutRes.statusCode(), "Logout must succeed");
        String clearCookie = logoutRes.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(clearCookie.contains("Max-Age=0"), "Logout must clear the cookie");
    }

    @Test
    void testCanonicalUrlDecoupledFromDynamicInternalPort() {
        // Preferred port 80 collision or unprivileged simulation
        httpServer = new TrystHttpServer("127.0.0.1", 80, null);
        httpServer.setDynamicPortConfiguration(true, 8080, 8180);
        httpServer.setPhase4Dependencies(
                authenticationService, userRepository, sessionRepository,
                null, null, null, null, null, null, null, null, null,
                configurationService, databaseManager, null, null
        );
        httpServer.start();

        int boundPort = httpServer.getPort();
        assertNotEquals(80, boundPort, "Server should fall back from unprivileged port 80 to dynamic range");

        RuntimeEndpoint ep = configurationService.getEndpointRegistry().getCurrentEndpoint();
        assertNotNull(ep, "Runtime endpoint must be registered");
        assertEquals("http://localhost/tryst", ep.canonicalUrl(), "Canonical URL must strictly remain http://localhost/tryst");
        assertEquals("/tryst", ep.contextPath(), "Context path must be /tryst");
        assertTrue(ep.internalUrl().contains(":" + boundPort + "/tryst"), "Internal URL must reflect actual listener port and context");
    }

    @Test
    void testReverseProxyConfigurationResolution() {
        ApplicationConfiguration cfg = configurationService.getConfiguration()
                .withDynamicNetworking(true, 8080, 8180, "https://developer.kalafatic.eu/tryst", true);
        configurationService.updateConfiguration(cfg);

        String effectiveUrl = cfg.resolveEffectiveExternalUrl(8123);
        assertEquals("https://developer.kalafatic.eu/tryst", effectiveUrl, "Reverse proxy URL must override internal host:port");
    }
}
