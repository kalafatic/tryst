package eu.kalafatic.tryst.tests;

import com.sun.net.httpserver.HttpExchange;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.SecurityContext;
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

import static org.junit.jupiter.api.Assertions.*;

class FullPlatformLifecycleAndSecurityIntegrationTest {

    @TempDir
    Path tempDir;

    private ApplicationLifecycleCoordinator coordinator;
    private HttpClient httpClient;

    private String savedDevMode;

    @BeforeEach
    void setUp() {
        savedDevMode = System.getProperty("tryst.dev.mode");
        System.setProperty("tryst.dev.mode", "false");
        System.setProperty("user.home", tempDir.toString());
        System.setProperty("tryst.admin.password", "admin123");
        coordinator = new ApplicationLifecycleCoordinator();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (savedDevMode != null) {
            System.setProperty("tryst.dev.mode", savedDevMode);
        } else {
            System.clearProperty("tryst.dev.mode");
        }
        if (coordinator != null) {
            try {
                coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
                coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
                coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
            } catch (Exception ignored) {}
        }
    }

    @Test
    void testEndToEndProductionPlatformFlow() throws Exception {
        // 1. Initialize complete application lifecycle
        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        int httpPort = coordinator.getHttpService().getPort();
        String baseUrl = "http://127.0.0.1:" + httpPort;

        // 2. Initial status check
        HttpRequest statusReq = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/status")).GET().build();
        HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusResp.statusCode());
        assertTrue(statusResp.body().contains("TRYST"));

        // 3. Verify admin was bootstrapped and authenticate admin
        HttpRequest adminLoginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\": \"admin\", \"password\": \"admin123\"}"))
                .build();
        HttpResponse<String> loginResp = httpClient.send(adminLoginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode());
        String loginBody = loginResp.body();
        assertTrue(loginBody.contains("\"success\": true"));

        // Extract token
        String adminToken = extractJson(loginBody, "token");
        assertNotNull(adminToken);

        // 4. Create regular user 'alice' via admin API
        HttpRequest createUserReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/users"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\": \"alice\", \"password\": \"AliceSecret#1\", \"role\": \"DEVELOPER\"}"))
                .build();
        HttpResponse<String> createUserResp = httpClient.send(createUserReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createUserResp.statusCode());

        // 5. Authenticate 'alice'
        HttpRequest aliceLoginReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\": \"alice\", \"password\": \"AliceSecret#1\"}"))
                .build();
        HttpResponse<String> aliceLoginResp = httpClient.send(aliceLoginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, aliceLoginResp.statusCode());
        String aliceToken = extractJson(aliceLoginResp.body(), "token");
        assertNotNull(aliceToken);

        // 6. Alice creates a project
        HttpRequest createProjectReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/projects"))
                .header("Authorization", "Bearer " + aliceToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"AliceProject\"}"))
                .build();
        HttpResponse<String> projResp = httpClient.send(createProjectReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, projResp.statusCode());
        assertTrue(projResp.body().contains("AliceProject"));

        // 7. Verify Alice CANNOT access admin overview (RBAC enforced)
        HttpRequest unauthAdminReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/overview"))
                .header("Authorization", "Bearer " + aliceToken)
                .GET().build();
        HttpResponse<String> unauthResp = httpClient.send(unauthAdminReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, unauthResp.statusCode(), "Regular user must be forbidden from admin overview");

        // 8. Prove header spoofing fails: An attacker sending X-User-Id: admin cannot access admin API without valid token
        HttpRequest spoofReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/overview"))
                .header("X-User-Id", "admin")
                .GET().build();
        HttpResponse<String> spoofResp = httpClient.send(spoofReq, HttpResponse.BodyHandlers.ofString());
        // Since X-User-Id without valid token resolution is denied or requires authentication
        assertNotEquals(200, spoofResp.statusCode());

        // 9. Admin checks overview and audit log
        HttpRequest adminOverviewReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/admin/overview"))
                .header("Authorization", "Bearer " + adminToken)
                .GET().build();
        HttpResponse<String> overviewResp = httpClient.send(adminOverviewReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, overviewResp.statusCode());
        assertTrue(overviewResp.body().contains("totalUsers"));

        // 10. Invalidate / logout Alice
        HttpRequest logoutReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/logout"))
                .header("Authorization", "Bearer " + aliceToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> logoutResp = httpClient.send(logoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, logoutResp.statusCode());

        // Alice token should now be rejected
        HttpRequest afterLogoutReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/me"))
                .header("Authorization", "Bearer " + aliceToken)
                .GET().build();
        HttpResponse<String> afterLogoutResp = httpClient.send(afterLogoutReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, afterLogoutResp.statusCode());
    }

    private static String extractJson(String json, String field) {
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
