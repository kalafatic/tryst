package eu.kalafatic.tryst.infrastructure.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java 21 standard embedded HTTP server for TRYST.
 * Delivers REST endpoints (/api/status, /api/projects, /api/tasks) and static single-page application assets.
 */
public class TrystHttpServer implements TrystHttpService {

    private final String host;
    private final int port;
    private final WorkspaceService workspaceService;

    private HttpServer server;
    private ExecutorService httpExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TrystHttpServer(String host, int port, WorkspaceService workspaceService) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.workspaceService = workspaceService;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
            this.httpExecutor = Executors.newFixedThreadPool(10, r -> {
                Thread t = new Thread(r, "tryst-http-worker-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });
            this.server.setExecutor(httpExecutor);

            setupRoutes();

            this.server.start();
            this.running.set(true);
            TrystLog.info(TrystHttpServer.class, "HTTP Server started on http://" + host + ":" + getPort() + "/");
        } catch (IOException e) {
            this.running.set(false);
            throw new TrystInfrastructureException("Failed to start HTTP server on " + host + ":" + port, e);
        }
    }

    private void setupRoutes() {
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/projects", new ProjectsHandler());
        server.createContext("/api/tasks", new TasksHandler());
        server.createContext("/", new StaticAssetHandler());
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        TrystLog.info(TrystHttpServer.class, "Stopping HTTP Server...");
        if (server != null) {
            server.stop(1); // 1 second maximum delay
            server = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        TrystLog.info(TrystHttpServer.class, "HTTP Server stopped.");
    }

    @Override
    public boolean isRunning() {
        return running.get() && server != null;
    }

    @Override
    public int getPort() {
        if (server != null) {
            return server.getAddress().getPort();
        }
        return port;
    }

    @Override
    public String getHost() {
        return host;
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            String json = """
                    {
                      "status": "UP",
                      "server": "TRYST",
                      "host": "%s",
                      "httpPort": %d
                    }
                    """.formatted(host, getPort()).trim();
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class ProjectsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"projects\": [");
            if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                Collection<Project> projects = workspaceService.getCurrentWorkspace().getProjects();
                int i = 0;
                for (Project p : projects) {
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"name\": \"%s\", \"status\": \"%s\"}",
                            escapeJson(p.getId()), escapeJson(p.getName()), p.getStatus().name()));
                    i++;
                }
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private class TasksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"tasks\": [");
            if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                Collection<AgentTask> tasks = workspaceService.getCurrentWorkspace().getTasks();
                int i = 0;
                for (AgentTask t : tasks) {
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"title\": \"%s\", \"status\": \"%s\"}",
                            escapeJson(t.getId()), escapeJson(t.getTitle()), t.getStatus().name()));
                    i++;
                }
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private static class StaticAssetHandler implements HttpHandler {
        private static final String DEFAULT_HTML = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>TRYST – Autonomous Development Platform</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 40px; background: #0f172a; color: #f8fafc; }
                        h1 { color: #38bdf8; font-weight: 600; }
                        .card { background: #1e293b; padding: 24px; border-radius: 8px; border: 1px solid #334155; max-width: 600px; margin-top: 20px; }
                        .badge { display: inline-block; padding: 4px 8px; background: #059669; color: white; border-radius: 4px; font-size: 12px; font-weight: bold; }
                    </style>
                </head>
                <body>
                    <h1>TRYST Server</h1>
                    <div class="card">
                        <p><span class="badge">ONLINE</span> Eclipse RCP / Equinox Autonomous Platform</p>
                        <p>REST Endpoints available at <code>/api/status</code>, <code>/api/projects</code>, <code>/api/tasks</code>.</p>
                    </div>
                </body>
                </html>
                """.stripIndent();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
                return;
            }

            // Look up embedded resource in classpath if available
            InputStream in = getClass().getResourceAsStream("/web" + (path.equals("/") ? "/index.html" : path));
            byte[] content;
            String contentType = "text/html; charset=UTF-8";

            if (in != null) {
                try (in) {
                    content = in.readAllBytes();
                }
                if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
                else if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
                else if (path.endsWith(".json")) contentType = "application/json; charset=UTF-8";
            } else {
                content = DEFAULT_HTML.getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
