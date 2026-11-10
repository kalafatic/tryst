package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.TrystServerApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class EmergencyDevelopmentModeE2ETest {

    @Test
    void testEndToEndRepositoryIntakeAndAiWorkflow() throws Exception {
        System.setProperty("tryst.dev.mode", "true");
        System.setProperty("tryst.http.port", "38099");
        System.setProperty("tryst.tcp.port", "38299");

        Path tempDir = Files.createTempDirectory("tryst-dev-e2e-");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());

        TrystServerApplication serverApp = new TrystServerApplication();
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();
        StartupModeAndReadinessTest.DummyApplicationContext appContext =
                new StartupModeAndReadinessTest.DummyApplicationContext(new String[]{"-headless"});

        var executor = Executors.newSingleThreadExecutor();
        Future<Object> future = executor.submit(() -> {
            try {
                return serverApp.runHeadless(appContext, coordinator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            long deadline = System.currentTimeMillis() + 10000;
            while (coordinator.getCurrentStage() != ApplicationLifecycleStage.APPLICATION_READY && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(ApplicationLifecycleStage.APPLICATION_READY, coordinator.getCurrentStage());

            int port = coordinator.getHttpService().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            // 1. Validate Web Interface accessibility without login
            HttpRequest getWeb = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/tryst"))
                    .GET()
                    .build();
            HttpResponse<String> webRes = client.send(getWeb, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, webRes.statusCode());
            assertTrue(webRes.body().contains("TRYST Production Platform"));

            // 2. Validate repository validation pre-check without authentication
            String validatePayload = """
                    {
                      "repositoryUrl": "https://github.com/kalafatic/genome",
                      "branch": "main"
                    }
                    """;
            HttpRequest valReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks/intake/validate-repo"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(validatePayload))
                    .build();
            HttpResponse<String> valRes = client.send(valReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valRes.statusCode());
            assertTrue(valRes.body().contains("\"valid\": true"));

            // 3. Submit real task: repository https://github.com/kalafatic/genome, prompt: Analyze the repository structure and provide a concise technical overview.
            String submitPayload = """
                    {
                      "repositoryUrl": "https://github.com/kalafatic/genome",
                      "branch": "main",
                      "title": "Analyze repository structure",
                      "description": "Analyze the repository structure and provide a concise technical overview.",
                      "executionOptions": {
                        "forceCleanClone": false,
                        "pullBeforeExecution": false,
                        "autoExecute": true
                      }
                    }
                    """;
            HttpRequest subReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks/intake/submit"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(submitPayload))
                    .build();
            HttpResponse<String> subRes = client.send(subReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, subRes.statusCode());
            assertTrue(subRes.body().contains("taskId"));

            String respBody = subRes.body();
            String taskId = respBody.substring(respBody.indexOf("\"taskId\": \"") + 11);
            taskId = taskId.substring(0, taskId.indexOf("\""));
            assertNotNull(taskId);
            assertFalse(taskId.isBlank());

            // 4. Poll task status until execution completes (WAITING_FOR_REVIEW or COMPLETED)
            long pollDeadline = System.currentTimeMillis() + 60000;
            String lastStatus = "";
            while (System.currentTimeMillis() < pollDeadline) {
                HttpRequest pollReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                        .GET()
                        .build();
                HttpResponse<String> pollRes = client.send(pollReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, pollRes.statusCode());
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

            // 5. Inspect reviews endpoint to verify real AI output and diff generated
            HttpRequest revReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/reviews"))
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
