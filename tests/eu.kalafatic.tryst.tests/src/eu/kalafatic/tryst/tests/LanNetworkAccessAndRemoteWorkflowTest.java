package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.TrystServerApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.config.NetworkAddressDetector;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that TRYST headless server binds correctly in LAN mode and
 * that a complete remote development workflow executes across the LAN network IP.
 */
public class LanNetworkAccessAndRemoteWorkflowTest {

    private String oldDevMode;
    private String oldHttpPort;
    private String oldTcpPort;
    private String oldNetMode;
    private String oldHome;

    @BeforeEach
    void setUp() {
        oldDevMode = System.getProperty("tryst.dev.mode");
        oldHttpPort = System.getProperty("tryst.http.port");
        oldTcpPort = System.getProperty("tryst.tcp.port");
        oldNetMode = System.getProperty("tryst.network.mode");
        oldHome = System.getProperty("user.home");
    }

    @AfterEach
    void tearDown() {
        restoreProp("tryst.dev.mode", oldDevMode);
        restoreProp("tryst.http.port", oldHttpPort);
        restoreProp("tryst.tcp.port", oldTcpPort);
        restoreProp("tryst.network.mode", oldNetMode);
        restoreProp("user.home", oldHome);
    }

    private void restoreProp(String key, String val) {
        if (val != null) {
            System.setProperty(key, val);
        } else {
            System.clearProperty(key);
        }
    }

    @Test
    void testLanBindingAndCompleteRemoteTaskWorkflow() throws Exception {
        String lanIp = NetworkAddressDetector.detectLanIpAddress();
        assertNotNull(lanIp);
        assertNotEquals("127.0.0.1", lanIp, "Test environment should have a non-loopback LAN interface (e.g. 192.168.0.2)");

        System.setProperty("tryst.dev.mode", "true");
        System.setProperty("tryst.network.mode", "LAN");
        System.setProperty("tryst.http.port", "38080");
        System.setProperty("tryst.tcp.port", "38280");

        Path tempDir = Files.createTempDirectory("tryst-lan-test-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        TrystServerApplication serverApp = new TrystServerApplication();
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();
        StartupModeAndReadinessTest.DummyApplicationContext appContext =
                new StartupModeAndReadinessTest.DummyApplicationContext(new String[]{"-headless"});

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Object> future = executor.submit(() -> {
            try {
                return serverApp.runHeadless(appContext, coordinator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            long deadline = System.currentTimeMillis() + 15000;
            while (coordinator.getCurrentStage() != ApplicationLifecycleStage.APPLICATION_READY && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(ApplicationLifecycleStage.APPLICATION_READY, coordinator.getCurrentStage());

            var httpService = coordinator.getHttpService();
            assertNotNull(httpService);
            int port = httpService.getPort();
            String host = httpService.getHost();
            assertEquals(lanIp, host, "HTTP service in LAN mode must bind to the detected LAN IP");

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            // 1. Verify remote access via LAN IP
            HttpRequest getWeb = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + lanIp + ":" + port + "/tryst"))
                    .GET()
                    .build();
            HttpResponse<String> webRes = client.send(getWeb, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, webRes.statusCode(), "Web interface should be directly accessible on LAN IP");
            assertTrue(webRes.body().contains("TRYST Production Platform"));

            // 2. Validate repository pre-check remotely
            String validatePayload = """
                    {
                      "repositoryUrl": "https://github.com/kalafatic/genome",
                      "branch": "master"
                    }
                    """;
            HttpRequest valReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + lanIp + ":" + port + "/api/tasks/intake/validate-repo"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(validatePayload))
                    .build();
            HttpResponse<String> valRes = client.send(valReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valRes.statusCode());
            assertTrue(valRes.body().contains("\"valid\": true"));

            // 3. Submit remote development task
            String submitPayload = """
                    {
                      "repositoryUrl": "https://github.com/kalafatic/genome",
                      "branch": "master",
                      "title": "Analyze repository structure",
                      "description": "Analyze the repository structure and provide a concise technical overview.",
                      "executionOptions": {
                        "forceCleanClone": false,
                        "pullBeforeExecution": false,
                        "autoExecute": true
                      }
                    }
                    """;
            HttpRequest submitReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + lanIp + ":" + port + "/api/tasks/intake/submit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(submitPayload))
                    .build();
            HttpResponse<String> submitRes = client.send(submitReq, HttpResponse.BodyHandlers.ofString());
            assertTrue(submitRes.statusCode() == 200 || submitRes.statusCode() == 201, "Expected 200 or 201 Created from task intake submit");
            assertTrue(submitRes.body().contains("\"taskId\":"));

            // Extract taskId
            String b = submitRes.body();
            int idIdx = b.indexOf("\"taskId\":");
            int q1 = b.indexOf("\"", idIdx + 9);
            int q2 = b.indexOf("\"", q1 + 1);
            String taskId = b.substring(q1 + 1, q2);
            assertFalse(taskId.isBlank());

            // 4. Poll task execution progress until finished
            long pollDeadline = System.currentTimeMillis() + 60000;
            String lastStatus = "UNKNOWN";
            while (System.currentTimeMillis() < pollDeadline) {
                HttpRequest pollReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + lanIp + ":" + port + "/api/tasks"))
                        .GET()
                        .build();
                HttpResponse<String> pollRes = client.send(pollReq, HttpResponse.BodyHandlers.ofString());
                String pBody = pollRes.body();
                if (pBody.contains(taskId)) {
                    if (pBody.contains("\"status\": \"WAITING_FOR_REVIEW\"")) {
                        lastStatus = "WAITING_FOR_REVIEW";
                        break;
                    }
                    if (pBody.contains("\"status\": \"COMPLETED\"")) {
                        lastStatus = "COMPLETED";
                        break;
                    }
                    if (pBody.contains("\"status\": \"FAILED\"")) {
                        lastStatus = "FAILED";
                        break;
                    }
                }
                Thread.sleep(1000);
            }

            assertTrue("WAITING_FOR_REVIEW".equals(lastStatus) || "COMPLETED".equals(lastStatus),
                    "Expected task to reach WAITING_FOR_REVIEW or COMPLETED, but reached: " + lastStatus);

            // 5. Inspect reviews endpoint
            HttpRequest revReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + lanIp + ":" + port + "/api/reviews"))
                    .GET()
                    .build();
            HttpResponse<String> revRes = client.send(revReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, revRes.statusCode());
            assertTrue(revRes.body().contains(taskId));

        } finally {
            serverApp.stop();
            future.get(5, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }
}
