package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.server.TrystHttpServer;
import eu.kalafatic.tryst.infrastructure.server.TrystTcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class CommunicationServiceTest {

    @TempDir
    Path tempDir;

    private TrystTcpServer tcpServer;
    private TrystHttpServer httpServer;

    @BeforeEach
    void setUp() {
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        if (tcpServer != null && tcpServer.isRunning()) {
            tcpServer.stop();
        }
        if (httpServer != null && httpServer.isRunning()) {
            httpServer.stop();
        }
    }

    @Test
    void testTcpServerCommands() throws Exception {
        // Bind to port 0 for dynamic ephemeral port allocation
        tcpServer = new TrystTcpServer("127.0.0.1", 0, null);
        tcpServer.start();

        assertTrue(tcpServer.isRunning());
        int port = tcpServer.getPort();
        assertTrue(port > 0);

        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String banner = reader.readLine();
            assertEquals("TRYST-TCP/1.0 READY", banner);

            // Test PING
            writer.write("PING\n");
            writer.flush();
            assertEquals("PONG", reader.readLine());

            // Test STATUS
            writer.write("STATUS\n");
            writer.flush();
            String statusResp = reader.readLine();
            assertTrue(statusResp.startsWith("OK status=RUNNING"));

            // Test QUIT
            writer.write("QUIT\n");
            writer.flush();
            assertEquals("BYE", reader.readLine());
        }

        tcpServer.stop();
        assertFalse(tcpServer.isRunning());
    }

    @Test
    void testHttpServerEndpointsAndStaticAssets() throws Exception {
        httpServer = new TrystHttpServer("127.0.0.1", 0, null);
        httpServer.start();

        assertTrue(httpServer.isRunning());
        int port = httpServer.getPort();
        assertTrue(port > 0);

        // 1. Test /api/status
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/status").toURL().openConnection();
        conn.setRequestMethod("GET");
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/json; charset=UTF-8", conn.getContentType());
        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"status\": \"UP\""));
        assertTrue(body.contains("\"server\": \"TRYST\""));

        // 2. Test /api/projects
        HttpURLConnection projConn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/projects").toURL().openConnection();
        projConn.setRequestMethod("GET");
        assertEquals(200, projConn.getResponseCode());
        String projs = new String(projConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(projs.contains("\"projects\": ["));

        // 3. Test static index html
        HttpURLConnection staticConn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/").toURL().openConnection();
        staticConn.setRequestMethod("GET");
        assertEquals(200, staticConn.getResponseCode());
        assertEquals("text/html; charset=UTF-8", staticConn.getContentType());
        String html = new String(staticConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("TRYST Server"));

        // 4. Test OPTIONS preflight request
        HttpURLConnection optionsConn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/status").toURL().openConnection();
        optionsConn.setRequestMethod("OPTIONS");
        assertEquals(204, optionsConn.getResponseCode());
        assertEquals("*", optionsConn.getHeaderField("Access-Control-Allow-Origin"));

        httpServer.stop();
        assertFalse(httpServer.isRunning());
    }

    @Test
    void testConcurrentHttpRequests() throws Exception {
        httpServer = new TrystHttpServer("127.0.0.1", 0, null);
        httpServer.start();
        int port = httpServer.getPort();

        int clientCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            tasks.add(() -> {
                HttpURLConnection conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/status").toURL().openConnection();
                conn.setRequestMethod("GET");
                return conn.getResponseCode();
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(tasks);
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get());
        }

        executor.shutdown();
        httpServer.stop();
    }

    @Test
    void testApplicationLifecycleCoordinatorStartsAndStopsCommunicationServices() {
        ApplicationLifecycleCoordinator coordinator = new ApplicationLifecycleCoordinator();

        coordinator.transitionTo(ApplicationLifecycleStage.PLATFORM_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CONFIGURATION_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);

        assertNotNull(coordinator.getTcpService());
        assertNotNull(coordinator.getHttpService());
        assertTrue(coordinator.getTcpService().isRunning());
        assertTrue(coordinator.getHttpService().isRunning());

        coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);

        assertNull(coordinator.getTcpService());
        assertNull(coordinator.getHttpService());

        coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
        assertEquals(ApplicationLifecycleStage.APPLICATION_TERMINATED, coordinator.getCurrentStage());
    }
}
