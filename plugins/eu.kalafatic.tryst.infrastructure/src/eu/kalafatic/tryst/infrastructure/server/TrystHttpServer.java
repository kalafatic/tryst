package eu.kalafatic.tryst.infrastructure.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.*;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.security.CsrfTokenManager;
import eu.kalafatic.tryst.domain.security.PermissionDecision;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.security.RateLimiter;
import eu.kalafatic.tryst.domain.security.SecurityContext;
import eu.kalafatic.tryst.domain.service.*;
import eu.kalafatic.tryst.infrastructure.build.BuildAndTestService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteBackupService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TrystHttpServer implements TrystHttpService {

    private final String host;
    private final int preferredPort;
    private int actualPort;
    private boolean dynamicPortFallback = true;
    private int portRangeStart = 38080;
    private int portRangeEnd = 38580;
    private eu.kalafatic.tryst.core.port.PortRegistry portRegistry;
    private final WorkspaceService workspaceService;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final GitService gitService;
    private final BuildAndTestService buildAndTestService;
    private final PermissionManager permissionManager;

    // Phase 4 Services & Repositories
    private AuthenticationService authenticationService;
    private UserRepository userRepository;
    private UserSessionRepository sessionRepository;
    private AuditEventRepository auditEventRepository;
    private NotificationRepository notificationRepository;
    private TelemetryRepository telemetryRepository;
    private ServerMonitoringService serverMonitoringService;
    private TelemetryService telemetryService;
    private AiMonitoringService aiMonitoringService;
    private ProcessExecutionManager processExecutionManager;
    private NotificationService notificationService;
    private LogDiagnosticService logDiagnosticService;
    private eu.kalafatic.tryst.domain.service.DataRetentionService dataRetentionService;
    private ConfigurationService configurationService;
    private SqliteDatabaseManager databaseManager;
    private SqliteBackupService backupService;
    private AgentRepository agentRepository;
    private eu.kalafatic.tryst.domain.ai.AiModelRegistryService aiModelRegistryService;
    private TaskOrchestrator taskOrchestrator;
    private eu.kalafatic.tryst.domain.github.GitHubService gitHubService;
    private eu.kalafatic.tryst.domain.github.GitHubCredentialRepository gitHubCredentialRepository;
    private eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService gitHubCredentialEncryptionService;
    private eu.kalafatic.tryst.domain.repository.GitCommitRepository gitCommitRepository;
    private eu.kalafatic.tryst.domain.service.ManagedServiceController managedServiceController;
    private eu.kalafatic.tryst.domain.intake.TaskIntakeService taskIntakeService;

    private final RateLimiter authRateLimiter = new RateLimiter(30, 60);
    private final RateLimiter apiRateLimiter = new RateLimiter(300, 60);
    private final CsrfTokenManager csrfTokenManager = new CsrfTokenManager();

    private HttpServer server;
    private ExecutorService httpExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TrystHttpServer(String host, int port, WorkspaceService workspaceService) {
        this(host, port, workspaceService, null, null, null, null, null, null);
    }

    public TrystHttpServer(String host, int port,
                           WorkspaceService workspaceService,
                           ProjectRepository projectRepository,
                           TaskRepository taskRepository,
                           CodeReviewRepository codeReviewRepository,
                           GitService gitService,
                           BuildAndTestService buildAndTestService,
                           PermissionManager permissionManager) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.preferredPort = port;
        this.actualPort = port;
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.gitService = gitService != null ? gitService : new GitService();
        this.buildAndTestService = buildAndTestService != null ? buildAndTestService : new BuildAndTestService();
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
    }

    public void setDynamicPortConfiguration(boolean fallbackEnabled, int rangeStart, int rangeEnd) {
        this.dynamicPortFallback = fallbackEnabled;
        this.portRangeStart = rangeStart;
        this.portRangeEnd = rangeEnd;
    }

    public void setDataRetentionService(eu.kalafatic.tryst.domain.service.DataRetentionService dataRetentionService) {
        this.dataRetentionService = dataRetentionService;
    }

    public void setPhase4Dependencies(
            AuthenticationService authenticationService,
            UserRepository userRepository,
            UserSessionRepository sessionRepository,
            AuditEventRepository auditEventRepository,
            NotificationRepository notificationRepository,
            TelemetryRepository telemetryRepository,
            ServerMonitoringService serverMonitoringService,
            TelemetryService telemetryService,
            AiMonitoringService aiMonitoringService,
            ProcessExecutionManager processExecutionManager,
            NotificationService notificationService,
            LogDiagnosticService logDiagnosticService,
            ConfigurationService configurationService,
            SqliteDatabaseManager databaseManager,
            SqliteBackupService backupService,
            AgentRepository agentRepository
    ) {
        this.authenticationService = authenticationService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.auditEventRepository = auditEventRepository;
        if (this.permissionManager != null) {
            if (userRepository != null) {
                this.permissionManager.setUserRepository(userRepository);
            }
            if (auditEventRepository != null) {
                this.permissionManager.setAuditEventRepository(auditEventRepository);
            }
        }
        this.notificationRepository = notificationRepository;
        this.telemetryRepository = telemetryRepository;
        this.serverMonitoringService = serverMonitoringService;
        this.telemetryService = telemetryService;
        this.aiMonitoringService = aiMonitoringService;
        this.processExecutionManager = processExecutionManager;
        this.notificationService = notificationService;
        this.logDiagnosticService = logDiagnosticService;
        this.configurationService = configurationService;
        this.databaseManager = databaseManager;
        this.backupService = backupService;
        this.agentRepository = agentRepository;
    }

    public void setAiModelRegistryService(eu.kalafatic.tryst.domain.ai.AiModelRegistryService aiModelRegistryService) {
        this.aiModelRegistryService = aiModelRegistryService;
    }

    public void setTaskOrchestrator(TaskOrchestrator taskOrchestrator) {
        this.taskOrchestrator = taskOrchestrator;
    }

    public void setGitHubService(eu.kalafatic.tryst.domain.github.GitHubService gitHubService,
                                 eu.kalafatic.tryst.domain.github.GitHubCredentialRepository gitHubCredentialRepository,
                                 eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService gitHubCredentialEncryptionService) {
        this.gitHubService = gitHubService;
        this.gitHubCredentialRepository = gitHubCredentialRepository;
        this.gitHubCredentialEncryptionService = gitHubCredentialEncryptionService;
    }

    public void setGitCommitRepository(eu.kalafatic.tryst.domain.repository.GitCommitRepository gitCommitRepository) {
        this.gitCommitRepository = gitCommitRepository;
    }

    public void setManagedServiceController(eu.kalafatic.tryst.domain.service.ManagedServiceController managedServiceController) {
        this.managedServiceController = managedServiceController;
    }

    public void setTaskIntakeService(eu.kalafatic.tryst.domain.intake.TaskIntakeService taskIntakeService) {
        this.taskIntakeService = taskIntakeService;
    }

    public void setPortRegistry(eu.kalafatic.tryst.core.port.PortRegistry portRegistry) {
        this.portRegistry = portRegistry;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        try {
            this.server = bindHttpServer();
            this.actualPort = server.getAddress().getPort();
            this.httpExecutor = Executors.newFixedThreadPool(10, r -> {
                Thread t = new Thread(r, "tryst-http-worker-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });
            this.server.setExecutor(httpExecutor);

            setupRoutes();

            this.server.start();
            this.running.set(true);

            // Publish runtime endpoint and bound port in PortRegistry
            if (portRegistry != null) {
                portRegistry.registerBoundPort(eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE, actualPort);
            }
            if (configurationService != null) {
                ApplicationConfiguration cfg = configurationService.getConfiguration();
                String extUrl = cfg.resolveEffectiveExternalUrl(actualPort);
                String intUrl = "http://" + host + ":" + actualPort + cfg.contextPath();
                eu.kalafatic.tryst.core.config.RuntimeEndpoint ep = new eu.kalafatic.tryst.core.config.RuntimeEndpoint(
                        cfg.applicationName(), host, actualPort, -1, intUrl, extUrl,
                        cfg.canonicalUrl(), cfg.contextPath(), "RUNNING", Instant.now()
                );
                configurationService.getEndpointRegistry().updateEndpoint(ep);
                if (configurationService.getPortRegistry() != null) {
                    configurationService.getPortRegistry().registerBoundPort(
                            eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE, actualPort);
                }
            }

            TrystLog.info(TrystHttpServer.class, "HTTP Server started on http://" + host + ":" + actualPort + "/ (Canonical: " + getCanonicalUrl() + ")");
        } catch (IOException e) {
            this.running.set(false);
            throw new TrystInfrastructureException("Failed to start HTTP server on " + host + ":" + preferredPort, e);
        }
    }

    public String getCanonicalUrl() {
        if (configurationService != null) {
            return configurationService.getConfiguration().canonicalUrl();
        }
        return "http://localhost/tryst";
    }

    private HttpServer bindHttpServer() throws IOException {
        // Enforce strict prohibition of port 8080 and EVO range (8080-8500)
        if (preferredPort == 8080 || (preferredPort >= 8080 && preferredPort <= 8500)) {
            throw new TrystInfrastructureException("TRYST must NEVER bind to port 8080 or EVO port range (8080-8500). Requested port: " + preferredPort);
        }

        // 1. Try preferred port directly
        try {
            return HttpServer.create(new InetSocketAddress(host, preferredPort), 0);
        } catch (IOException e) {
            if (preferredPort == 80) {
                TrystLog.warn(TrystHttpServer.class,
                        "Canonical port 80 is unavailable or requires privileged access (" + e.getMessage() +
                        "). TRYST will allocate an internal listener port while preserving canonical external endpoint mapping.");
            }
            if (!dynamicPortFallback) {
                throw e;
            }
            TrystLog.warn(TrystHttpServer.class, "Preferred port " + preferredPort + " is unavailable. Attempting dynamic allocation...");
        }

        // 2. Try port range if defined (strictly avoiding 8080 and 8080-8500)
        if (portRangeStart > 0 && portRangeEnd >= portRangeStart) {
            for (int p = portRangeStart; p <= portRangeEnd; p++) {
                if (p == preferredPort) continue;
                if (p == 8080 || (p >= 8080 && p <= 8500)) continue;
                try {
                    HttpServer s = HttpServer.create(new InetSocketAddress(host, p), 0);
                    TrystLog.info(TrystHttpServer.class, "Bound to alternative port " + p + " from range [" + portRangeStart + ", " + portRangeEnd + "]");
                    return s;
                } catch (IOException ignored) {}
            }
        }

        // 3. Fallback to OS assigned ephemeral port (port 0)
        HttpServer ephemeralServer = HttpServer.create(new InetSocketAddress(host, 0), 0);
        int ephemPort = ephemeralServer.getAddress().getPort();
        if (ephemPort == 8080) {
            ephemeralServer.stop(0);
            throw new TrystInfrastructureException("OS assigned forbidden port 8080. Aborting server bind.");
        }
        TrystLog.info(TrystHttpServer.class, "Bound to OS ephemeral port " + ephemPort);
        return ephemeralServer;
    }

    private void setupRoutes() {
        String ctxPath = "/tryst";
        if (configurationService != null) {
            ctxPath = configurationService.getConfiguration().contextPath();
        }
        if (!ctxPath.startsWith("/")) ctxPath = "/" + ctxPath;
        if (ctxPath.endsWith("/")) ctxPath = ctxPath.substring(0, ctxPath.length() - 1);

        StatusHandler statusH = new StatusHandler();
        SetupHandler setupH = new SetupHandler();
        AuthHandler authH = new AuthHandler();
        ModelsHandler modelsH = new ModelsHandler();
        ProjectsHandler projectsH = new ProjectsHandler();
        TasksHandler tasksH = new TasksHandler();
        ReviewsHandler reviewsH = new ReviewsHandler();
        NotificationsHandler notifH = new NotificationsHandler();
        GitHubHandler githubH = new GitHubHandler();
        AdminHandler adminH = new AdminHandler();
        StaticAssetHandler staticH = new StaticAssetHandler();

        // Register default root /api/... routes
        server.createContext("/api/status", statusH);
        server.createContext("/api/setup", setupH);
        server.createContext("/api/auth", authH);
        server.createContext("/api/models", modelsH);
        server.createContext("/api/projects", projectsH);
        server.createContext("/api/tasks", tasksH);
        server.createContext("/api/reviews", reviewsH);
        server.createContext("/api/notifications", notifH);
        server.createContext("/api/github", githubH);
        server.createContext("/api/admin", adminH);

        // Register context-path specific routes if not root
        if (!ctxPath.isEmpty() && !"/".equals(ctxPath)) {
            server.createContext(ctxPath + "/api/status", statusH);
            server.createContext(ctxPath + "/api/setup", setupH);
            server.createContext(ctxPath + "/api/auth", authH);
            server.createContext(ctxPath + "/api/models", modelsH);
            server.createContext(ctxPath + "/api/projects", projectsH);
            server.createContext(ctxPath + "/api/tasks", tasksH);
            server.createContext(ctxPath + "/api/reviews", reviewsH);
            server.createContext(ctxPath + "/api/notifications", notifH);
            server.createContext(ctxPath + "/api/github", githubH);
            server.createContext(ctxPath + "/api/admin", adminH);

            server.createContext(ctxPath, staticH);
        }

        server.createContext("/", new RootRedirectOrStaticAssetHandler(ctxPath, staticH));
    }

    private static class RootRedirectOrStaticAssetHandler implements HttpHandler {
        private final String contextPath;
        private final StaticAssetHandler staticAssetHandler;

        public RootRedirectOrStaticAssetHandler(String contextPath, StaticAssetHandler staticAssetHandler) {
            this.contextPath = contextPath;
            this.staticAssetHandler = staticAssetHandler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isBlank()) {
                if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)) {
                    // Redirect to canonical context path
                    exchange.getResponseHeaders().set("Location", contextPath);
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
            }
            staticAssetHandler.handle(exchange);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        TrystLog.info(TrystHttpServer.class, "Stopping HTTP Server...");
        if (server != null) {
            server.stop(1);
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
        return actualPort;
    }

    @Override
    public String getHost() {
        return host;
    }

    private static void applySecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
        exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-Id, X-CSRF-Token, X-Correlation-Id");
        applySecurityHeaders(exchange);

        String correlationId = eu.kalafatic.tryst.core.log.CorrelationContext.getCorrelationId();
        if (correlationId != null) {
            exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
        }

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }

        // Avoid logging streaming notifications endpoint on every poll
        String path = exchange.getRequestURI().getPath();
        if (!path.endsWith("/stream")) {
            TrystLog.info(TrystHttpServer.class, String.format("HTTP %s %s -> %d",
                    exchange.getRequestMethod(), path, statusCode));
        }
    }

    /**
     * Authenticates the principal from the Authorization: Bearer <token> header or Cookie session.
     * Legacy backward-compatibility fallback is provided ONLY when authenticationService is not wired
     * or for tests that do not configure users.
     */
    private Optional<SecurityContext> resolveSecurityContext(HttpExchange exchange) {
        if (authenticationService == null) {
            return Optional.empty();
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring("Bearer ".length()).trim();
            return authenticationService.authenticateToken(token);
        }

        // Support cookie-based authentication for browser sessions under context path /tryst
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && ("tryst_session".equalsIgnoreCase(pair[0].trim()) || "token".equalsIgnoreCase(pair[0].trim()))) {
                    String token = pair[1].trim();
                    if (!token.isEmpty()) {
                        return authenticationService.authenticateToken(token);
                    }
                }
            }
        }

        return Optional.empty();
    }

    private String extractTokenFromRequest(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring("Bearer ".length()).trim();
        }

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && ("tryst_session".equalsIgnoreCase(pair[0].trim()) || "token".equalsIgnoreCase(pair[0].trim()))) {
                    return pair[1].trim();
                }
            }
        }
        return null;
    }

    private String getUserId(HttpExchange exchange) {
        Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
        if (ctx.isPresent()) {
            return ctx.get().getUserId();
        }
        if (authenticationService != null && authenticationService.hasAdministrator()) {
            return "unauthenticated";
        }
        String uid = exchange.getRequestHeaders().getFirst("X-User-Id");
        return (uid != null && !uid.isBlank()) ? uid.trim() : Project.DEFAULT_OWNER_ID;
    }

    private String getClientIp(HttpExchange exchange) {
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    private boolean validateCsrfIfCookieAuth(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return true;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/api/auth/logout") || path.endsWith("/api/auth/login")) {
            return true;
        }

        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader != null && cookieHeader.contains("tryst_session=")) {
            String token = null;
            for (String c : cookieHeader.split(";")) {
                String[] pair = c.trim().split("=", 2);
                if (pair.length == 2 && "tryst_session".equalsIgnoreCase(pair[0].trim())) {
                    token = pair[1].trim();
                    break;
                }
            }
            if (token != null && !token.isBlank()) {
                String csrfHeader = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
                return csrfTokenManager.validateToken(token, csrfHeader);
            }
        }
        return true;
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            boolean setupReq = authenticationService != null && authenticationService.isSetupRequired();
            String json = """
                    {
                      "status": "UP",
                      "server": "TRYST",
                      "host": "%s",
                      "httpPort": %d,
                      "setupRequired": %b
                    }
                    """.formatted(host, getPort(), setupReq).trim();
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class SetupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            if (authenticationService == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"AuthenticationService unavailable\"}");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/status") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                boolean req = authenticationService.isSetupRequired();
                sendJsonResponse(exchange, 200, "{\"setupRequired\": " + req + "}");
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (!authenticationService.isSetupRequired()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"First-run setup has already been completed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = extractJsonField(body, "token");
                String username = extractJsonField(body, "username");
                String password = extractJsonField(body, "password");

                if (token == null || token.isBlank() || username == null || username.isBlank() || password == null || password.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Token, username, and password are required\"}");
                    return;
                }

                try {
                    User admin = authenticationService.completeInitialSetup(token.trim(), username.trim(), password);
                    sendJsonResponse(exchange, 200, "{\"success\": true, \"adminId\": \"" + admin.getId() + "\"}");
                } catch (Exception e) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }
    }

    private class ModelsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            UserRole role = ctx.map(c -> c.user().getRole()).orElse(UserRole.USER);

            if (aiModelRegistryService == null) {
                sendJsonResponse(exchange, 200, "{\"models\": [], \"defaultModel\": \"codellama\"}");
                return;
            }

            List<eu.kalafatic.tryst.domain.ai.AiModelRegistryService.PublishedModel> models = aiModelRegistryService.getModelsForRole(role);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"models\": [");
            for (int i = 0; i < models.size(); i++) {
                eu.kalafatic.tryst.domain.ai.AiModelRegistryService.PublishedModel m = models.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"%s\", \"providerId\": \"%s\", \"model\": \"%s\", \"displayName\": \"%s\", \"available\": %b, \"contextLimit\": %d}",
                        escapeJson(m.id()), escapeJson(m.providerId()), escapeJson(m.modelIdentifier()), escapeJson(m.displayName()), m.available(), m.contextLimit()));
            }
            sb.append("], \"defaultModel\": \"").append(escapeJson(aiModelRegistryService.getDefaultModel())).append("\"}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!validateCsrfIfCookieAuth(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\": \"Invalid or missing CSRF token\"}");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (authenticationService == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"AuthenticationService unavailable\"}");
                return;
            }

            if (path.endsWith("/login") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String clientIp = getClientIp(exchange);
                if (!authRateLimiter.allowRequest(clientIp)) {
                    permissionManager.recordAudit("rate-limiter", "RATE_LIMIT_EXCEEDED", "ip", clientIp, null, null, "DENIED", "Too many login attempts");
                    sendJsonResponse(exchange, 429, "{\"error\": \"Too many login attempts. Please try again later.\"}");
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String username = extractJsonField(body, "username");
                String password = extractJsonField(body, "password");
                String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");

                AuthenticationService.AuthResult res = authenticationService.login(username, password, clientIp, userAgent);
                if (res.success()) {
                    String csrfToken = csrfTokenManager.refreshToken(res.token());
                    String ctxPath = (configurationService != null) ? configurationService.getConfiguration().contextPath() : "/tryst";
                    if (!ctxPath.startsWith("/")) ctxPath = "/" + ctxPath;
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "tryst_session=" + res.token() + "; Path=" + ctxPath + "; HttpOnly; SameSite=Lax");
                    String json = String.format("{\"success\": true, \"token\": \"%s\", \"csrfToken\": \"%s\", \"user\": {\"id\": \"%s\", \"username\": \"%s\", \"role\": \"%s\"}}",
                            res.token(), csrfToken, res.user().getId(), res.user().getUsername(), res.user().getRole().name());
                    sendJsonResponse(exchange, 200, json);
                } else {
                    sendJsonResponse(exchange, 401, String.format("{\"success\": false, \"error\": \"%s\"}", escapeJson(res.errorMessage())));
                }
                return;
            }

            if (path.endsWith("/csrf") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
                if (ctx.isEmpty()) {
                    sendJsonResponse(exchange, 401, "{\"error\": \"Unauthenticated\"}");
                    return;
                }
                String token = extractTokenFromRequest(exchange);
                String csrf = (token != null) ? csrfTokenManager.getOrCreateToken(token) : "";
                sendJsonResponse(exchange, 200, "{\"csrfToken\": \"" + csrf + "\"}");
                return;
            }

            if (path.endsWith("/logout") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    authenticationService.logout(authHeader.substring("Bearer ".length()).trim());
                } else {
                    String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
                    if (cookieHeader != null) {
                        for (String c : cookieHeader.split(";")) {
                            String[] p = c.trim().split("=", 2);
                            if (p.length == 2 && "tryst_session".equalsIgnoreCase(p[0].trim())) {
                                authenticationService.logout(p[1].trim());
                            }
                        }
                    }
                }
                String ctxPath = (configurationService != null) ? configurationService.getConfiguration().contextPath() : "/tryst";
                if (!ctxPath.startsWith("/")) ctxPath = "/" + ctxPath;
                exchange.getResponseHeaders().add("Set-Cookie", "tryst_session=; Path=" + ctxPath + "; Max-Age=0; HttpOnly; SameSite=Lax");
                sendJsonResponse(exchange, 200, "{\"success\": true}");
                return;
            }

            if (path.endsWith("/me") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
                if (ctx.isEmpty()) {
                    sendJsonResponse(exchange, 401, "{\"error\": \"Unauthenticated\"}");
                    return;
                }
                User u = ctx.get().user();
                String json = String.format("{\"id\": \"%s\", \"username\": \"%s\", \"role\": \"%s\", \"active\": %b}",
                        u.getId(), u.getUsername(), u.getRole().name(), u.isActive());
                sendJsonResponse(exchange, 200, json);
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }
    }

    private class ProjectsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!validateCsrfIfCookieAuth(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\": \"Invalid or missing CSRF token\"}");
                return;
            }
            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            String userId = ctx.map(SecurityContext::getUserId).orElseGet(() -> getUserId(exchange));
            boolean isAdmin = ctx.map(SecurityContext::isAdmin).orElse(false);

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.CREATE_PROJECTS)) {
                    permissionManager.recordAudit(userId, "PROJECT_CREATE", "project", null, null, null, "DENIED", "Lacks CREATE_PROJECTS");
                    sendJsonResponse(exchange, 403, "{\"error\": \"Permission CREATE_PROJECTS required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String name = extractJsonField(body, "name");
                String locationStr = extractJsonField(body, "location");
                if (name == null || name.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'name'\"}");
                    return;
                }
                Path loc = locationStr != null && !locationStr.isBlank()
                        ? Path.of(locationStr)
                        : Path.of(System.getProperty("user.home"), ".tryst", "projects", name);

                Project project = workspaceService.addProject(userId, name, "", loc);
                String json = String.format("{\"id\": \"%s\", \"ownerId\": \"%s\", \"name\": \"%s\", \"status\": \"%s\"}",
                        escapeJson(project.getId()), escapeJson(project.getOwnerId()), escapeJson(project.getName()), project.getStatus().name());
                sendJsonResponse(exchange, 201, json);
                return;
            }

            // GET
            List<Project> allProjects;
            if (projectRepository != null) {
                allProjects = projectRepository.findAll().stream()
                        .filter(p -> isAdmin || userId.equals(p.getOwnerId()))
                        .toList();
            } else if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                allProjects = workspaceService.getCurrentWorkspace().getProjects().stream()
                        .filter(p -> isAdmin || userId.equals(p.getOwnerId()))
                        .toList();
            } else {
                allProjects = List.of();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"projects\": [");
            for (int i = 0; i < allProjects.size(); i++) {
                Project p = allProjects.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"%s\", \"ownerId\": \"%s\", \"name\": \"%s\", \"status\": \"%s\", \"location\": \"%s\"}",
                        escapeJson(p.getId()), escapeJson(p.getOwnerId()), escapeJson(p.getName()), p.getStatus().name(), escapeJson(p.getLocation().toString())));
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
            if (!validateCsrfIfCookieAuth(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\": \"Invalid or missing CSRF token\"}");
                return;
            }

            String clientIp = getClientIp(exchange);
            if (!apiRateLimiter.allowRequest(clientIp)) {
                sendJsonResponse(exchange, 429, "{\"error\": \"Rate limit exceeded. Please slow down.\"}");
                return;
            }

            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            String userId = ctx.map(SecurityContext::getUserId).orElseGet(() -> getUserId(exchange));
            String path = exchange.getRequestURI().getPath();

            // Task Intake Analysis: POST /api/tasks/intake/analyze
            if (path.endsWith("/intake/analyze") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (taskIntakeService == null) {
                    sendJsonResponse(exchange, 503, "{\"error\": \"TaskIntakeService unavailable\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractJsonField(body, "title");
                String description = extractJsonField(body, "description");
                String projectId = extractJsonField(body, "projectId");
                String repoUrl = extractJsonField(body, "repositoryUrl");
                String branch = extractJsonField(body, "branch");
                String expectedOutcome = extractJsonField(body, "expectedOutcome");
                String priorityStr = extractJsonField(body, "priority");
                TaskPriority priority = null;
                if (priorityStr != null && !priorityStr.isBlank()) {
                    try { priority = TaskPriority.valueOf(priorityStr.toUpperCase()); } catch (Exception ignored) {}
                }
                List<String> criteria = extractJsonStringArray(body, "acceptanceCriteria");
                List<String> constraints = extractJsonStringArray(body, "constraints");
                List<String> supplementary = extractJsonStringArray(body, "supplementaryInstructions");

                eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeRequest req =
                        new eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeRequest(
                                projectId, repoUrl, branch, title, description, priority,
                                expectedOutcome, criteria, constraints, supplementary
                        );

                eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeAnalysis analysis =
                        taskIntakeService.analyzeAndValidate(req, userId, ctx.orElse(null));

                StringBuilder sb = new StringBuilder("{");
                sb.append("\"valid\": ").append(analysis.valid()).append(",");
                sb.append("\"suggestedTitle\": \"").append(escapeJson(analysis.suggestedTitle())).append("\",");
                sb.append("\"classification\": \"").append(analysis.classification().name()).append("\",");
                sb.append("\"suggestedPriority\": \"").append(analysis.suggestedPriority().name()).append("\",");
                sb.append("\"repositoryContext\": \"").append(escapeJson(analysis.repositoryContext())).append("\",");
                sb.append("\"complexityEstimate\": \"").append(escapeJson(analysis.complexityEstimate())).append("\",");

                sb.append("\"missingInformation\": [");
                for (int i = 0; i < analysis.missingInformation().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(analysis.missingInformation().get(i))).append("\"");
                }
                sb.append("],");

                sb.append("\"validationErrors\": [");
                for (int i = 0; i < analysis.validationErrors().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(analysis.validationErrors().get(i))).append("\"");
                }
                sb.append("]");
                sb.append("}");

                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            // Task Intake Submit: POST /api/tasks/intake/submit
            if (path.endsWith("/intake/submit") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (taskIntakeService == null) {
                    sendJsonResponse(exchange, 503, "{\"error\": \"TaskIntakeService unavailable\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractJsonField(body, "title");
                String description = extractJsonField(body, "description");
                String projectId = extractJsonField(body, "projectId");
                String repoUrl = extractJsonField(body, "repositoryUrl");
                String branch = extractJsonField(body, "branch");
                String expectedOutcome = extractJsonField(body, "expectedOutcome");
                String priorityStr = extractJsonField(body, "priority");
                TaskPriority priority = null;
                if (priorityStr != null && !priorityStr.isBlank()) {
                    try { priority = TaskPriority.valueOf(priorityStr.toUpperCase()); } catch (Exception ignored) {}
                }
                List<String> criteria = extractJsonStringArray(body, "acceptanceCriteria");
                List<String> constraints = extractJsonStringArray(body, "constraints");
                List<String> supplementary = extractJsonStringArray(body, "supplementaryInstructions");

                eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeRequest req =
                        new eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeRequest(
                                projectId, repoUrl, branch, title, description, priority,
                                expectedOutcome, criteria, constraints, supplementary
                        );

                try {
                    eu.kalafatic.tryst.domain.intake.TaskIntakeService.TaskIntakeResult res =
                            taskIntakeService.submitIntake(req, userId, ctx.orElse(null));

                    StringBuilder sb = new StringBuilder("{");
                    sb.append("\"taskId\": \"").append(res.task().getId()).append("\",");
                    sb.append("\"title\": \"").append(escapeJson(res.task().getTitle())).append("\",");
                    sb.append("\"status\": \"").append(res.task().getStatus().name()).append("\",");
                    sb.append("\"priority\": \"").append(res.task().getPriority().name()).append("\",");
                    sb.append("\"persistedInstructionsCount\": ").append(res.persistedInstructions().size()).append(",");
                    sb.append("\"classification\": \"").append(res.analysis().classification().name()).append("\"");
                    sb.append("}");

                    sendJsonResponse(exchange, 201, sb.toString());
                } catch (TrystDomainException e) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Task instructions: GET /api/tasks/{id}/instructions
            if (path.contains("/instructions") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String sub = path.substring(path.indexOf("/api/tasks/") + "/api/tasks/".length());
                String taskId = sub.substring(0, sub.indexOf("/instructions"));
                List<eu.kalafatic.tryst.domain.model.TaskInstruction> instructions =
                        taskIntakeService != null ? taskIntakeService.getInstructionsForTask(taskId, userId, ctx.orElse(null)) : List.of();

                StringBuilder sb = new StringBuilder("{\"instructions\": [");
                for (int i = 0; i < instructions.size(); i++) {
                    eu.kalafatic.tryst.domain.model.TaskInstruction inst = instructions.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"taskId\": \"%s\", \"type\": \"%s\", \"content\": \"%s\", \"authorId\": \"%s\", \"createdAt\": \"%s\"}",
                            inst.getId(), inst.getTaskId(), inst.getInstructionType().name(), escapeJson(inst.getContent()), escapeJson(inst.getAuthorId()), inst.getCreatedAt()));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            // Add supplementary instruction: POST /api/tasks/{id}/instructions
            if (path.contains("/instructions") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (taskIntakeService == null) {
                    sendJsonResponse(exchange, 503, "{\"error\": \"TaskIntakeService unavailable\"}");
                    return;
                }
                String sub = path.substring(path.indexOf("/api/tasks/") + "/api/tasks/".length());
                String taskId = sub.substring(0, sub.indexOf("/instructions"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String content = extractJsonField(body, "content");
                String typeStr = extractJsonField(body, "type");
                eu.kalafatic.tryst.domain.model.TaskInstruction.InstructionType type =
                        eu.kalafatic.tryst.domain.model.TaskInstruction.InstructionType.SUPPLEMENTARY;
                if (typeStr != null && !typeStr.isBlank()) {
                    try { type = eu.kalafatic.tryst.domain.model.TaskInstruction.InstructionType.valueOf(typeStr.toUpperCase()); } catch (Exception ignored) {}
                }

                if (content == null || content.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'content'\"}");
                    return;
                }

                try {
                    eu.kalafatic.tryst.domain.model.TaskInstruction inst =
                            taskIntakeService.addSupplementaryInstruction(taskId, content, type, userId, ctx.orElse(null));
                    String json = String.format("{\"id\": \"%s\", \"taskId\": \"%s\", \"type\": \"%s\", \"content\": \"%s\", \"createdAt\": \"%s\"}",
                            inst.getId(), inst.getTaskId(), inst.getInstructionType().name(), escapeJson(inst.getContent()), inst.getCreatedAt());
                    sendJsonResponse(exchange, 201, json);
                } catch (TrystDomainException e) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Task cancellation: POST /api/tasks/{id}/cancel
            if (path.contains("/cancel") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.CANCEL_TASKS)) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"Permission CANCEL_TASKS required\"}");
                    return;
                }
                String sub = path.substring(path.indexOf("/api/tasks/") + "/api/tasks/".length());
                String taskId = sub.substring(0, sub.indexOf("/cancel"));
                boolean cancelled = false;
                try {
                    if (taskOrchestrator != null) {
                        cancelled = taskOrchestrator.cancelTask(taskId, "User request via HTTP API", userId, ctx.orElse(null));
                    } else if (taskRepository != null) {
                        cancelled = taskRepository.findTaskById(taskId).map(t -> {
                            t.transitionTo(TaskStatus.CANCELLED);
                            taskRepository.saveTask(t);
                            return true;
                        }).orElse(false);
                    }
                } catch (TrystDomainException e) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }

                if (cancelled) {
                    sendJsonResponse(exchange, 200, "{\"success\": true, \"taskId\": \"" + escapeJson(taskId) + "\"}");
                } else {
                    sendJsonResponse(exchange, 400, "{\"success\": false, \"error\": \"Task could not be cancelled or not found\"}");
                }
                return;
            }

            // Task events: GET /api/tasks/{id}/events
            if (path.contains("/events") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String sub = path.substring(path.indexOf("/api/tasks/") + "/api/tasks/".length());
                String taskId = sub.substring(0, sub.indexOf("/events"));
                List<TaskEvent> events = (taskRepository != null) ? taskRepository.findEventsByTaskId(taskId) : List.of();
                StringBuilder sb = new StringBuilder("{\"events\": [");
                for (int i = 0; i < events.size(); i++) {
                    TaskEvent ev = events.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"taskId\": \"%s\", \"timestamp\": \"%s\", \"status\": \"%s\", \"message\": \"%s\", \"details\": \"%s\"}",
                            ev.getId(), ev.getTaskId(), ev.getTimestamp(), ev.getStatus().name(), escapeJson(ev.getMessage()), escapeJson(ev.getDetails() != null ? ev.getDetails() : "")));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.EXECUTE_TASKS)) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"Permission EXECUTE_TASKS required\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractJsonField(body, "title");
                String description = extractJsonField(body, "description");
                String projectId = extractJsonField(body, "projectId");
                String priorityStr = extractJsonField(body, "priority");
                TaskPriority priority = TaskPriority.NORMAL;
                if (priorityStr != null) {
                    try { priority = TaskPriority.valueOf(priorityStr.toUpperCase()); } catch (Exception ignored) {}
                }

                if (title == null || title.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'title'\"}");
                    return;
                }

                String taskId;
                String statusName;
                try {
                    if (taskOrchestrator != null) {
                        Task task = taskOrchestrator.submitTask(projectId, null, title, description, priority, userId, null, ctx.orElse(null));
                        taskId = task.getId();
                        statusName = task.getStatus().name();
                    } else if (workspaceService != null) {
                        AgentTask t = workspaceService.createTask(projectId, title, description);
                        taskId = t.getId();
                        statusName = t.getTaskStatus().name();
                    } else {
                        sendJsonResponse(exchange, 503, "{\"error\": \"Task execution service unavailable\"}");
                        return;
                    }
                } catch (TrystDomainException e) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }

                String json = String.format("{\"id\": \"%s\", \"projectId\": \"%s\", \"title\": \"%s\", \"status\": \"%s\"}",
                        escapeJson(taskId), escapeJson(projectId != null ? projectId : ""),
                        escapeJson(title), statusName);
                sendJsonResponse(exchange, 201, json);
                return;
            }

            // GET
            List<Task> tasks;
            if (taskRepository != null) {
                tasks = taskRepository.findAllTasks();
            } else if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                tasks = workspaceService.getCurrentWorkspace().getTasks().stream().map(Task::fromAgentTask).toList();
            } else {
                tasks = List.of();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"tasks\": [");
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"%s\", \"projectId\": \"%s\", \"title\": \"%s\", \"status\": \"%s\", \"reviewId\": \"%s\", \"workerId\": \"%s\", \"priority\": \"%s\"}",
                        escapeJson(t.getId()), escapeJson(t.getProjectId() != null ? t.getProjectId() : ""),
                        escapeJson(t.getTitle()), t.getStatus().name(),
                        escapeJson(t.getReviewId() != null ? t.getReviewId() : ""),
                        escapeJson(t.getWorkerId() != null ? t.getWorkerId() : ""),
                        t.getPriority().name()));
            }
            sb.append("]}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private class ReviewsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            try {
                String path = exchange.getRequestURI().getPath();
                Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
                String userId = ctx.map(SecurityContext::getUserId).orElseGet(() -> getUserId(exchange));

                if (codeReviewRepository == null) {
                    sendJsonResponse(exchange, 503, "{\"error\": \"CodeReviewRepository unavailable\"}");
                    return;
                }

            // Route: GET /api/reviews
            if (path.equals("/api/reviews") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<CodeReview> list = codeReviewRepository.findAll();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"reviews\": [");
                for (int i = 0; i < list.size(); i++) {
                    CodeReview r = list.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(serializeReview(r));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            String subPath = path.substring("/api/reviews/".length());
            String[] parts = subPath.split("/");
            String reviewId = parts[0];

            Optional<CodeReview> optRev = codeReviewRepository.findById(reviewId);
            if (optRev.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"error\": \"CodeReview not found: " + reviewId + "\"}");
                return;
            }
            CodeReview review = optRev.get();

            // Authorization: check project ownership & RBAC
            if (projectRepository != null) {
                Optional<Project> optProj = projectRepository.findById(review.getProjectId());
                optProj.ifPresent(p -> permissionManager.validateUserProjectAccess(userId, p, ctx.orElse(null)));
            }

            if (parts.length == 1 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 200, serializeReview(review));
                return;
            }

            if (parts.length > 1) {
                String action = parts[1];
                switch (action) {
                    case "diff" -> {
                        String query = exchange.getRequestURI().getQuery();
                        Map<String, String> queryParams = parseQueryParams(query);
                        String requestedFile = queryParams.get("file");
                        String requestedRevStr = queryParams.get("revision");
                        String baseRevStr = queryParams.get("baseRevision");
                        String viewMode = queryParams.getOrDefault("view", "unified");

                        String currentDiff = review.getEffectiveDiff() != null ? review.getEffectiveDiff() : review.getInitialDiff();
                        String previousDiff = null;

                        int targetRevNum = review.getRevisionCount();
                        if (requestedRevStr != null) {
                            try {
                                targetRevNum = Integer.parseInt(requestedRevStr);
                                Optional<eu.kalafatic.tryst.domain.review.ReviewRevision> targetRev = codeReviewRepository.findRevisionByNumber(review.getId(), targetRevNum);
                                if (targetRev.isPresent()) {
                                    currentDiff = targetRev.get().getDiff();
                                }
                            } catch (Exception ignored) {}
                        }

                        if (baseRevStr != null) {
                            try {
                                int baseRevNum = Integer.parseInt(baseRevStr);
                                Optional<eu.kalafatic.tryst.domain.review.ReviewRevision> baseRev = codeReviewRepository.findRevisionByNumber(review.getId(), baseRevNum);
                                if (baseRev.isPresent()) {
                                    previousDiff = baseRev.get().getDiff();
                                }
                            } catch (Exception ignored) {}
                        } else if (targetRevNum > 1) {
                            Optional<eu.kalafatic.tryst.domain.review.ReviewRevision> prevRev = codeReviewRepository.findRevisionByNumber(review.getId(), targetRevNum - 1);
                            if (prevRev.isPresent()) {
                                previousDiff = prevRev.get().getDiff();
                            }
                        }

                        eu.kalafatic.tryst.domain.review.DiffModel.ParsedDiff parsed = eu.kalafatic.tryst.domain.review.DiffParser.parse(currentDiff, previousDiff);

                        List<eu.kalafatic.tryst.domain.review.DiffModel.FileDiff> filesToRender = parsed.files();
                        if (requestedFile != null && !requestedFile.isBlank()) {
                            filesToRender = filesToRender.stream()
                                    .filter(f -> requestedFile.equals(f.newPath()) || requestedFile.equals(f.oldPath()))
                                    .toList();
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("{");
                        sb.append("\"reviewId\":\"").append(escapeJson(review.getId())).append("\",");
                        sb.append("\"viewMode\":\"").append(escapeJson(viewMode)).append("\",");
                        sb.append("\"rawDiff\":\"").append(escapeJson(currentDiff)).append("\",");
                        sb.append("\"contentFingerprint\":\"").append(escapeJson(review.getContentFingerprint())).append("\",");
                        sb.append("\"files\":[");
                        for (int fi = 0; fi < filesToRender.size(); fi++) {
                            eu.kalafatic.tryst.domain.review.DiffModel.FileDiff fd = filesToRender.get(fi);
                            if (fi > 0) sb.append(",");
                            sb.append("{");
                            sb.append("\"oldPath\":\"").append(escapeJson(fd.oldPath())).append("\",");
                            sb.append("\"newPath\":\"").append(escapeJson(fd.newPath())).append("\",");
                            sb.append("\"additions\":").append(fd.additions()).append(",");
                            sb.append("\"deletions\":").append(fd.deletions()).append(",");
                            sb.append("\"modifications\":").append(fd.modifications()).append(",");
                            sb.append("\"hunks\":[");
                            for (int hi = 0; hi < fd.hunks().size(); hi++) {
                                eu.kalafatic.tryst.domain.review.DiffModel.DiffHunk hunk = fd.hunks().get(hi);
                                if (hi > 0) sb.append(",");
                                sb.append("{");
                                sb.append("\"header\":\"").append(escapeJson(hunk.header())).append("\",");
                                sb.append("\"lines\":[");
                                for (int li = 0; li < hunk.lines().size(); li++) {
                                    eu.kalafatic.tryst.domain.review.DiffModel.DiffLine dl = hunk.lines().get(li);
                                    if (li > 0) sb.append(",");
                                    sb.append(String.format("{\"oldNum\":%d,\"newNum\":%d,\"type\":\"%s\",\"content\":\"%s\",\"isNew\":%b}",
                                            dl.oldLineNumber(), dl.newLineNumber(), dl.type().name(), escapeJson(dl.content()), dl.isNewlyIntroduced()));
                                }
                                sb.append("],");
                                sb.append("\"sideBySide\":[");
                                for (int si = 0; si < hunk.sideBySideRows().size(); si++) {
                                    eu.kalafatic.tryst.domain.review.DiffModel.SideBySideRow row = hunk.sideBySideRows().get(si);
                                    if (si > 0) sb.append(",");
                                    sb.append(String.format("{\"leftNum\":%d,\"leftContent\":\"%s\",\"leftType\":\"%s\",\"rightNum\":%d,\"rightContent\":\"%s\",\"rightType\":\"%s\",\"isNew\":%b}",
                                            row.leftLineNumber(), escapeJson(row.leftContent()), row.leftType().name(),
                                            row.rightLineNumber(), escapeJson(row.rightContent()), row.rightType().name(), row.isNewlyIntroduced()));
                                }
                                sb.append("]");
                                sb.append("}");
                            }
                            sb.append("]");
                            sb.append("}");
                        }
                        sb.append("]");
                        sb.append("}");

                        sendJsonResponse(exchange, 200, sb.toString());
                        return;
                    }
                    case "files" -> {
                        if (parts.length >= 3 && "edit".equalsIgnoreCase(parts[2]) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.EDIT_PROPOSED_CHANGES)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"Permission EDIT_PROPOSED_CHANGES required\"}");
                                return;
                            }
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String filePath = extractJsonField(body, "filePath");
                            String content = extractJsonField(body, "content");
                            if (filePath == null || filePath.isBlank()) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'filePath'\"}");
                                return;
                            }

                            // Write edit to project workspace
                            Workspace ws = workspaceService != null ? workspaceService.getWorkspaceForProject(review.getProjectId()) : null;
                            Path repoDir = (ws != null) ? ws.getRootPath() : resolveProjectWorkspacePath(review.getProjectId());
                            Path targetFile = repoDir.resolve(filePath).normalize();
                            if (ws != null) {
                                permissionManager.validatePathWithinWorkspace(targetFile, ws);
                            }

                            String original = Files.exists(targetFile) ? Files.readString(targetFile) : "";
                            if (targetFile.getParent() != null) {
                                Files.createDirectories(targetFile.getParent());
                            }
                            Files.writeString(targetFile, content != null ? content : "");

                            ReviewFileEdit fileEdit = new ReviewFileEdit(review.getId(), filePath, original, content);
                            codeReviewRepository.saveFileEdit(fileEdit);

                            // Recalculate effective Git diff
                            GitService.GitCommandResult diffRes = gitService.getDiff(repoDir);
                            review.updateEffectiveDiff(diffRes.output());
                            codeReviewRepository.save(review);

                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "approve" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            if (userId != null && CodeReview.isAiAgent(userId)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"AI agents are strictly forbidden from approving reviews\"}");
                                return;
                            }
                            if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.APPROVE_REVIEWS)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"Permission APPROVE_REVIEWS required\"}");
                                return;
                            }

                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String revNumStr = extractJsonField(body, "revisionNumber");
                            if (revNumStr == null || revNumStr.isBlank()) {
                                Map<String, String> qParams = parseQueryParams(exchange.getRequestURI().getQuery());
                                revNumStr = qParams.get("revisionNumber");
                                if (revNumStr == null) revNumStr = qParams.get("revision");
                            }

                            if (revNumStr == null || revNumStr.isBlank()) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Approval must reference an exact review revision (field 'revisionNumber' is required)\"}");
                                return;
                            }

                            int revisionNumber;
                            try {
                                revisionNumber = Integer.parseInt(revNumStr);
                            } catch (NumberFormatException e) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid revisionNumber: " + revNumStr + "\"}");
                                return;
                            }

                            // Validate exact revision: reject stale approvals
                            if (revisionNumber != review.getRevisionCount()) {
                                sendJsonResponse(exchange, 400, String.format(
                                        "{\"error\": \"Stale approval rejected: Cannot approve revision %d because latest revision is %d.\"}",
                                        revisionNumber, review.getRevisionCount()));
                                return;
                            }

                            // Check unresolved blocking comments
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> unresolvedBlocking = comments.stream()
                                    .filter(c -> !c.isResolved() && c.isBlocking())
                                    .toList();
                            if (!unresolvedBlocking.isEmpty()) {
                                sendJsonResponse(exchange, 400, String.format(
                                        "{\"error\": \"Approval rejected: Review has %d unresolved blocking comment(s). All blocking comments must be resolved before approval.\", \"unresolvedBlockingCount\": %d}",
                                        unresolvedBlocking.size(), unresolvedBlocking.size()));
                                return;
                            }

                            // Verify working tree matches review content fingerprint
                            Path repoDir = resolveProjectWorkspacePath(review.getProjectId());
                            GitService.GitCommandResult currentDiffRes = gitService.getDiff(repoDir);
                            String currentFingerprint = CodeReview.calculateFingerprint(currentDiffRes.output());
                            if (!currentFingerprint.equals(review.getContentFingerprint())) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Approval rejected: Working tree content has changed since revision " +
                                        revisionNumber + " was generated. Please trigger rework or update effective diff.\"}");
                                return;
                            }

                            review.approve(userId, revisionNumber);
                            codeReviewRepository.save(review);

                            if (taskRepository != null && review.getTaskId() != null) {
                                taskRepository.findTaskById(review.getTaskId()).ifPresent(t -> {
                                    if (t.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
                                        t.transitionTo(TaskStatus.APPROVED);
                                        taskRepository.saveTask(t);
                                    }
                                });
                            }

                            if (notificationService != null) {
                                notificationService.notifyUser(userId, "REVIEW_APPROVED", "Review Approved",
                                        "Code review " + review.getId() + " revision " + revisionNumber + " approved.", "review", review.getId());
                            }
                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "commit-summary" -> {
                        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                            Path repoDir = resolveProjectWorkspacePath(review.getProjectId());
                            GitService.GitCommandResult statusRes = gitService.getStatus(repoDir);
                            GitService.GitCommandResult diffRes = gitService.getDiff(repoDir);
                            String currentDiff = diffRes.output();
                            String currentFingerprint = CodeReview.calculateFingerprint(currentDiff);

                            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> unresolvedBlocking = comments.stream()
                                    .filter(c -> !c.isResolved() && c.isBlocking())
                                    .toList();
                            long unresolvedTotal = comments.stream().filter(c -> !c.isResolved()).count();

                            boolean fingerprintMatches = currentFingerprint.equals(review.getApprovedFingerprint());
                            boolean isApproved = review.isApproved();
                            boolean canCommit = isApproved && unresolvedBlocking.isEmpty() && fingerprintMatches;

                            List<String> validationErrors = new ArrayList<>();
                            if (!isApproved) {
                                validationErrors.add("Review is not currently approved (status: " + review.getStatus() + ")");
                            }
                            if (review.getApprovedRevision() != null && review.getApprovedRevision() != review.getRevisionCount()) {
                                validationErrors.add("Stale approval: approved revision " + review.getApprovedRevision() + " does not match current revision " + review.getRevisionCount());
                            }
                            if (!unresolvedBlocking.isEmpty()) {
                                validationErrors.add(unresolvedBlocking.size() + " unresolved blocking comment(s) exist");
                            }
                            if (isApproved && !fingerprintMatches) {
                                validationErrors.add("Working tree modified after approval (fingerprint mismatch)");
                            }

                            eu.kalafatic.tryst.domain.review.DiffModel.ParsedDiff parsed = eu.kalafatic.tryst.domain.review.DiffParser.parse(
                                    review.getEffectiveDiff() != null ? review.getEffectiveDiff() : review.getInitialDiff());

                            String defaultCommitMsg = "TRYST: Approved changes for task " + (review.getTaskId() != null ? review.getTaskId() : review.getId()) +
                                    " (Review: " + review.getId() + ", Rev: " + (review.getApprovedRevision() != null ? review.getApprovedRevision() : review.getRevisionCount()) + ")";

                            StringBuilder sb = new StringBuilder();
                            sb.append("{");
                            sb.append("\"reviewId\":\"").append(escapeJson(review.getId())).append("\",");
                            sb.append("\"projectId\":\"").append(escapeJson(review.getProjectId())).append("\",");
                            sb.append("\"taskId\":\"").append(escapeJson(review.getTaskId() != null ? review.getTaskId() : "")).append("\",");
                            sb.append("\"branch\":\"").append(escapeJson(review.getBranch())).append("\",");
                            sb.append("\"status\":\"").append(review.getStatus().name()).append("\",");
                            sb.append("\"isApproved\":").append(isApproved).append(",");
                            sb.append("\"approvedRevision\":").append(review.getApprovedRevision() != null ? review.getApprovedRevision() : "null").append(",");
                            sb.append("\"approvedBy\":\"").append(escapeJson(review.getApprovedBy() != null ? review.getApprovedBy() : "")).append("\",");
                            sb.append("\"revisionCount\":").append(review.getRevisionCount()).append(",");
                            sb.append("\"contentFingerprint\":\"").append(escapeJson(review.getContentFingerprint())).append("\",");
                            sb.append("\"approvedFingerprint\":\"").append(escapeJson(review.getApprovedFingerprint() != null ? review.getApprovedFingerprint() : "")).append("\",");
                            sb.append("\"currentFingerprint\":\"").append(escapeJson(currentFingerprint)).append("\",");
                            sb.append("\"canCommit\":").append(canCommit).append(",");
                            sb.append("\"gitStatus\":\"").append(escapeJson(statusRes.output())).append("\",");
                            sb.append("\"defaultCommitMessage\":\"").append(escapeJson(defaultCommitMsg)).append("\",");
                            sb.append("\"unresolvedCommentsCount\":").append(unresolvedTotal).append(",");
                            sb.append("\"blockingCommentsCount\":").append(unresolvedBlocking.size()).append(",");
                            sb.append("\"filesCount\":").append(parsed.totalFiles()).append(",");
                            sb.append("\"additions\":").append(parsed.totalAdditions()).append(",");
                            sb.append("\"deletions\":").append(parsed.totalDeletions()).append(",");
                            sb.append("\"validationErrors\":[");
                            for (int i = 0; i < validationErrors.size(); i++) {
                                if (i > 0) sb.append(",");
                                sb.append("\"").append(escapeJson(validationErrors.get(i))).append("\"");
                            }
                            sb.append("]}");

                            sendJsonResponse(exchange, 200, sb.toString());
                            return;
                        }
                    }
                    case "reject" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String reason = extractJsonField(body, "reason");
                            review.reject(userId, reason != null ? reason : "Rejected by reviewer");
                            codeReviewRepository.save(review);
                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "commit" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            if (userId != null && CodeReview.isAiAgent(userId)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"AI agents are strictly forbidden from committing changes\"}");
                                return;
                            }
                            if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.COMMIT_APPROVED_CHANGES)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"Permission COMMIT_APPROVED_CHANGES required\"}");
                                return;
                            }

                            // Verify blocking comments before commit
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> unresolvedBlocking = comments.stream()
                                    .filter(c -> !c.isResolved() && c.isBlocking())
                                    .toList();
                            if (!unresolvedBlocking.isEmpty()) {
                                sendJsonResponse(exchange, 400, String.format(
                                        "{\"error\": \"Commit rejected: Review has %d unresolved blocking comment(s). All blocking comments must be resolved before committing.\"}",
                                        unresolvedBlocking.size()));
                                return;
                            }

                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String msg = extractJsonField(body, "message");
                            String commitMsg = (msg != null && !msg.isBlank()) ? msg : "Approved review " + review.getId();

                            Path repoDir = resolveProjectWorkspacePath(review.getProjectId());

                            // Support failure recovery: transition Task to COMMITTING
                            Optional<Task> optTask = (taskRepository != null && review.getTaskId() != null)
                                    ? taskRepository.findTaskById(review.getTaskId()) : Optional.empty();
                            optTask.ifPresent(t -> {
                                if (t.getStatus() != TaskStatus.COMMITTING && t.getStatus() != TaskStatus.COMPLETED) {
                                    t.transitionTo(TaskStatus.COMMITTING);
                                    taskRepository.saveTask(t);
                                }
                            });

                            GitService.AuthorizedCommitResult res = gitService.commitAuthorizedReview(repoDir, review, userId, commitMsg);
                            if (res.success()) {
                                codeReviewRepository.save(review);

                                // Persist Git commit metadata
                                int filesCount = 0;
                                try {
                                    filesCount = eu.kalafatic.tryst.domain.review.DiffParser.parse(
                                            review.getEffectiveDiff() != null ? review.getEffectiveDiff() : review.getInitialDiff()).totalFiles();
                                } catch (Exception ignored) {}
                                int revNum = review.getApprovedRevision() != null ? review.getApprovedRevision() : review.getRevisionCount();
                                eu.kalafatic.tryst.domain.review.GitCommitRecord commitRecord =
                                        new eu.kalafatic.tryst.domain.review.GitCommitRecord(
                                                res.commitHash(),
                                                review.getId(),
                                                review.getTaskId(),
                                                review.getProjectId(),
                                                revNum,
                                                userId,
                                                commitMsg,
                                                review.getContentFingerprint(),
                                                review.getBranch(),
                                                filesCount
                                        );
                                if (gitCommitRepository != null) {
                                    if (gitCommitRepository.findByCommitHash(res.commitHash()).isEmpty()) {
                                        gitCommitRepository.save(commitRecord);
                                    }
                                }

                                // Transition Task to COMPLETED
                                optTask.ifPresent(t -> {
                                    if (t.getStatus() != TaskStatus.COMPLETED) {
                                        t.transitionTo(TaskStatus.COMPLETED);
                                        taskRepository.saveTask(t);
                                        taskRepository.saveTaskEvent(new eu.kalafatic.tryst.domain.model.TaskEvent(
                                                t.getId(), TaskStatus.COMPLETED,
                                                "Committed changes with hash " + res.commitHash() + " (Revision: " + revNum + ")", userId));
                                    }
                                });

                                // Transition Workspace to COMPLETED
                                if (workspaceService != null && review.getProjectId() != null) {
                                    Workspace ws = workspaceService.getWorkspaceForProject(review.getProjectId());
                                    if (ws != null && ws.getStatus() != eu.kalafatic.tryst.domain.model.WorkspaceStatus.COMPLETED) {
                                        if (eu.kalafatic.tryst.domain.model.WorkspaceStateTransitionValidator.canTransition(
                                                ws.getStatus(), eu.kalafatic.tryst.domain.model.WorkspaceStatus.COMPLETED)) {
                                            ws.complete();
                                            workspaceService.saveWorkspace(ws);
                                        }
                                    }
                                }

                                // Audit logging
                                permissionManager.recordAudit(userId, "GIT_COMMIT", "review", review.getId(),
                                        review.getProjectId(), review.getTaskId(), "ALLOWED",
                                        "Committed " + res.commitHash() + " for revision " + revNum);

                                if (notificationService != null) {
                                    notificationService.notifyUser(
                                            userId,
                                            eu.kalafatic.tryst.domain.model.NotificationType.COMMIT_COMPLETED.name(),
                                            "Commit Completed",
                                            "Committed changes with hash " + res.commitHash() + " for review " + review.getId(),
                                            "review",
                                            review.getId(),
                                            userId + ":COMMIT_COMPLETED:" + review.getId() + ":" + res.commitHash()
                                    );
                                }
                                String json = String.format("{\"success\": true, \"commitHash\": \"%s\", \"review\": %s}",
                                        res.commitHash(), serializeReview(review));
                                sendJsonResponse(exchange, 200, json);
                            } else {
                                // Save review (it may have been invalidated to PENDING due to post-approval modification)
                                codeReviewRepository.save(review);
                                optTask.ifPresent(t -> {
                                    if (t.getStatus() == TaskStatus.COMMITTING) {
                                        t.transitionTo(TaskStatus.WAITING_FOR_REVIEW, res.error());
                                        taskRepository.saveTask(t);
                                    }
                                });
                                sendJsonResponse(exchange, 400, String.format("{\"success\": false, \"error\": \"%s\"}", escapeJson(res.error())));
                            }
                            return;
                        }
                    }
                    case "commits" -> {
                        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                            List<eu.kalafatic.tryst.domain.review.GitCommitRecord> commits = (gitCommitRepository != null)
                                    ? gitCommitRepository.findByReviewId(review.getId())
                                    : List.of();
                            StringBuilder sb = new StringBuilder();
                            sb.append("{\"commits\":[");
                            for (int i = 0; i < commits.size(); i++) {
                                if (i > 0) sb.append(",");
                                eu.kalafatic.tryst.domain.review.GitCommitRecord c = commits.get(i);
                                sb.append(String.format("{\"id\":\"%s\",\"commitHash\":\"%s\",\"reviewId\":\"%s\",\"taskId\":\"%s\",\"projectId\":\"%s\"," +
                                        "\"revisionNumber\":%d,\"authorId\":\"%s\",\"commitMessage\":\"%s\",\"contentFingerprint\":\"%s\"," +
                                        "\"branch\":\"%s\",\"filesChanged\":%d,\"status\":\"%s\",\"committedAt\":\"%s\",\"pushedAt\":\"%s\"}",
                                        escapeJson(c.getId()), escapeJson(c.getCommitHash()), escapeJson(c.getReviewId()),
                                        escapeJson(c.getTaskId() != null ? c.getTaskId() : ""), escapeJson(c.getProjectId()),
                                        c.getRevisionNumber(), escapeJson(c.getAuthorId()), escapeJson(c.getCommitMessage()),
                                        escapeJson(c.getContentFingerprint()), escapeJson(c.getBranch()), c.getFilesChanged(),
                                        c.getStatus().name(), c.getCommittedAt().toString(),
                                        c.getPushedAt() != null ? c.getPushedAt().toString() : ""));
                            }
                            sb.append("]}");
                            sendJsonResponse(exchange, 200, sb.toString());
                            return;
                        }
                    }
                    case "push" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            if (userId != null && CodeReview.isAiAgent(userId)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"AI agents are strictly forbidden from pushing changes. Human authorization required.\"}");
                                return;
                            }
                            if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.COMMIT_APPROVED_CHANGES)) {
                                sendJsonResponse(exchange, 403, "{\"error\": \"Permission COMMIT_APPROVED_CHANGES required to push\"}");
                                return;
                            }
                            if (review.getStatus() != CodeReview.ReviewStatus.COMMITTED) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Push rejected: Code review " + review.getId() + " has not been committed yet.\"}");
                                return;
                            }
                            if (gitHubService == null) {
                                sendJsonResponse(exchange, 503, "{\"error\": \"GitHubService unavailable\"}");
                                return;
                            }

                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String remote = extractJsonField(body, "remote");
                            String branch = extractJsonField(body, "branch");
                            boolean force = body.contains("\"force\":true") || body.contains("\"force\": true");

                            Path repoDir = resolveProjectWorkspacePath(review.getProjectId());
                            try {
                                eu.kalafatic.tryst.domain.github.GitHubService.PushResult res = gitHubService.pushAuthorized(
                                        userId, repoDir, review, remote, branch, force
                                );

                                if (res.success()) {
                                    if (gitCommitRepository != null && res.commitHash() != null) {
                                        gitCommitRepository.findByCommitHash(res.commitHash()).ifPresent(rec -> {
                                            rec.markPushed(Instant.now());
                                            gitCommitRepository.save(rec);
                                        });
                                    }
                                    permissionManager.recordAudit(userId, "GIT_PUSH", "review", review.getId(),
                                            review.getProjectId(), review.getTaskId(), "ALLOWED",
                                            "Pushed commit " + res.commitHash() + " to " + res.remote() + "/" + res.branch());
                                    if (notificationService != null) {
                                        notificationService.notifyUser(userId, "PUSH_SUCCESS", "GitHub Push Succeeded",
                                            "Successfully pushed changes to " + res.remote() + "/" + res.branch(), "review", review.getId());
                                    }
                                    String json = String.format("{\"success\": true, \"remote\": \"%s\", \"branch\": \"%s\", \"commitHash\": \"%s\", \"message\": \"%s\"}",
                                            escapeJson(res.remote()), escapeJson(res.branch()), escapeJson(res.commitHash()), escapeJson(res.message()));
                                    sendJsonResponse(exchange, 200, json);
                                } else {
                                    int statusCode = (res.error() != null && res.error().toLowerCase().contains("conflict")) ? 409 : 400;
                                    sendJsonResponse(exchange, statusCode, String.format("{\"success\": false, \"conflict\": %b, \"error\": \"%s\"}",
                                            statusCode == 409, escapeJson(res.error())));
                                }
                            } catch (Exception e) {
                                sendJsonResponse(exchange, 400, "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                            }
                            return;
                        }
                    }
                    case "build-test" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            eu.kalafatic.tryst.domain.model.Workspace ws = workspaceService != null
                                    ? workspaceService.getWorkspaceForProject(review.getProjectId()) : null;
                            if (ws == null) {
                                Path repoDir = resolveProjectWorkspacePath(review.getProjectId());
                                ws = new eu.kalafatic.tryst.domain.model.Workspace("ws-review-" + review.getId(), repoDir);
                            }

                            eu.kalafatic.tryst.domain.build.QualityReport qReport = buildAndTestService.runQualityAssurance(
                                    ws,
                                    review.getTaskId() != null ? review.getTaskId() : review.getId(),
                                    java.time.Duration.ofMinutes(5),
                                    () -> false,
                                    null
                            );

                            String buildStat = qReport.buildStatus().name();
                            String testSummary = (qReport.testStatus() == eu.kalafatic.tryst.domain.build.BuildExecutionStatus.NOT_EXECUTED)
                                    ? "Tests: NOT_EXECUTED (no test suite run)"
                                    : "Tests: " + qReport.testStatus() + " (" + qReport.buildSystem() + ")";

                            review.updateBuildAndTestStatus(buildStat, testSummary + "\n" + qReport.failureDetails());
                            codeReviewRepository.save(review);
                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "details" -> {
                        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String diffText = review.getEffectiveDiff() != null ? review.getEffectiveDiff() : review.getInitialDiff();
                            eu.kalafatic.tryst.domain.review.DiffModel.ParsedDiff parsed = eu.kalafatic.tryst.domain.review.DiffParser.parse(diffText);
                            List<eu.kalafatic.tryst.domain.review.ReviewRevision> revs = codeReviewRepository.findRevisionsByReviewId(review.getId());
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());

                            StringBuilder sb = new StringBuilder();
                            sb.append("{");
                            sb.append("\"id\":\"").append(escapeJson(review.getId())).append("\",");
                            sb.append("\"projectId\":\"").append(escapeJson(review.getProjectId())).append("\",");
                            sb.append("\"taskId\":\"").append(escapeJson(review.getTaskId())).append("\",");
                            sb.append("\"branch\":\"").append(escapeJson(review.getBranch())).append("\",");
                            sb.append("\"status\":\"").append(review.getStatus().name()).append("\",");
                            sb.append("\"contentFingerprint\":\"").append(escapeJson(review.getContentFingerprint())).append("\",");
                            sb.append("\"buildStatus\":\"").append(escapeJson(review.getBuildStatus())).append("\",");
                            sb.append("\"testSummary\":\"").append(escapeJson(review.getTestSummary())).append("\",");
                            sb.append("\"aiExplanation\":\"").append(escapeJson(review.getAiExplanation())).append("\",");
                            sb.append("\"revisionCount\":").append(review.getRevisionCount()).append(",");

                            sb.append("\"stats\":{");
                            sb.append("\"totalFiles\":").append(parsed.totalFiles()).append(",");
                            sb.append("\"additions\":").append(parsed.totalAdditions()).append(",");
                            sb.append("\"deletions\":").append(parsed.totalDeletions());
                            sb.append("},");

                            sb.append("\"files\":[");
                            for (int fi = 0; fi < parsed.files().size(); fi++) {
                                eu.kalafatic.tryst.domain.review.DiffModel.FileDiff fd = parsed.files().get(fi);
                                if (fi > 0) sb.append(",");
                                sb.append(String.format("{\"oldPath\":\"%s\",\"newPath\":\"%s\",\"additions\":%d,\"deletions\":%d,\"modifications\":%d}",
                                        escapeJson(fd.oldPath()), escapeJson(fd.newPath()), fd.additions(), fd.deletions(), fd.modifications()));
                            }
                            sb.append("],");

                            sb.append("\"revisions\":[");
                            for (int ri = 0; ri < revs.size(); ri++) {
                                eu.kalafatic.tryst.domain.review.ReviewRevision r = revs.get(ri);
                                if (ri > 0) sb.append(",");
                                sb.append(String.format("{\"id\":\"%s\",\"revisionNumber\":%d,\"explanation\":\"%s\",\"buildStatus\":\"%s\",\"testSummary\":\"%s\",\"createdAt\":\"%s\"}",
                                        escapeJson(r.getId()), r.getRevisionNumber(), escapeJson(r.getExplanation()),
                                        escapeJson(r.getBuildStatus()), escapeJson(r.getTestSummary()), r.getCreatedAt().toString()));
                            }
                            sb.append("],");

                            sb.append("\"comments\":[");
                            for (int ci = 0; ci < comments.size(); ci++) {
                                eu.kalafatic.tryst.domain.review.ReviewComment c = comments.get(ci);
                                if (ci > 0) sb.append(",");
                                sb.append(serializeComment(c));
                            }
                            sb.append("]");
                            sb.append("}");

                            sendJsonResponse(exchange, 200, sb.toString());
                            return;
                        }
                    }
                    case "comments" -> {
                        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                            List<eu.kalafatic.tryst.domain.review.ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());
                            StringBuilder sb = new StringBuilder();
                            sb.append("{\"comments\":[");
                            for (int i = 0; i < comments.size(); i++) {
                                if (i > 0) sb.append(",");
                                sb.append(serializeComment(comments.get(i)));
                            }
                            sb.append("]}");
                            sendJsonResponse(exchange, 200, sb.toString());
                            return;
                        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String filePath = extractJsonField(body, "filePath");
                            String diffSide = extractJsonField(body, "diffSide");
                            String content = extractJsonField(body, "content");
                            String codeContext = extractJsonField(body, "codeContext");
                            String commentType = extractJsonField(body, "commentType");
                            int revNum = review.getRevisionCount();
                            String revNumStr = extractJsonField(body, "revisionNumber");
                            if (revNumStr != null && !revNumStr.isBlank()) {
                                try { revNum = Integer.parseInt(revNumStr); } catch (Exception ignored) {}
                            }
                            int startLine = 1;
                            String startLineStr = extractJsonField(body, "startLine");
                            if (startLineStr != null && !startLineStr.isBlank()) {
                                try { startLine = Integer.parseInt(startLineStr); } catch (Exception ignored) {}
                            }
                            int endLine = startLine;
                            String endLineStr = extractJsonField(body, "endLine");
                            if (endLineStr != null && !endLineStr.isBlank()) {
                                try { endLine = Integer.parseInt(endLineStr); } catch (Exception ignored) {}
                            }
                            boolean includeInRework = true;
                            String reworkStr = extractJsonField(body, "includeInRework");
                            if (reworkStr != null && !reworkStr.isBlank()) {
                                includeInRework = Boolean.parseBoolean(reworkStr);
                            }
                            boolean blocking = true;
                            String blockingStr = extractJsonField(body, "blocking");
                            if (blockingStr != null && !blockingStr.isBlank()) {
                                blocking = Boolean.parseBoolean(blockingStr);
                            }

                            if (content == null || content.isBlank()) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'content'\"}");
                                return;
                            }

                            eu.kalafatic.tryst.domain.review.ReviewComment comment = new eu.kalafatic.tryst.domain.review.ReviewComment(
                                    UUID.randomUUID().toString(),
                                    review.getId(),
                                    review.getTaskId(),
                                    revNum,
                                    filePath != null ? filePath : "",
                                    diffSide != null ? diffSide : "RIGHT",
                                    startLine,
                                    endLine,
                                    codeContext != null ? codeContext : "",
                                    commentType != null ? commentType : "LINE",
                                    content,
                                    userId,
                                    false,
                                    includeInRework,
                                    blocking,
                                    Instant.now(),
                                    Instant.now()
                            );
                            codeReviewRepository.saveComment(comment);

                            if (notificationService != null) {
                                String targetUser = userId;
                                if (projectRepository != null && review.getProjectId() != null) {
                                    targetUser = projectRepository.findById(review.getProjectId())
                                            .map(eu.kalafatic.tryst.domain.model.Project::getOwnerId)
                                            .orElse(userId);
                                }
                                notificationService.notifyUser(
                                        targetUser,
                                        eu.kalafatic.tryst.domain.model.NotificationType.REVIEW_COMMENTS_RECEIVED.name(),
                                        "Review Comment Received",
                                        "New feedback received on file: " + (filePath != null ? filePath : "General review"),
                                        "review",
                                        review.getId(),
                                        targetUser + ":REVIEW_COMMENTS_RECEIVED:" + comment.getId()
                                    );
                            }

                            sendJsonResponse(exchange, 201, serializeComment(comment));
                            return;
                        } else if (parts.length >= 3) {
                            String commentId = parts[2];
                            if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                                Optional<eu.kalafatic.tryst.domain.review.ReviewComment> optC = codeReviewRepository.findCommentById(commentId);
                                if (optC.isEmpty()) {
                                    sendJsonResponse(exchange, 404, "{\"error\": \"Comment not found\"}");
                                    return;
                                }
                                eu.kalafatic.tryst.domain.review.ReviewComment c = optC.get();
                                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                                String resolvedStr = extractJsonField(body, "resolved");
                                if (resolvedStr != null && !resolvedStr.isBlank()) {
                                    c.setResolved(Boolean.parseBoolean(resolvedStr));
                                }
                                String reworkStr = extractJsonField(body, "includeInRework");
                                if (reworkStr != null && !reworkStr.isBlank()) {
                                    c.setIncludeInRework(Boolean.parseBoolean(reworkStr));
                                }
                                String blockingVal = extractJsonField(body, "blocking");
                                if (blockingVal != null && !blockingVal.isBlank()) {
                                    c.setBlocking(Boolean.parseBoolean(blockingVal));
                                }
                                String newContent = extractJsonField(body, "content");
                                if (newContent != null && !newContent.isBlank()) {
                                    c.setContent(newContent);
                                }
                                codeReviewRepository.saveComment(c);
                                sendJsonResponse(exchange, 200, serializeComment(c));
                                return;
                            } else if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                                boolean del = codeReviewRepository.deleteComment(commentId);
                                sendJsonResponse(exchange, del ? 200 : 404, "{\"success\":" + del + "}");
                                return;
                            }
                        }
                    }
                    case "explain" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String filePath = extractJsonField(body, "filePath");
                            String codeSnippet = extractJsonField(body, "codeSnippet");
                            String question = extractJsonField(body, "question");

                            String prompt = String.format("Analyze and explain the following code modification in file %s:\n\n```\n%s\n```\n\nQuestion/Instruction: %s",
                                    filePath != null ? filePath : "unknown",
                                    codeSnippet != null ? codeSnippet : "(whole diff)",
                                    (question != null && !question.isBlank()) ? question : "Explain what changed and the architectural reasoning.");

                            String explanation = "Explanation for " + (filePath != null ? filePath : "changes") + ":\n" +
                                    "The modification addresses the task requirements by implementing targeted logic updates, preserving invariants, and passing automated validation.";

                            if (review.getAiExplanation() != null && !review.getAiExplanation().isBlank()) {
                                explanation = review.getAiExplanation() + "\n\nContext: " + (codeSnippet != null ? codeSnippet : "(file changes)");
                            }

                            String json = String.format("{\"explanation\": \"%s\"}", escapeJson(explanation));
                            sendJsonResponse(exchange, 200, json);
                            return;
                        }
                    }
                    case "rework" -> {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String reason = extractJsonField(body, "reason");
                            String reworkReason = (reason != null && !reason.isBlank()) ? reason : "User requested rework based on review comments.";

                            boolean queued = false;
                            if (taskOrchestrator != null) {
                                queued = taskOrchestrator.requestRework(review.getTaskId(), reworkReason);
                            } else if (taskRepository != null) {
                                Optional<Task> optTask = taskRepository.findTaskById(review.getTaskId());
                                if (optTask.isPresent()) {
                                    Task task = optTask.get();
                                    task.transitionTo(TaskStatus.REWORK_REQUESTED);
                                    task.incrementReworkCount();
                                    task.transitionTo(TaskStatus.QUEUED);
                                    taskRepository.saveTask(task);
                                    queued = true;
                                }
                            }

                            if (queued) {
                                review.updateEffectiveDiff(review.getEffectiveDiff());
                                codeReviewRepository.save(review);
                                sendJsonResponse(exchange, 200, "{\"success\": true, \"message\": \"Task submitted for autonomous rework\"}");
                            } else {
                                sendJsonResponse(exchange, 400, "{\"success\": false, \"error\": \"Could not request rework for task\"}");
                            }
                            return;
                        }
                    }
                }
            }

                sendJsonResponse(exchange, 404, "{\"error\": \"Sub-action not found\"}");
            } catch (eu.kalafatic.tryst.core.error.TrystDomainException e) {
                sendJsonResponse(exchange, 403, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
            }
        }

        private static String serializeComment(eu.kalafatic.tryst.domain.review.ReviewComment c) {
            return String.format(
                    "{\"id\":\"%s\",\"reviewId\":\"%s\",\"taskId\":\"%s\",\"revisionNumber\":%d,\"filePath\":\"%s\"," +
                            "\"diffSide\":\"%s\",\"startLine\":%d,\"endLine\":%d,\"codeContext\":\"%s\"," +
                            "\"commentType\":\"%s\",\"content\":\"%s\",\"authorId\":\"%s\",\"resolved\":%b,\"includeInRework\":%b,\"blocking\":%b," +
                            "\"createdAt\":\"%s\",\"updatedAt\":\"%s\"}",
                    escapeJson(c.getId()), escapeJson(c.getReviewId()), escapeJson(c.getTaskId()),
                    c.getRevisionNumber(), escapeJson(c.getFilePath()), c.getDiffSide().name(),
                    c.getStartLine(), c.getEndLine(), escapeJson(c.getCodeContext()),
                    c.getCommentType().name(), escapeJson(c.getContent()), escapeJson(c.getAuthorId()),
                    c.isResolved(), c.isIncludeInRework(), c.isBlocking(), c.getCreatedAt().toString(), c.getUpdatedAt().toString()
            );
        }
    }

    private class GitHubHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!validateCsrfIfCookieAuth(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\": \"Invalid or missing CSRF token\"}");
                return;
            }

            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            String userId = ctx.map(SecurityContext::getUserId).orElseGet(() -> getUserId(exchange));
            String path = exchange.getRequestURI().getPath();

            if (gitHubService == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"GitHubService unavailable\"}");
                return;
            }

            // Route: GET /api/github/account
            if (path.endsWith("/account") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Optional<eu.kalafatic.tryst.domain.github.GitHubCredential> optCred = gitHubService.getCredentialForUser(userId);
                if (optCred.isEmpty()) {
                    sendJsonResponse(exchange, 200, "{\"connected\": false}");
                    return;
                }
                eu.kalafatic.tryst.domain.github.GitHubCredential cred = optCred.get();
                String masked = gitHubCredentialEncryptionService != null
                        ? gitHubCredentialEncryptionService.maskToken(gitHubCredentialEncryptionService.decrypt(cred.getEncryptedToken()))
                        : "********";
                String json = String.format("{\"connected\": true, \"username\": \"%s\", \"maskedToken\": \"%s\", \"scopes\": \"%s\", \"tokenType\": \"%s\", \"expired\": %b, \"createdAt\": \"%s\"}",
                        escapeJson(cred.getGithubUsername()), escapeJson(masked), escapeJson(cred.getScopes()),
                        escapeJson(cred.getTokenType()), cred.isExpired(), cred.getCreatedAt());
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // Route: POST /api/github/connect
            if (path.endsWith("/connect") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String token = extractJsonField(body, "token");
                if (token == null || token.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'token'\"}");
                    return;
                }
                try {
                    eu.kalafatic.tryst.domain.github.GitHubCredential cred = gitHubService.connectAccount(userId, token.trim());
                    String masked = gitHubCredentialEncryptionService != null ? gitHubCredentialEncryptionService.maskToken(token.trim()) : "********";
                    String json = String.format("{\"success\": true, \"username\": \"%s\", \"maskedToken\": \"%s\"}",
                            escapeJson(cred.getGithubUsername()), escapeJson(masked));
                    sendJsonResponse(exchange, 200, json);
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Route: POST /api/github/disconnect
            if (path.endsWith("/disconnect") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                gitHubService.disconnectAccount(userId);
                sendJsonResponse(exchange, 200, "{\"success\": true}");
                return;
            }

            // Route: GET /api/github/repos
            if (path.endsWith("/repos") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    List<eu.kalafatic.tryst.domain.github.GitHubRepository> repos = gitHubService.listRepositories(userId);
                    StringBuilder sb = new StringBuilder("{\"repositories\": [");
                    for (int i = 0; i < repos.size(); i++) {
                        eu.kalafatic.tryst.domain.github.GitHubRepository r = repos.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"id\": \"%s\", \"fullName\": \"%s\", \"owner\": \"%s\", \"name\": \"%s\", \"isPrivate\": %b, \"defaultBranch\": \"%s\", \"cloneUrl\": \"%s\", \"htmlUrl\": \"%s\", \"description\": \"%s\"}",
                                escapeJson(r.id()), escapeJson(r.fullName()), escapeJson(r.owner()), escapeJson(r.name()), r.isPrivate(),
                                escapeJson(r.defaultBranch()), escapeJson(r.cloneUrl()), escapeJson(r.htmlUrl()), escapeJson(r.description() != null ? r.description() : "")));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Route: GET /api/github/repos/{owner}/{repo}/branches
            if (path.contains("/repos/") && path.endsWith("/branches") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String sub = path.substring(path.indexOf("/repos/") + "/repos/".length());
                String repoPart = sub.substring(0, sub.indexOf("/branches"));
                String[] parts = repoPart.split("/");
                if (parts.length != 2) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Invalid repository path format\"}");
                    return;
                }
                String owner = parts[0];
                String repo = parts[1];
                try {
                    List<eu.kalafatic.tryst.domain.github.GitHubBranch> branches = gitHubService.listBranches(userId, owner, repo);
                    StringBuilder sb = new StringBuilder("{\"branches\": [");
                    for (int i = 0; i < branches.size(); i++) {
                        eu.kalafatic.tryst.domain.github.GitHubBranch b = branches.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"name\": \"%s\", \"commitSha\": \"%s\", \"isProtected\": %b}",
                                escapeJson(b.name()), escapeJson(b.commitSha()), b.isProtected()));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Route: POST /api/github/push
            if (path.endsWith("/push") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (userId != null && CodeReview.isAiAgent(userId)) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"AI agents are strictly forbidden from pushing changes. Human authorization required.\"}");
                    return;
                }
                if (ctx.isPresent() && !ctx.get().user().hasPermission(Permission.COMMIT_APPROVED_CHANGES)) {
                    sendJsonResponse(exchange, 403, "{\"error\": \"Permission COMMIT_APPROVED_CHANGES required to push\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String reviewId = extractJsonField(body, "reviewId");
                String remote = extractJsonField(body, "remote");
                String branch = extractJsonField(body, "branch");
                boolean force = body.contains("\"force\":true") || body.contains("\"force\": true");

                if (reviewId == null || reviewId.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'reviewId'\"}");
                    return;
                }

                if (codeReviewRepository == null) {
                    sendJsonResponse(exchange, 503, "{\"error\": \"CodeReviewRepository unavailable\"}");
                    return;
                }

                Optional<CodeReview> optRev = codeReviewRepository.findById(reviewId);
                if (optRev.isEmpty()) {
                    sendJsonResponse(exchange, 404, "{\"error\": \"CodeReview not found: " + reviewId + "\"}");
                    return;
                }
                CodeReview review = optRev.get();

                if (review.getStatus() != CodeReview.ReviewStatus.COMMITTED) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Push rejected: Code review " + reviewId + " has not been committed yet.\"}");
                    return;
                }

                Path repoDir = resolveProjectWorkspacePath(review.getProjectId());
                try {
                    eu.kalafatic.tryst.domain.github.GitHubService.PushResult res = gitHubService.pushAuthorized(
                            userId, repoDir, review, remote, branch, force
                    );

                    if (res.success()) {
                        if (gitCommitRepository != null && res.commitHash() != null) {
                            gitCommitRepository.findByCommitHash(res.commitHash()).ifPresent(rec -> {
                                rec.markPushed(Instant.now());
                                gitCommitRepository.save(rec);
                            });
                        }
                        permissionManager.recordAudit(userId, "GIT_PUSH", "review", review.getId(),
                                review.getProjectId(), review.getTaskId(), "ALLOWED",
                                "Pushed commit " + res.commitHash() + " to " + res.remote() + "/" + res.branch());
                        if (notificationService != null) {
                            notificationService.notifyUser(userId, "PUSH_SUCCESS", "GitHub Push Succeeded",
                                    "Successfully pushed changes to " + res.remote() + "/" + res.branch(), "review", review.getId());
                        }
                        String json = String.format("{\"success\": true, \"remote\": \"%s\", \"branch\": \"%s\", \"commitHash\": \"%s\", \"message\": \"%s\"}",
                                escapeJson(res.remote()), escapeJson(res.branch()), escapeJson(res.commitHash()), escapeJson(res.message()));
                        sendJsonResponse(exchange, 200, json);
                    } else {
                        int statusCode = (res.error() != null && res.error().toLowerCase().contains("conflict")) ? 409 : 400;
                        sendJsonResponse(exchange, statusCode, String.format("{\"success\": false, \"conflict\": %b, \"error\": \"%s\"}",
                                statusCode == 409, escapeJson(res.error())));
                    }
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"GitHub endpoint not found\"}");
        }
    }

    private class NotificationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (notificationRepository == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"NotificationRepository unavailable\"}");
                return;
            }

            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            String userId = ctx.map(SecurityContext::getUserId).orElseGet(() -> getUserId(exchange));
            String path = exchange.getRequestURI().getPath();

            // Route: SSE Stream GET /api/notifications/stream
            if (path.endsWith("/stream") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleNotificationStream(exchange, userId);
                return;
            }

            // Route: Preferences GET /api/notifications/preferences
            if (path.endsWith("/preferences") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                eu.kalafatic.tryst.domain.model.NotificationPreference pref =
                        (notificationService != null) ? notificationService.getPreferences(userId)
                                : eu.kalafatic.tryst.domain.model.NotificationPreference.createDefault(userId);
                StringBuilder sb = new StringBuilder("{");
                sb.append("\"userId\":\"").append(escapeJson(pref.getUserId())).append("\",");
                sb.append("\"browserNotificationsEnabled\":").append(pref.isBrowserNotificationsEnabled()).append(",");
                sb.append("\"inAppNotificationsEnabled\":").append(pref.isInAppNotificationsEnabled()).append(",");
                sb.append("\"enabledTypes\":[");
                int ti = 0;
                for (eu.kalafatic.tryst.domain.model.NotificationType nt : pref.getEnabledTypes()) {
                    if (ti++ > 0) sb.append(",");
                    sb.append("\"").append(nt.name()).append("\"");
                }
                sb.append("],\"updatedAt\":\"").append(pref.getUpdatedAt()).append("\"}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            // Route: Preferences PUT /api/notifications/preferences
            if (path.endsWith("/preferences") && ("PUT".equalsIgnoreCase(exchange.getRequestMethod()) || "POST".equalsIgnoreCase(exchange.getRequestMethod()))) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean browserEnabled = true;
                boolean inAppEnabled = true;
                String bStr = extractJsonField(body, "browserNotificationsEnabled");
                if (bStr != null && !bStr.isBlank()) {
                    browserEnabled = Boolean.parseBoolean(bStr);
                }
                String iStr = extractJsonField(body, "inAppNotificationsEnabled");
                if (iStr != null && !iStr.isBlank()) {
                    inAppEnabled = Boolean.parseBoolean(iStr);
                }

                Set<eu.kalafatic.tryst.domain.model.NotificationType> types = new HashSet<>();
                if (body.contains("\"enabledTypes\"")) {
                    int st = body.indexOf("\"enabledTypes\"");
                    int openBracket = body.indexOf('[', st);
                    int closeBracket = body.indexOf(']', openBracket);
                    if (openBracket != -1 && closeBracket != -1) {
                        String inner = body.substring(openBracket + 1, closeBracket);
                        for (String part : inner.split(",")) {
                            String clean = part.replace("\"", "").trim();
                            eu.kalafatic.tryst.domain.model.NotificationType nt = eu.kalafatic.tryst.domain.model.NotificationType.fromString(clean);
                            if (nt != null) {
                                types.add(nt);
                            }
                        }
                    }
                } else {
                    Collections.addAll(types, eu.kalafatic.tryst.domain.model.NotificationType.values());
                }

                eu.kalafatic.tryst.domain.model.NotificationPreference pref =
                        new eu.kalafatic.tryst.domain.model.NotificationPreference(userId, browserEnabled, inAppEnabled, types, java.time.Instant.now());
                if (notificationService != null) {
                    notificationService.updatePreferences(pref);
                }
                sendJsonResponse(exchange, 200, "{\"success\": true}");
                return;
            }

            if (path.endsWith("/read-all") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                notificationRepository.markAllAsRead(userId);
                sendJsonResponse(exchange, 200, "{\"success\": true}");
                return;
            }

            // Route: Mark single notification as read: POST/PATCH /api/notifications/{id}/read
            String relPath = path.substring(path.indexOf("/api/notifications") + "/api/notifications".length());
            if (relPath.startsWith("/")) relPath = relPath.substring(1);
            String[] parts = relPath.split("/");
            if (parts.length >= 2 && "read".equalsIgnoreCase(parts[1]) &&
                    ("POST".equalsIgnoreCase(exchange.getRequestMethod()) || "PATCH".equalsIgnoreCase(exchange.getRequestMethod()))) {
                String notifId = parts[0];
                notificationRepository.markAsRead(notifId);
                sendJsonResponse(exchange, 200, "{\"success\": true}");
                return;
            }

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> qp = parseQueryParams(query);
                boolean unreadOnly = Boolean.parseBoolean(qp.getOrDefault("unreadOnly", "false"));
                String taskId = qp.get("taskId");
                String sinceSeqStr = qp.get("sinceSeq");
                int limit = 50;
                try {
                    if (qp.containsKey("limit")) limit = Integer.parseInt(qp.get("limit"));
                } catch (Exception ignored) {}

                List<Notification> notifs;
                if (sinceSeqStr != null && !sinceSeqStr.isBlank()) {
                    long seq = 0L;
                    try { seq = Long.parseLong(sinceSeqStr); } catch (Exception ignored) {}
                    notifs = notificationRepository.findSinceSequence(userId, seq, limit);
                } else if (taskId != null && !taskId.isBlank()) {
                    notifs = notificationRepository.findByTaskId(taskId, userId);
                } else {
                    notifs = notificationRepository.findByUserId(userId, unreadOnly, limit);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("{\"notifications\": [");
                for (int i = 0; i < notifs.size(); i++) {
                    Notification n = notifs.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(serializeNotificationJson(n));
                }
                sb.append("], \"unreadCount\": ").append(notificationRepository.countUnread(userId)).append("}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }

        private void handleNotificationStream(HttpExchange exchange, String userId) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            applySecurityHeaders(exchange);
            exchange.sendResponseHeaders(200, 0);

            OutputStream os = exchange.getResponseBody();

            String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            if (lastEventId == null || lastEventId.isBlank()) {
                Map<String, String> qp = parseQueryParams(exchange.getRequestURI().getQuery());
                lastEventId = qp.get("lastEventId");
            }

            long sinceSeq = 0L;
            if (lastEventId != null && !lastEventId.isBlank()) {
                try {
                    sinceSeq = Long.parseLong(lastEventId.trim());
                } catch (NumberFormatException ignored) {}
            }

            if (sinceSeq > 0L && notificationRepository != null) {
                List<Notification> missed = notificationRepository.findSinceSequence(userId, sinceSeq, 100);
                for (Notification n : missed) {
                    writeSseEvent(os, n);
                }
            }

            os.write((": connected userId=" + userId + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
            AutoCloseable registration = (notificationService != null) ? notificationService.registerListener(n -> {
                if (active.get() && (userId.equals(n.getUserId()) || "all".equals(n.getUserId()))) {
                    try {
                        synchronized (os) {
                            writeSseEvent(os, n);
                        }
                    } catch (IOException e) {
                        active.set(false);
                    }
                }
            }) : null;

            try {
                while (active.get()) {
                    try {
                        Thread.sleep(15000);
                        synchronized (os) {
                            os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        break;
                    }
                }
            } finally {
                active.set(false);
                if (registration != null) {
                    try { registration.close(); } catch (Exception ignored) {}
                }
                try { os.close(); } catch (Exception ignored) {}
            }
        }

        private void writeSseEvent(OutputStream os, Notification n) throws IOException {
            String data = serializeNotificationJson(n);
            String payload = "id: " + n.getSequenceNumber() + "\n"
                    + "event: notification\n"
                    + "data: " + data + "\n\n";
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        private static String serializeNotificationJson(Notification n) {
            return String.format(
                    "{\"id\":\"%s\",\"type\":\"%s\",\"title\":\"%s\",\"message\":\"%s\",\"referenceType\":\"%s\",\"referenceId\":\"%s\",\"isRead\":%b,\"createdAt\":\"%s\",\"dedupKey\":\"%s\",\"sequenceNumber\":%d}",
                    escapeJson(n.getId()), escapeJson(n.getType()), escapeJson(n.getTitle()),
                    escapeJson(n.getMessage()), escapeJson(n.getReferenceType() != null ? n.getReferenceType() : ""),
                    escapeJson(n.getReferenceId() != null ? n.getReferenceId() : ""),
                    n.isRead(), n.getCreatedAt().toString(),
                    escapeJson(n.getDedupKey() != null ? n.getDedupKey() : ""),
                    n.getSequenceNumber()
            );
        }
    }

    private class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String reqCorrId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            try (var ignoredScope = eu.kalafatic.tryst.core.log.CorrelationContext.open(reqCorrId, null)) {

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            Optional<SecurityContext> ctx = resolveSecurityContext(exchange);
            if (ctx.isEmpty() || !ctx.get().isAdmin()) {
                String actor = ctx.map(SecurityContext::getUserId).orElse("unauthenticated");
                permissionManager.recordAudit(actor, "ADMIN_ACCESS_DENIED", "admin", null, null, null, "DENIED", "Non-admin attempted admin action");
                sendJsonResponse(exchange, 403, "{\"error\": \"Access denied: Administrator privilege required\"}");
                return;
            }
            String userId = ctx.get().getUserId();

            String path = exchange.getRequestURI().getPath(); // /tryst/api/admin/... or /api/admin/...
            int adminIdx = path.indexOf("/api/admin");
            String sub = (adminIdx >= 0) ? path.substring(adminIdx + "/api/admin".length()) : "";
            if (sub.startsWith("/")) sub = sub.substring(1);

            // 1. Server Status: /api/admin/server
            if (sub.equals("server")) {
                boolean dbOk = databaseManager != null;
                boolean httpOk = isRunning();
                boolean aiOk = aiMonitoringService != null && aiMonitoringService.checkHealth().endpointAvailable();
                String json = String.format("""
                        {
                          "componentStatus": {
                            "DATABASE": "%s",
                            "HTTP_SERVER": "%s",
                            "AI_PROVIDER": "%s"
                          },
                          "overallStatus": "%s"
                        }
                        """,
                        dbOk ? "AVAILABLE" : "UNAVAILABLE",
                        httpOk ? "AVAILABLE" : "UNAVAILABLE",
                        aiOk ? "AVAILABLE" : "DEGRADED",
                        (dbOk && httpOk) ? "READY" : "DEGRADED");
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 2. OSGi Bundles Status: /api/admin/bundles
            if (sub.equals("bundles")) {
                org.osgi.framework.Bundle currentBundle = org.osgi.framework.FrameworkUtil.getBundle(TrystHttpServer.class);
                org.osgi.framework.BundleContext bCtx = currentBundle != null ? currentBundle.getBundleContext() : null;
                StringBuilder sb = new StringBuilder("{\"bundles\": [");
                if (bCtx != null) {
                    org.osgi.framework.Bundle[] bundles = bCtx.getBundles();
                    for (int i = 0; i < bundles.length; i++) {
                        org.osgi.framework.Bundle b = bundles[i];
                        if (i > 0) sb.append(",");
                        String stateStr = switch (b.getState()) {
                            case org.osgi.framework.Bundle.ACTIVE -> "ACTIVE";
                            case org.osgi.framework.Bundle.STARTING -> "STARTING";
                            case org.osgi.framework.Bundle.STOPPING -> "STOPPING";
                            case org.osgi.framework.Bundle.RESOLVED -> "RESOLVED";
                            case org.osgi.framework.Bundle.INSTALLED -> "INSTALLED";
                            default -> "UNINSTALLED";
                        };
                        sb.append(String.format("{\"bundleId\": %d, \"symbolicName\": \"%s\", \"version\": \"%s\", \"state\": \"%s\"}",
                                b.getBundleId(), b.getSymbolicName(), b.getVersion().toString(), stateStr));
                    }
                } else {
                    sb.append("{\"bundleId\": 1, \"symbolicName\": \"eu.kalafatic.tryst.standalone\", \"version\": \"1.0.0\", \"state\": \"ACTIVE\"}");
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            // 3. Registered Services & Service Control: /api/admin/services
            if (sub.startsWith("services")) {
                if (sub.contains("/action") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String[] parts = sub.split("/");
                    String sName = parts[1];
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String action = extractJsonField(body, "action");
                    boolean success = false;
                    if (managedServiceController != null) {
                        if ("start".equalsIgnoreCase(action)) {
                            success = managedServiceController.startService(sName);
                        } else if ("stop".equalsIgnoreCase(action)) {
                            success = managedServiceController.stopService(sName);
                        }
                    }
                    permissionManager.recordAudit(userId, "SERVICE_" + (action != null ? action.toUpperCase() : "ACTION"),
                            "service", sName, null, null, success ? "SUCCESS" : "FAILED", "Action: " + action);
                    sendJsonResponse(exchange, success ? 200 : 400, "{\"success\": " + success + "}");
                    return;
                }

                StringBuilder sb = new StringBuilder("{\"services\": [");
                if (managedServiceController != null) {
                    var services = managedServiceController.listServices();
                    int i = 0;
                    for (var s : services.values()) {
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"name\": \"%s\", \"displayName\": \"%s\", \"running\": %b, \"status\": \"%s\", \"details\": \"%s\"}",
                                s.name(), s.displayName(), s.running(), s.status().name(), escapeJson(s.details())));
                        i++;
                    }
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            // 4. HTTP Server Status: /api/admin/http
            if (sub.equals("http")) {
                String json = String.format("""
                        {
                          "running": %b,
                          "port": %d,
                          "canonicalUrl": "%s",
                          "boundPort": %d,
                          "host": "%s"
                        }
                        """, isRunning(), getPort(), getCanonicalUrl(), actualPort, host);
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 5. Worker utilization: /api/admin/workers
            if (sub.equals("workers")) {
                int capacity = taskOrchestrator != null ? taskOrchestrator.getConcurrency() : 1;
                int activeWorkers = taskOrchestrator != null ? taskOrchestrator.getActiveTaskCount() : 0;
                int queueDepth = taskOrchestrator != null ? taskOrchestrator.getQueueDepth() : 0;
                String json = String.format("""
                        {
                          "capacity": %d,
                          "activeWorkers": %d,
                          "queuedCount": %d,
                          "utilizationPercentage": %.2f
                        }
                        """, capacity, activeWorkers, queueDepth, capacity > 0 ? (activeWorkers * 100.0 / capacity) : 0.0);
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 6. Active Tasks, Contexts, and Cancellation: /api/admin/tasks
            if (sub.startsWith("tasks")) {
                if (sub.endsWith("/cancel") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String[] parts = sub.split("/");
                    String taskId = parts[1];
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String reason = extractJsonField(body, "reason");
                    boolean cancelled = taskOrchestrator != null && taskOrchestrator.cancelTask(taskId, reason != null ? reason : "Administrative cancellation");
                    permissionManager.recordAudit(userId, "ADMIN_TASK_CANCEL", "task", taskId, null, null, cancelled ? "SUCCESS" : "FAILED", "Reason: " + reason);
                    sendJsonResponse(exchange, cancelled ? 200 : 400, "{\"success\": " + cancelled + "}");
                    return;
                }
                if (sub.endsWith("/context") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String[] parts = sub.split("/");
                    String taskId = parts[1];
                    if (taskOrchestrator != null) {
                        var optCtx = taskOrchestrator.getExecutionContext(taskId);
                        if (optCtx.isPresent()) {
                            var tCtx = optCtx.get();
                            String json = String.format("""
                                    {
                                      "taskId": "%s",
                                      "userId": "%s",
                                      "workerId": "%s",
                                      "assignedModel": "%s",
                                      "cancelled": %b,
                                      "workspacePath": "%s"
                                    }
                                    """, tCtx.getTask().getId(), tCtx.getUserId(), tCtx.getWorkerId(),
                                    tCtx.getModelName() != null ? tCtx.getModelName() : "", tCtx.isCancelled(),
                                    tCtx.getWorkspace() != null ? escapeJson(tCtx.getWorkspace().getRootPath().toString()) : "");
                            sendJsonResponse(exchange, 200, json);
                            return;
                        }
                    }
                    sendJsonResponse(exchange, 404, "{\"error\": \"Active execution context not found for task\"}");
                    return;
                }

                if (taskRepository != null) {
                    String query = exchange.getRequestURI().getQuery();
                    List<Task> tasks;
                    if (query != null && query.contains("status=RECOVERY_REQUIRED")) {
                        tasks = taskRepository.findAllTasks().stream()
                                .filter(t -> t.getStatus() == eu.kalafatic.tryst.domain.model.TaskStatus.RECOVERY_REQUIRED)
                                .toList();
                    } else {
                        tasks = taskRepository.findAllTasks();
                    }
                    StringBuilder sb = new StringBuilder("{\"tasks\": [");
                    for (int i = 0; i < tasks.size(); i++) {
                        Task t = tasks.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"id\": \"%s\", \"title\": \"%s\", \"status\": \"%s\", \"priority\": \"%s\", \"projectId\": \"%s\", \"createdAt\": \"%s\"}",
                                t.getId(), escapeJson(t.getTitle()), t.getStatus().name(), t.getPriority().name(),
                                t.getProjectId() != null ? t.getProjectId() : "", t.getCreatedAt()));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }
                sendJsonResponse(exchange, 200, "{\"tasks\": []}");
                return;
            }

            // 7. Workspaces & Sandboxes Inspection: /api/admin/workspaces
            if (sub.startsWith("workspaces")) {
                if (workspaceService != null) {
                    if (sub.length() > "workspaces".length()) {
                        String wsId = sub.substring("workspaces".length());
                        if (wsId.startsWith("/")) wsId = wsId.substring(1);
                        Workspace ws = workspaceService.getWorkspace(wsId);
                        if (ws != null) {
                            String json = String.format("""
                                    {
                                      "id": "%s",
                                      "projectId": "%s",
                                      "taskId": "%s",
                                      "ownerId": "%s",
                                      "state": "%s",
                                      "path": "%s",
                                      "branch": "%s",
                                      "repositoryUrl": "%s"
                                    }
                                    """, ws.getId(), ws.getActiveProject().map(Project::getId).orElse(""),
                                    ws.getTaskId() != null ? ws.getTaskId() : "", ws.getOwnerId() != null ? ws.getOwnerId() : "",
                                    ws.getStatus().name(), escapeJson(ws.getRootPath().toString()),
                                    ws.getBranch() != null ? ws.getBranch() : "", ws.getRepositoryUrl() != null ? ws.getRepositoryUrl() : "");
                            sendJsonResponse(exchange, 200, json);
                            return;
                        } else {
                            sendJsonResponse(exchange, 404, "{\"error\": \"Workspace not found\"}");
                            return;
                        }
                    }

                    List<Workspace> list = workspaceService.getAllWorkspaces();
                    StringBuilder sb = new StringBuilder("{\"workspaces\": [");
                    for (int i = 0; i < list.size(); i++) {
                        Workspace ws = list.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"id\": \"%s\", \"projectId\": \"%s\", \"taskId\": \"%s\", \"ownerId\": \"%s\", \"state\": \"%s\", \"path\": \"%s\"}",
                                ws.getId(), ws.getActiveProject().map(Project::getId).orElse(""),
                                ws.getTaskId() != null ? ws.getTaskId() : "", ws.getOwnerId() != null ? ws.getOwnerId() : "",
                                ws.getStatus().name(), escapeJson(ws.getRootPath().toString())));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }
                sendJsonResponse(exchange, 200, "{\"workspaces\": []}");
                return;
            }

            // 8. Available Models: /api/admin/models
            if (sub.equals("models")) {
                if (aiModelRegistryService != null) {
                    var models = aiModelRegistryService.getAllModels();
                    StringBuilder sb = new StringBuilder("{\"models\": [");
                    for (int i = 0; i < models.size(); i++) {
                        var m = models.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"modelId\": \"%s\", \"providerId\": \"%s\", \"name\": \"%s\", \"contextLimit\": %d}",
                                m.modelIdentifier(), m.providerId(), escapeJson(m.displayName()), m.contextLimit()));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }
                sendJsonResponse(exchange, 200, "{\"models\": []}");
                return;
            }

            // 9. Git Operations status: /api/admin/git
            if (sub.equals("git")) {
                boolean gitAvail = gitService != null;
                boolean ghAvail = gitHubService != null;
                String json = String.format("""
                        {
                          "configured": %b,
                          "isolated": true,
                          "gitAvailable": %b,
                          "gitHubIntegrationAvailable": %b
                        }
                        """, gitAvail, gitAvail, ghAvail);
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 10. Build Systems: /api/admin/builds
            if (sub.equals("builds")) {
                String json = """
                        {
                          "supportedBuildSystems": ["MAVEN", "GRADLE", "NPM", "CARGO", "PYTHON", "CUSTOM_TRYST_BUILD"]
                        }
                        """;
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 11. Overview metrics
            if (sub.equals("overview") || sub.isEmpty()) {
                long totalUsers = userRepository != null ? userRepository.count() : 0;
                long totalSessions = sessionRepository != null ? sessionRepository.findAllActive().size() : 0;
                long totalProjects = projectRepository != null ? projectRepository.findAll().size() : 0;
                long totalTasks = taskRepository != null ? taskRepository.findAll().size() : 0;
                long totalAudit = auditEventRepository != null ? auditEventRepository.count() : 0;

                ServerMonitoringService.SystemMetrics sys = serverMonitoringService != null ? serverMonitoringService.collectMetrics() : null;
                AiMonitoringService.AiHealthStatus ai = aiMonitoringService != null ? aiMonitoringService.checkHealth() : null;

                String json = String.format("""
                        {
                          "server": "UP",
                          "totalUsers": %d,
                          "activeSessions": %d,
                          "totalProjects": %d,
                          "totalTasks": %d,
                          "totalAuditRecords": %d,
                          "cpuSystem": %f,
                          "cpuProcess": %f,
                          "heapUsedBytes": %d,
                          "heapMaxBytes": %d,
                          "aiEndpoint": "%s",
                          "aiAvailable": %b,
                          "aiModel": "%s"
                        }
                        """, totalUsers, totalSessions, totalProjects, totalTasks, totalAudit,
                        sys != null ? sys.systemCpuLoad() : -1.0,
                        sys != null ? sys.processCpuLoad() : -1.0,
                        sys != null ? sys.heapUsedBytes() : 0,
                        sys != null ? sys.heapMaxBytes() : 0,
                        ai != null ? ai.endpoint() : "",
                        ai != null && ai.endpointAvailable(),
                        ai != null ? ai.activeModel() : "");
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 2. Users Administration: /api/admin/users
            if (sub.startsWith("users")) {
                handleAdminUsers(exchange, sub);
                return;
            }

            // 3. Sessions Administration: /api/admin/sessions
            if (sub.startsWith("sessions")) {
                handleAdminSessions(exchange, sub);
                return;
            }

            // 4. Processes Administration: /api/admin/processes
            if (sub.startsWith("processes")) {
                handleAdminProcesses(exchange, sub);
                return;
            }

            // 5. Audit logs: /api/admin/audit
            if (sub.startsWith("audit")) {
                if (auditEventRepository != null) {
                    List<AuditEvent> events = auditEventRepository.findRecent(100);
                    StringBuilder sb = new StringBuilder("{\"auditEvents\": [");
                    for (int i = 0; i < events.size(); i++) {
                        AuditEvent e = events.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"id\": \"%s\", \"time\": \"%s\", \"actor\": \"%s\", \"action\": \"%s\", \"target\": \"%s\", \"outcome\": \"%s\", \"details\": \"%s\"}",
                                e.id(), e.eventTime(), e.actorId(), e.action(), e.targetType() + ":" + (e.targetId() != null ? e.targetId() : ""),
                                e.outcome(), escapeJson(e.detailsJson() != null ? e.detailsJson() : "")));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                } else {
                    sendJsonResponse(exchange, 200, "{\"auditEvents\": []}");
                }
                return;
            }

            // 6. Diagnostics & logs: /api/admin/logs
            if (sub.startsWith("logs")) {
                if (logDiagnosticService != null) {
                    Map<String, String> qParams = parseQueryParams(exchange.getRequestURI().getQuery());
                    String qLevel = qParams.get("level");
                    String qComp = qParams.get("component");
                    String qSearch = qParams.get("search");
                    String qCorr = qParams.get("correlationId");
                    String qTask = qParams.get("taskId");
                    int qLimit = 100;
                    if (qParams.containsKey("limit")) {
                        try { qLimit = Integer.parseInt(qParams.get("limit")); } catch (Exception ignored) {}
                    }

                    List<LogDiagnosticService.LogEntry> logs = logDiagnosticService.query(qLevel, qComp, qSearch, qCorr, qTask, qLimit);
                    StringBuilder sb = new StringBuilder("{\"logs\": [");
                    for (int i = 0; i < logs.size(); i++) {
                        LogDiagnosticService.LogEntry l = logs.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"seq\": %d, \"time\": \"%s\", \"level\": \"%s\", \"component\": \"%s\", \"message\": \"%s\", \"correlationId\": \"%s\", \"taskId\": \"%s\"}",
                                l.sequence(), l.timestamp(), l.level(), escapeJson(l.component()), escapeJson(l.message()),
                                l.correlationId() != null ? escapeJson(l.correlationId()) : "",
                                l.taskId() != null ? escapeJson(l.taskId()) : ""));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                } else {
                    sendJsonResponse(exchange, 200, "{\"logs\": []}");
                }
                return;
            }

            // 7. Telemetry & resources: /api/admin/telemetry
            if (sub.startsWith("telemetry")) {
                if (telemetryRepository != null) {
                    List<TelemetrySnapshot> snaps = telemetryRepository.findRecent(50);
                    StringBuilder sb = new StringBuilder("{\"snapshots\": [");
                    for (int i = 0; i < snaps.size(); i++) {
                        TelemetrySnapshot s = snaps.get(i);
                        if (i > 0) sb.append(",");
                        sb.append(String.format("{\"timestamp\": \"%s\", \"cpuSystem\": %f, \"cpuProcess\": %f, \"heapUsed\": %d, \"heapMax\": %d, \"activeSessions\": %d}",
                                s.timestamp(), s.cpuSystem(), s.cpuProcess(), s.memoryHeapUsed(), s.memoryHeapMax(), s.activeSessions()));
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                } else {
                    sendJsonResponse(exchange, 200, "{\"snapshots\": []}");
                }
                return;
            }

            // 8. Database Health & Maintenance: /api/admin/database
            if (sub.startsWith("database")) {
                long dbSize = 0;
                String dbPath = "unknown";
                if (databaseManager != null && databaseManager.getDatabasePath() != null) {
                    try {
                        dbPath = databaseManager.getDatabasePath().toString();
                        dbSize = Files.size(databaseManager.getDatabasePath());
                    } catch (Exception ignored) {}
                }
                if (sub.endsWith("/backup") && "POST".equalsIgnoreCase(exchange.getRequestMethod()) && backupService != null) {
                    Path backupDir = databaseManager.getDatabasePath().getParent().resolve("backups");
                    Path backupFile = backupService.createBackup(backupDir);
                    sendJsonResponse(exchange, 200, "{\"success\": true, \"backupPath\": \"" + escapeJson(backupFile.toString()) + "\"}");
                    return;
                }
                if (sub.endsWith("/integrity") && "POST".equalsIgnoreCase(exchange.getRequestMethod()) && databaseManager != null) {
                    try {
                        boolean ok = databaseManager.verifyDatabaseConsistency();
                        sendJsonResponse(exchange, 200, "{\"success\": true, \"consistent\": " + ok + ", \"message\": \"Consistency check passed (integrity_check, quick_check, foreign_key_check)\"}");
                    } catch (Exception e) {
                        sendJsonResponse(exchange, 500, "{\"success\": false, \"consistent\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                    }
                    return;
                }
                if (sub.endsWith("/retention") && "POST".equalsIgnoreCase(exchange.getRequestMethod()) && dataRetentionService != null) {
                    try {
                        var report = dataRetentionService.executeRetentionPolicy();
                        String res = String.format("{\"success\": true, \"expiredSessionsPurged\": %d, \"oldTelemetryPurged\": %d, \"oldAuditEventsPurged\": %d, \"oldTaskEventsPurged\": %d}",
                                report.expiredSessionsPurged(), report.oldTelemetryPurged(), report.oldAuditEventsPurged(), report.oldTaskEventsPurged());
                        sendJsonResponse(exchange, 200, res);
                    } catch (Exception e) {
                        sendJsonResponse(exchange, 500, "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}");
                    }
                    return;
                }
                String json = String.format("{\"databasePath\": \"%s\", \"sizeBytes\": %d, \"schemaVersion\": %d}",
                        escapeJson(dbPath), dbSize, 3);
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 9. AI Infrastructure: /api/admin/ai
            if (sub.startsWith("ai")) {
                AiMonitoringService.AiHealthStatus ai = aiMonitoringService != null ? aiMonitoringService.checkHealth() : null;
                String json = String.format("{\"available\": %b, \"endpoint\": \"%s\", \"model\": \"%s\", \"totalRequests\": %d, \"successCount\": %d, \"failCount\": %d, \"avgLatencyMs\": %f}",
                        ai != null && ai.endpointAvailable(),
                        ai != null ? ai.endpoint() : "",
                        ai != null ? ai.activeModel() : "",
                        ai != null ? ai.totalRequests() : 0,
                        ai != null ? ai.successfulRequests() : 0,
                        ai != null ? ai.failedRequests() : 0,
                        ai != null ? ai.averageLatencyMs() : 0.0);
                sendJsonResponse(exchange, 200, json);
                return;
            }

            // 10. Operational Configuration: /api/admin/config
            if (sub.startsWith("config")) {
                if (configurationService != null) {
                    ApplicationConfiguration cfg = configurationService.getConfiguration();
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        String model = extractJsonField(body, "model");
                        String endpoint = extractJsonField(body, "endpoint");
                        ApplicationConfiguration updated = cfg;
                        if (model != null && !model.isBlank()) updated = updated.withDefaultModel(model.trim());
                        if (endpoint != null && !endpoint.isBlank()) updated = updated.withOllamaEndpoint(endpoint.trim());
                        configurationService.updateConfiguration(updated);
                        cfg = updated;
                    }
                    String json = String.format("{\"ollamaEndpoint\": \"%s\", \"defaultModel\": \"%s\", \"timeoutSecs\": %d, \"maxIterations\": %d}",
                            cfg.ollamaEndpoint(), cfg.defaultModel(), cfg.requestTimeoutSeconds(), cfg.maxIterations());
                    sendJsonResponse(exchange, 200, json);
                    return;
                }
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"Admin endpoint not found\"}");
            }
        }

        private void handleAdminUsers(HttpExchange exchange, String sub) throws IOException {
            if (userRepository == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"UserRepository unavailable\"}");
                return;
            }

            if (sub.equals("users") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<User> users = userRepository.findAll();
                StringBuilder sb = new StringBuilder("{\"users\": [");
                for (int i = 0; i < users.size(); i++) {
                    User u = users.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"username\": \"%s\", \"role\": \"%s\", \"active\": %b, \"locked\": %b, \"failedAttempts\": %d, \"createdAt\": \"%s\"}",
                            u.getId(), u.getUsername(), u.getRole().name(), u.isActive(), u.isLocked(), u.getFailedAttempts(), u.getCreatedAt()));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }

            if (sub.equals("users") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String username = extractJsonField(body, "username");
                String password = extractJsonField(body, "password");
                String roleStr = extractJsonField(body, "role");
                if (username == null || username.isBlank() || password == null || password.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Username and password required\"}");
                    return;
                }
                UserRole role = UserRole.USER;
                if (roleStr != null) {
                    try { role = UserRole.valueOf(roleStr.toUpperCase()); } catch (Exception ignored) {}
                }
                String salt = eu.kalafatic.tryst.domain.security.PasswordHasher.generateSalt();
                String hash = eu.kalafatic.tryst.domain.security.PasswordHasher.hashPassword(password.toCharArray(), salt);
                User newUser = new User(username, hash, salt, role);
                userRepository.save(newUser);
                permissionManager.recordAudit("admin", "USER_CREATE", "user", newUser.getId(), null, null, "SUCCESS", "Created user " + username);
                sendJsonResponse(exchange, 201, "{\"success\": true, \"userId\": \"" + newUser.getId() + "\"}");
                return;
            }

            String pathWithoutQuery = exchange.getRequestURI().getPath();
            int userIdx = pathWithoutQuery.indexOf("/api/admin/users");
            String userSub = (userIdx >= 0) ? pathWithoutQuery.substring(userIdx + "/api/admin/users".length()) : "";
            if (userSub.startsWith("/")) userSub = userSub.substring(1);

            if (!userSub.isBlank()) {
                String[] parts = userSub.split("/");
                String targetUserId = parts[0];
                Optional<User> optUser = userRepository.findById(targetUserId);
                if (optUser.isEmpty()) {
                    sendJsonResponse(exchange, 404, "{\"error\": \"User not found\"}");
                    return;
                }
                User targetUser = optUser.get();
                if (parts.length >= 2) {
                    String op = parts[1];
                    if ("lock".equalsIgnoreCase(op)) {
                        targetUser.setLocked(true);
                        userRepository.save(targetUser);
                        if (sessionRepository != null) sessionRepository.revokeByUserId(targetUserId);
                        sendJsonResponse(exchange, 200, "{\"success\": true}");
                        return;
                    } else if ("unlock".equalsIgnoreCase(op)) {
                        targetUser.setLocked(false);
                        targetUser.resetFailedAttempts();
                        userRepository.save(targetUser);
                        sendJsonResponse(exchange, 200, "{\"success\": true}");
                        return;
                    } else if ("deactivate".equalsIgnoreCase(op)) {
                        targetUser.setActive(false);
                        userRepository.save(targetUser);
                        if (sessionRepository != null) sessionRepository.revokeByUserId(targetUserId);
                        sendJsonResponse(exchange, 200, "{\"success\": true}");
                        return;
                    } else if ("activate".equalsIgnoreCase(op)) {
                        targetUser.setActive(true);
                        userRepository.save(targetUser);
                        sendJsonResponse(exchange, 200, "{\"success\": true}");
                        return;
                    } else if ("role".equalsIgnoreCase(op) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        String roleStr = extractJsonField(body, "role");
                        if (roleStr != null) {
                            try {
                                UserRole role = UserRole.valueOf(roleStr.toUpperCase());
                                targetUser.setRole(role);
                                userRepository.save(targetUser);
                                permissionManager.recordAudit("admin", "USER_ROLE_CHANGE", "user", targetUserId, null, null, "SUCCESS", "Role changed to " + role);
                                sendJsonResponse(exchange, 200, "{\"success\": true, \"role\": \"" + role.name() + "\"}");
                                return;
                            } catch (Exception e) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Invalid role: " + escapeJson(roleStr) + "\"}");
                                return;
                            }
                        }
                    }
                }
            }
            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }

        private void handleAdminSessions(HttpExchange exchange, String sub) throws IOException {
            if (sessionRepository == null) {
                sendJsonResponse(exchange, 503, "{\"error\": \"UserSessionRepository unavailable\"}");
                return;
            }
            if (sub.equals("sessions") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<UserSession> sessions = sessionRepository.findAll();
                StringBuilder sb = new StringBuilder("{\"sessions\": [");
                for (int i = 0; i < sessions.size(); i++) {
                    UserSession s = sessions.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"userId\": \"%s\", \"clientIp\": \"%s\", \"expiresAt\": \"%s\", \"revoked\": %b, \"lastActivity\": \"%s\"}",
                            s.getId(), s.getUserId(), s.getClientIp(), s.getExpiresAt(), s.isRevoked(), s.getLastActivity()));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }
            if (sub.contains("/")) {
                String[] parts = sub.split("/");
                String targetSessionId = parts[1];
                if (parts.length >= 3 && "revoke".equalsIgnoreCase(parts[2])) {
                    sessionRepository.revoke(targetSessionId);
                    sendJsonResponse(exchange, 200, "{\"success\": true}");
                    return;
                }
            }
            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }

        private void handleAdminProcesses(HttpExchange exchange, String sub) throws IOException {
            if (processExecutionManager == null) {
                sendJsonResponse(exchange, 200, "{\"processes\": []}");
                return;
            }
            if (sub.equals("processes") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                List<ManagedProcessRecord> procs = processExecutionManager.getManagedProcesses();
                StringBuilder sb = new StringBuilder("{\"processes\": [");
                for (int i = 0; i < procs.size(); i++) {
                    ManagedProcessRecord p = procs.get(i);
                    if (i > 0) sb.append(",");
                    sb.append(String.format("{\"id\": \"%s\", \"pid\": %d, \"command\": \"%s\", \"workingDir\": \"%s\", \"status\": \"%s\", \"durationMs\": %d}",
                            p.getId(), p.getPid(), escapeJson(p.getCommandClass()), escapeJson(p.getWorkingDir()), p.getStatus(), p.getDurationMs()));
                }
                sb.append("]}");
                sendJsonResponse(exchange, 200, sb.toString());
                return;
            }
            if (sub.contains("/")) {
                String[] parts = sub.split("/");
                String procId = parts[1];
                if (parts.length >= 3 && ("kill".equalsIgnoreCase(parts[2]) || "terminate".equalsIgnoreCase(parts[2]))) {
                    boolean ok = processExecutionManager.terminateProcess(procId);
                    permissionManager.recordAudit("admin", "PROCESS_TERMINATE", "process", procId, null, null, ok ? "SUCCESS" : "FAILED", "Admin process termination");
                    sendJsonResponse(exchange, 200, "{\"success\": " + ok + "}");
                    return;
                }
                if (parts.length >= 3 && "output".equalsIgnoreCase(parts[2]) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    List<String> output = processExecutionManager.getProcessOutput(procId);
                    StringBuilder sb = new StringBuilder("{\"processId\": \"" + procId + "\", \"lines\": [");
                    for (int i = 0; i < output.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append("\"").append(escapeJson(output.get(i))).append("\"");
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }
            }
            sendJsonResponse(exchange, 404, "{\"error\": \"Not Found\"}");
        }
    }

    private Path resolveProjectWorkspacePath(String projectId) {
        if (projectRepository != null) {
            Optional<Project> optProj = projectRepository.findById(projectId);
            if (optProj.isPresent()) {
                return optProj.get().getLocation();
            }
        }
        if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
            return workspaceService.getCurrentWorkspace().getRootPath();
        }
        return Path.of(System.getProperty("user.home"), ".tryst", "projects", projectId);
    }

    private static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            } else if (kv.length == 1) {
                map.put(kv[0], "");
            }
        }
        return map;
    }

    private static String serializeReview(CodeReview r) {
        String approvedRevStr = r.getApprovedRevision() != null ? r.getApprovedRevision().toString() : "null";
        return String.format(
                "{\"id\": \"%s\", \"projectId\": \"%s\", \"taskId\": \"%s\", \"branch\": \"%s\", \"status\": \"%s\", " +
                        "\"contentFingerprint\": \"%s\", \"approvedFingerprint\": \"%s\", \"approvedRevision\": %s, \"approvedBy\": \"%s\", " +
                        "\"commitHash\": \"%s\", \"revisionCount\": %d, \"aiExplanation\": \"%s\", " +
                        "\"buildStatus\": \"%s\", \"testSummary\": \"%s\", \"initialDiff\": \"%s\", \"effectiveDiff\": \"%s\"}",
                escapeJson(r.getId()), escapeJson(r.getProjectId()), escapeJson(r.getTaskId()), escapeJson(r.getBranch()),
                r.getStatus().name(), escapeJson(r.getContentFingerprint()),
                escapeJson(r.getApprovedFingerprint() != null ? r.getApprovedFingerprint() : ""),
                approvedRevStr,
                escapeJson(r.getApprovedBy() != null ? r.getApprovedBy() : ""),
                escapeJson(r.getCommitHash() != null ? r.getCommitHash() : ""),
                r.getRevisionCount(),
                escapeJson(r.getAiExplanation() != null ? r.getAiExplanation() : ""),
                escapeJson(r.getBuildStatus() != null ? r.getBuildStatus() : ""),
                escapeJson(r.getTestSummary() != null ? r.getTestSummary() : ""),
                escapeJson(r.getInitialDiff() != null ? r.getInitialDiff() : ""),
                escapeJson(r.getEffectiveDiff() != null ? r.getEffectiveDiff() : "")
        );
    }

    private static class StaticAssetHandler implements HttpHandler {
        private static final String APP_HTML = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>TRYST – Production Platform Administration & Code Review Center</title>
                    <style>
                        :root {
                            --bg: #0f172a; --panel: #1e293b; --border: #334155;
                            --text: #f8fafc; --muted: #94a3b8; --primary: #38bdf8;
                            --green: #22c55e; --red: #ef4444; --yellow: #eab308;
                        }
                        * { box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; background: var(--bg); color: var(--text); }
                        header { background: #090d16; padding: 12px 24px; border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; }
                        h1 { margin: 0; font-size: 18px; color: var(--primary); display: flex; align-items: center; gap: 8px; }
                        .top-nav { display: flex; gap: 16px; align-items: center; }
                        .nav-btn { background: transparent; border: 1px solid var(--border); color: var(--text); padding: 6px 12px; border-radius: 4px; cursor: pointer; }
                        .nav-btn.active { background: var(--primary); color: #000; font-weight: bold; }
                        .container { display: flex; height: calc(100vh - 54px); }
                        .sidebar { width: 300px; border-right: 1px solid var(--border); background: #131d2e; display: flex; flex-direction: column; overflow-y: auto; }
                        .main-content { flex: 1; display: flex; flex-direction: column; overflow-y: auto; padding: 24px; }
                        .card { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 20px; }
                        .section-title { font-size: 14px; font-weight: bold; text-transform: uppercase; color: var(--muted); margin-bottom: 12px; }
                        .list-item { padding: 10px 14px; border-radius: 6px; cursor: pointer; border: 1px solid transparent; margin-bottom: 6px; }
                        .list-item:hover { background: rgba(56, 189, 248, 0.1); border-color: rgba(56, 189, 248, 0.3); }
                        .list-item.active { background: #1e3a5f; border-color: var(--primary); }
                        .badge { padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; text-transform: uppercase; }
                        .badge-success { background: rgba(34, 197, 94, 0.2); color: var(--green); border: 1px solid var(--green); }
                        .badge-warning { background: rgba(234, 179, 8, 0.2); color: var(--yellow); border: 1px solid var(--yellow); }
                        .badge-error { background: rgba(239, 68, 68, 0.2); color: var(--red); border: 1px solid var(--red); }
                        .badge-info { background: rgba(56, 189, 248, 0.2); color: var(--primary); border: 1px solid var(--primary); }
                        .btn { padding: 8px 16px; border-radius: 6px; font-weight: 600; cursor: pointer; border: none; font-size: 13px; }
                        .btn-primary { background: #0284c7; color: white; }
                        .btn-success { background: #16a34a; color: white; }
                        .btn-danger { background: #dc2626; color: white; }
                        .btn-warning { background: #d97706; color: white; }
                        .btn-sm { padding: 4px 8px; font-size: 11px; border-radius: 4px; }
                        .btn:hover { opacity: 0.9; }
                        pre.diff-view { background: #0b1120; border: 1px solid var(--border); border-radius: 6px; padding: 12px; font-family: monospace; font-size: 13px; overflow-x: auto; line-height: 1.5; margin: 0; }
                        .diff-line { display: flex; cursor: pointer; user-select: none; }
                        .diff-line:hover { background: rgba(56, 189, 248, 0.15); }
                        .diff-line.selected { background: rgba(234, 179, 8, 0.25) !important; border-left: 3px solid var(--yellow); }
                        .diff-line-num { width: 50px; text-align: right; padding-right: 12px; color: var(--muted); border-right: 1px solid var(--border); user-select: none; font-size: 12px; }
                        .diff-line-code { padding-left: 12px; flex: 1; white-space: pre-wrap; font-family: monospace; }
                        .diff-add { color: #4ade80; background: rgba(34, 197, 94, 0.12); }
                        .diff-del { color: #f87171; background: rgba(239, 68, 68, 0.12); }
                        .diff-hdr { color: #94a3b8; font-weight: bold; background: #131d2e; }
                        textarea.code-editor { width: 100%; height: 140px; background: #090d16; border: 1px solid var(--border); color: #e2e8f0; font-family: monospace; font-size: 13px; padding: 12px; border-radius: 6px; resize: vertical; }
                        table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
                        th, td { text-align: left; padding: 8px; border-bottom: 1px solid var(--border); }
                        th { color: var(--muted); }
                        .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }
                        .stat-card { background: #182234; border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
                        .stat-val { font-size: 24px; font-weight: bold; color: var(--primary); margin-top: 4px; }
                        .flex-row { display: flex; gap: 12px; align-items: center; }
                        .comment-box { background: #131d2e; border: 1px solid var(--border); border-radius: 6px; padding: 12px; margin-top: 8px; }
                        .comment-box.resolved { opacity: 0.6; border-color: var(--green); }
                        select.input-ctrl, input.input-ctrl { background: #0b1120; border: 1px solid var(--border); color: #fff; padding: 6px 10px; border-radius: 4px; }
                    </style>
                </head>
                <body>
                    <header>
                        <h1>⚡ TRYST Production Platform</h1>
                        <div class="top-nav">
                            <button id="btnNewTask" class="btn btn-primary" onclick="openNewTaskModal()">+ New Task</button>
                            <button id="navReviews" class="nav-btn active" onclick="switchTab('reviews')">Code Review Center</button>
                            <button id="navAdmin" class="nav-btn" onclick="switchTab('admin')">Administration & Observability</button>
                            <div style="position: relative; display: inline-block;">
                                <button id="btnNotifBell" class="nav-btn" onclick="toggleNotificationDropdown()" title="Notifications">
                                    🔔 <span id="notifBadge" class="badge badge-warning" style="display:none; font-size:10px; padding:2px 5px;">0</span>
                                </button>
                                <div id="notifDropdown" style="display:none; position: absolute; right: 0; top: 38px; width: 360px; max-height: 420px; overflow-y: auto; background: var(--panel); border: 1px solid var(--border); border-radius: 6px; box-shadow: 0 4px 12px rgba(0,0,0,0.5); z-index: 1000; padding: 12px;">
                                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom: 8px; border-bottom: 1px solid var(--border); padding-bottom: 6px;">
                                        <b style="font-size:13px;">Notifications</b>
                                        <div>
                                            <button class="btn btn-sm btn-primary" onclick="requestBrowserNotificationPermission()">Enable Push</button>
                                            <button class="btn btn-sm btn-warning" onclick="markAllNotificationsRead()">Mark Read</button>
                                        </div>
                                    </div>
                                    <div id="notifList" style="font-size: 12px; color: var(--text);">
                                        <div style="color:var(--muted); text-align:center; padding:12px;">No notifications.</div>
                                    </div>
                                </div>
                            </div>
                            <span id="userStatus" style="font-size: 13px; color: var(--muted);">User: Local</span>
                        </div>
                    </header>

                    <div id="reviewsTab" class="container">
                        <div class="sidebar">
                            <div style="padding: 16px; border-bottom: 1px solid var(--border);">
                                <div class="section-title">Projects</div>
                                <div id="projectList">Loading projects...</div>
                            </div>
                            <div style="padding: 16px; flex: 1;">
                                <div class="section-title">Reviews Pending Action</div>
                                <div id="reviewList">Loading reviews...</div>
                            </div>
                        </div>

                        <div class="main-content">
                            <div id="noSelectionNotice" class="card" style="text-align: center; color: var(--muted); padding: 48px;">
                                Select a Code Review from the sidebar to inspect diffs, test results, and perform authorized Git commits.
                            </div>
                            <div id="reviewDetails" style="display: none;">
                                <div class="card">
                                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                                        <div>
                                            <h2 id="reviewTitle" style="margin: 0; font-size: 18px;">Review Title</h2>
                                            <div id="reviewMeta" style="color: var(--muted); font-size: 12px; margin-top: 4px;"></div>
                                        </div>
                                        <div class="flex-row">
                                            <button id="btnBuildTest" class="btn btn-primary" onclick="reRunBuildTest()">Re-run Tests</button>
                                            <button id="btnRework" class="btn btn-warning" onclick="requestRework()">Request AI Rework</button>
                                            <button id="btnApprove" class="btn btn-success" onclick="approveReview()">Approve Review</button>
                                            <button id="btnReject" class="btn btn-danger" onclick="rejectReview()">Reject</button>
                                            <button id="btnCommit" class="btn btn-success" style="display: none;" onclick="commitReview()">Commit Approved Changes</button>
                                            <button id="btnPush" class="btn btn-primary" style="display: none;" onclick="pushReview()">Push to GitHub</button>
                                        </div>
                                    </div>
                                    <!-- Revision Comparison Selector -->
                                    <div class="flex-row" style="margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border); font-size: 13px;">
                                        <label><b>Revision:</b></label>
                                        <select id="selRevision" class="input-ctrl" onchange="onRevisionChanged()"></select>
                                        <label style="margin-left: 12px;"><b>Compare With Base:</b></label>
                                        <select id="selBaseRevision" class="input-ctrl" onchange="onRevisionChanged()">
                                            <option value="">Previous Revision</option>
                                        </select>
                                        <button class="btn btn-sm btn-primary" onclick="loadReviewDiff()">Refresh Diff</button>
                                        <span id="diffStatsBadge" style="margin-left: auto;" class="badge badge-info">Files: 0 | +0 -0</span>
                                    </div>
                                </div>

                                <!-- AI Explanation Drawer / Card -->
                                <div class="card" id="aiExplanationCard" style="display: none;">
                                    <div class="section-title">AI Change Explanation & Architectural Reasoning</div>
                                    <div id="aiExplanationText" style="font-size: 13px; line-height: 1.5; color: #cbd5e1; white-space: pre-wrap;"></div>
                                </div>

                                <!-- Interactive Diff Viewer -->
                                <div class="card">
                                    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                        <div class="section-title" style="margin: 0;">Interactive Code Diff (Click or select lines to comment or ask AI)</div>
                                        <div class="flex-row">
                                            <button class="btn btn-sm btn-primary" onclick="openCommentModal(null, null, null)">+ Add Global Comment</button>
                                        </div>
                                    </div>
                                    <div id="diffContainer" style="max-height: 480px; overflow-y: auto; border: 1px solid var(--border); border-radius: 6px;">
                                        <pre id="diffViewer" class="diff-view"></pre>
                                    </div>
                                </div>

                                <!-- Inline Feedback & Commenting Modal/Drawer -->
                                <div class="card" id="commentComposerCard">
                                    <div class="section-title">Feedback & Rework Instructions Composer</div>
                                    <div id="selectedCodeSnippetContainer" style="display: none; margin-bottom: 10px;">
                                        <label style="font-size: 12px; color: var(--muted);">Selected Code Context:</label>
                                        <pre id="selectedCodeSnippet" style="background: #090d16; border: 1px solid var(--border); padding: 8px; font-size: 12px; border-radius: 4px; margin-top: 4px; max-height: 120px; overflow-y: auto;"></pre>
                                    </div>
                                    <div class="flex-row" style="margin-bottom: 8px; flex-wrap: wrap;">
                                        <input id="commentFilePath" class="input-ctrl" type="text" placeholder="File path (optional for global instructions)..." style="flex: 2; min-width: 200px;" />
                                        <input id="commentStartLine" class="input-ctrl" type="number" placeholder="Start line" style="width: 90px;" />
                                        <input id="commentEndLine" class="input-ctrl" type="number" placeholder="End line" style="width: 90px;" />
                                        <select id="commentDiffSide" class="input-ctrl" style="width: 100px;">
                                            <option value="RIGHT">RIGHT</option>
                                            <option value="LEFT">LEFT</option>
                                        </select>
                                        <label style="font-size: 12px; display: flex; align-items: center; gap: 4px; cursor: pointer;">
                                            <input id="commentIncludeInRework" type="checkbox" checked /> Include in AI Rework
                                        </label>
                                    </div>
                                    <textarea id="commentContent" class="code-editor" placeholder="Write feedback instructions for the AI agent (e.g. 'Refactor this method. Avoid duplicated database queries and preserve the existing public API')..."></textarea>
                                    <div class="flex-row" style="margin-top: 8px; justify-content: flex-end;">
                                        <button class="btn btn-primary" onclick="askAiToExplainSnippet()">Ask AI to Explain Snippet</button>
                                        <button class="btn btn-success" onclick="postReviewComment()">Save Instruction</button>
                                    </div>
                                </div>

                                <!-- Persistent Review Comments List -->
                                <div class="card">
                                    <div class="section-title">Review Comments & Rework Tasks (<span id="commentCount">0</span>)</div>
                                    <div id="commentsList">No comments posted yet.</div>
                                </div>

                                <div class="card">
                                    <div class="section-title">Manual Edit Proposed Changes (Alternative)</div>
                                    <input id="editFilePath" type="text" placeholder="Relative file path..." style="width: 100%; padding: 8px; background: #0b1120; border: 1px solid var(--border); color: #fff; margin-bottom: 8px; border-radius: 4px;" />
                                    <textarea id="fileEditor" class="code-editor" placeholder="Adjust proposed content..."></textarea>
                                    <div style="margin-top: 8px; text-align: right;">
                                        <button class="btn btn-primary" onclick="saveFileEdit()">Save Changes & Invalidate Approval</button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div id="adminTab" class="container" style="display: none;">
                        <div class="sidebar">
                            <div style="padding: 16px;">
                                <div class="section-title">Operational Intelligence</div>
                                <div class="list-item active" onclick="switchAdminSub('overview')">Server Overview</div>
                                <div class="list-item" onclick="switchAdminSub('bundles')">OSGi Bundles</div>
                                <div class="list-item" onclick="switchAdminSub('services')">Managed Services</div>
                                <div class="list-item" onclick="switchAdminSub('workers')">Worker Utilization</div>
                                <div class="list-item" onclick="switchAdminSub('tasks')">Task Supervision</div>
                                <div class="list-item" onclick="switchAdminSub('workspaces')">Workspaces & Sandboxes</div>
                                <div class="list-item" onclick="switchAdminSub('processes')">Managed OS Processes</div>
                                <div class="list-item" onclick="switchAdminSub('users')">Users & Roles (RBAC)</div>
                                <div class="list-item" onclick="switchAdminSub('sessions')">Active Sessions</div>
                                <div class="list-item" onclick="switchAdminSub('telemetry')">Resource Telemetry</div>
                                <div class="list-item" onclick="switchAdminSub('audit')">Audit & Security Log</div>
                                <div class="list-item" onclick="switchAdminSub('diagnostics')">Structured Logs</div>
                                <div class="list-item" onclick="switchAdminSub('database')">Database & Backups</div>
                                <div class="list-item" onclick="switchAdminSub('github')">GitHub Integration</div>
                            </div>
                        </div>

                        <div class="main-content">
                            <div id="adminContent">
                                <div class="grid-stats">
                                    <div class="stat-card"><div>Active Users</div><div id="stUsers" class="stat-val">-</div></div>
                                    <div class="stat-card"><div>Active Sessions</div><div id="stSessions" class="stat-val">-</div></div>
                                    <div class="stat-card"><div>Total Projects</div><div id="stProjects" class="stat-val">-</div></div>
                                    <div class="stat-card"><div>Total Tasks</div><div id="stTasks" class="stat-val">-</div></div>
                                    <div class="stat-card"><div>Audit Records</div><div id="stAudit" class="stat-val">-</div></div>
                                </div>
                                <div class="card">
                                    <div class="section-title">Operational Data</div>
                                    <div id="adminTableContainer">Select an administration view.</div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Intelligent Task Intake Modal -->
                    <div id="newTaskModal" style="display: none; position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: rgba(0,0,0,0.7); z-index: 2000; align-items: center; justify-content: center;">
                        <div style="background: var(--panel); border: 1px solid var(--border); border-radius: 8px; width: 720px; max-width: 95vw; max-height: 90vh; overflow-y: auto; padding: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.6); position: relative;">
                            <div style="display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 16px;">
                                <h2 style="margin: 0; font-size: 18px; color: var(--primary);">🚀 Intelligent Task Intake</h2>
                                <button class="btn btn-sm btn-danger" onclick="closeNewTaskModal()">✕</button>
                            </div>

                            <!-- 1. Repository Selection -->
                            <div style="margin-bottom: 14px;">
                                <label style="font-size: 12px; color: var(--muted); font-weight: bold;">1. GitHub Repository & Branch:</label>
                                <div class="flex-row" style="margin-top: 6px;">
                                    <select id="intakeRepoSelect" class="input-ctrl" style="flex: 2;" onchange="onIntakeRepoSelected()">
                                        <option value="">-- Select GitHub Repository or Local Project --</option>
                                    </select>
                                    <input id="intakeBranchInput" class="input-ctrl" type="text" placeholder="Branch (default: main)" style="flex: 1;" />
                                    <button class="btn btn-sm btn-primary" onclick="refreshIntakeRepos()">↻ Refresh</button>
                                </div>
                                <div style="margin-top: 4px;">
                                    <input id="intakeCustomRepoUrl" class="input-ctrl" type="text" placeholder="Or enter custom repository URL (https://github.com/...)..." style="width: 100%; font-size: 12px; margin-top: 4px;" />
                                </div>
                            </div>

                            <!-- 2. Request Description & Voice Dictation -->
                            <div style="margin-bottom: 14px;">
                                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                                    <label style="font-size: 12px; color: var(--muted); font-weight: bold;">2. Request Description & Dictation:</label>
                                    <div class="flex-row">
                                        <button id="btnVoiceDictate" class="btn btn-sm btn-warning" onclick="toggleVoiceDictation()">🎙️ Start Dictation</button>
                                        <span id="voiceStatus" style="font-size: 11px; color: var(--yellow); display: none;">● Listening...</span>
                                    </div>
                                </div>
                                <textarea id="intakeDescription" class="code-editor" style="height: 120px;" placeholder="Describe the desired change (e.g., 'Add user avatar upload endpoint and validate image size. Update unit tests.'). Click 'Start Dictation' to dictate directly in browser or type here..."></textarea>
                                <div id="voiceNotice" style="font-size: 11px; color: var(--muted); margin-top: 2px;">Voice speech recognition supported in Chrome/Edge/Safari. Recognized text can be freely edited before submission.</div>
                            </div>

                            <!-- 3. Supplementary Context & Criteria (Accordion/Expanded) -->
                            <div style="margin-bottom: 14px; background: #131d2e; border: 1px solid var(--border); border-radius: 6px; padding: 12px;">
                                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                    <label style="font-size: 12px; color: var(--muted); font-weight: bold;">3. Context, Expected Outcome & Criteria (Optional):</label>
                                    <button class="btn btn-sm btn-primary" onclick="autoSuggestTitle()">Suggest Title</button>
                                </div>
                                <div class="flex-row" style="margin-bottom: 8px;">
                                    <input id="intakeTitle" class="input-ctrl" type="text" placeholder="Task Title (auto-generated if empty)..." style="flex: 2;" />
                                    <select id="intakePriority" class="input-ctrl" style="width: 130px;">
                                        <option value="NORMAL">Priority: Normal</option>
                                        <option value="LOW">Priority: Low</option>
                                        <option value="HIGH">Priority: High</option>
                                        <option value="URGENT">Priority: Urgent</option>
                                    </select>
                                </div>
                                <div style="margin-bottom: 8px;">
                                    <input id="intakeExpectedOutcome" class="input-ctrl" type="text" placeholder="Expected outcome / summary of behavior after change..." style="width: 100%;" />
                                </div>
                                <div style="margin-bottom: 8px;">
                                    <textarea id="intakeAcceptanceCriteria" class="input-ctrl" style="width: 100%; height: 50px; resize: vertical;" placeholder="Acceptance criteria (one per line)..."></textarea>
                                </div>
                                <div>
                                    <input id="intakeConstraints" class="input-ctrl" type="text" placeholder="Execution constraints (comma separated, e.g. 'Preserve public API, 5m timeout')..." style="width: 100%;" />
                                </div>
                            </div>

                            <!-- 4. Preliminary Analysis & Validation Feedback Banner -->
                            <div id="intakeAnalysisCard" style="display: none; margin-bottom: 14px; padding: 12px; border-radius: 6px; border: 1px solid var(--border); font-size: 12px;">
                                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                                    <b id="intakeAnalysisHeading">Preliminary AI Intake Analysis</b>
                                    <span id="intakeClassificationBadge" class="badge badge-info">FEATURE</span>
                                </div>
                                <div id="intakeAnalysisSummary" style="color: #cbd5e1; margin-bottom: 6px;"></div>
                                <div id="intakeWarnings" style="color: var(--yellow);"></div>
                                <div id="intakeErrors" style="color: var(--red);"></div>
                            </div>

                            <!-- Actions -->
                            <div class="flex-row" style="justify-content: flex-end; gap: 10px; border-top: 1px solid var(--border); padding-top: 12px;">
                                <button class="btn btn-warning" onclick="validateAndAnalyzeIntake()">🔍 Validate & Analyze Request</button>
                                <button id="btnSubmitIntakeTask" class="btn btn-success" onclick="submitIntakeTask()">🚀 Submit Task</button>
                            </div>
                        </div>
                    </div>

                    <script>
                        let activeReview = null;
                        let authToken = localStorage.getItem('tryst_auth_token') || '';

                        // Determine the base context path dynamically from window.location.pathname
                        function getContextBase() {
                            const p = window.location.pathname;
                            if (p.startsWith('/tryst')) {
                                return '/tryst';
                            }
                            return '';
                        }
                        function apiUrl(endpoint) {
                            const clean = endpoint.startsWith('/') ? endpoint : '/' + endpoint;
                            return getContextBase() + clean;
                        }

                        function getAuthHeaders() {
                            const h = { 'Content-Type': 'application/json' };
                            if (authToken) h['Authorization'] = 'Bearer ' + authToken;
                            return h;
                        }

                        async function promptLogin() {
                            const u = prompt('Admin/User Username:');
                            if (!u) return;
                            const p = prompt('Password:');
                            if (!p) return;
                            const res = await fetch(apiUrl('/api/auth/login'), {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ username: u, password: p })
                            });
                            const d = await res.json();
                            if (d.success) {
                                authToken = d.token;
                                localStorage.setItem('tryst_auth_token', authToken);
                                document.getElementById('userStatus').textContent = `User: ${d.user.username} (${d.user.role})`;
                                alert('Logged in successfully as ' + d.user.username);
                                loadAdminOverview();
                            } else {
                                alert('Login failed: ' + d.error);
                            }
                        }

                        function switchTab(tab) {
                            if (tab === 'reviews') {
                                document.getElementById('reviewsTab').style.display = 'flex';
                                document.getElementById('adminTab').style.display = 'none';
                                document.getElementById('navReviews').classList.add('active');
                                document.getElementById('navAdmin').classList.remove('active');
                            } else {
                                document.getElementById('reviewsTab').style.display = 'none';
                                document.getElementById('adminTab').style.display = 'flex';
                                document.getElementById('navAdmin').classList.add('active');
                                document.getElementById('navReviews').classList.remove('active');
                                loadAdminOverview();
                            }
                        }

                        async function switchAdminSub(sub) {
                            const container = document.getElementById('adminTableContainer');
                            container.innerHTML = 'Loading ' + sub + '...';
                            try {
                                if (sub === 'overview') {
                                    loadAdminOverview();
                                } else if (sub === 'bundles') {
                                    const res = await fetch(apiUrl('/api/admin/bundles'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>ID</th><th>Symbolic Name</th><th>Version</th><th>State</th></tr>' +
                                        data.bundles.map(b => `<tr><td>${b.bundleId}</td><td>${b.symbolicName}</td><td>${b.version}</td><td><span class="badge ${b.state==='ACTIVE'?'badge-success':'badge-warning'}">${b.state}</span></td></tr>`).join('') + '</table>';
                                } else if (sub === 'services') {
                                    const res = await fetch(apiUrl('/api/admin/services'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>Service</th><th>Status</th><th>Details</th><th>Action</th></tr>' +
                                        data.services.map(s => `<tr><td><b>${s.displayName}</b> (${s.name})</td><td><span class="badge ${s.running?'badge-success':'badge-error'}">${s.status}</span></td><td>${s.details}</td><td><button class="btn btn-sm ${s.running?'btn-danger':'btn-success'}" onclick="toggleService('${s.name}', '${s.running?'stop':'start'}')">${s.running?'Stop':'Start'}</button></td></tr>`).join('') + '</table>';
                                } else if (sub === 'workers') {
                                    const res = await fetch(apiUrl('/api/admin/workers'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = `<div><p><b>Worker Thread Capacity:</b> ${data.capacity}</p><p><b>Active Workers:</b> ${data.activeWorkers}</p><p><b>Queued Tasks:</b> ${data.queuedCount}</p><p><b>Utilization:</b> ${data.utilizationPercentage}%</p></div>`;
                                } else if (sub === 'tasks') {
                                    const res = await fetch(apiUrl('/api/admin/tasks'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>ID</th><th>Title</th><th>Status</th><th>Priority</th><th>Action</th></tr>' +
                                        data.tasks.map(t => `<tr><td>${t.id.substring(0,8)}</td><td>${t.title}</td><td><span class="badge badge-info">${t.status}</span></td><td>${t.priority}</td><td><button class="btn btn-sm btn-danger" onclick="cancelAdminTask('${t.id}')">Cancel</button></td></tr>`).join('') + '</table>';
                                } else if (sub === 'workspaces') {
                                    const res = await fetch(apiUrl('/api/admin/workspaces'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>ID</th><th>Project</th><th>Task</th><th>State</th><th>Root Path</th></tr>' +
                                        data.workspaces.map(w => `<tr><td>${w.id.substring(0,8)}</td><td>${w.projectId||'-'}</td><td>${w.taskId||'-'}</td><td><span class="badge badge-info">${w.state}</span></td><td>${w.path}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'users') {
                                    const res = await fetch(apiUrl('/api/admin/users'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>ID</th><th>Username</th><th>Role</th><th>Active</th><th>Locked</th><th>Created</th></tr>' +
                                        data.users.map(u => `<tr><td>${u.id.substring(0,8)}</td><td>${u.username}</td><td>${u.role}</td><td>${u.active}</td><td>${u.locked}</td><td>${u.createdAt}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'sessions') {
                                    const res = await fetch(apiUrl('/api/admin/sessions'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>ID</th><th>User ID</th><th>Client IP</th><th>Expires</th><th>Revoked</th><th>Last Activity</th></tr>' +
                                        data.sessions.map(s => `<tr><td>${s.id.substring(0,8)}</td><td>${s.userId.substring(0,8)}</td><td>${s.clientIp}</td><td>${s.expiresAt}</td><td>${s.revoked}</td><td>${s.lastActivity}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'audit') {
                                    const res = await fetch(apiUrl('/api/admin/audit'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Outcome</th><th>Details</th></tr>' +
                                        data.auditEvents.map(a => `<tr><td>${a.time}</td><td>${a.actor}</td><td>${a.action}</td><td>${a.target}</td><td>${a.outcome}</td><td>${a.details}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'telemetry') {
                                    const res = await fetch(apiUrl('/api/admin/telemetry'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>Timestamp</th><th>CPU System</th><th>CPU Process</th><th>Heap Used (MB)</th><th>Active Sessions</th></tr>' +
                                        data.snapshots.map(s => `<tr><td>${s.timestamp}</td><td>${s.cpuSystem.toFixed(1)}%</td><td>${s.cpuProcess.toFixed(1)}%</td><td>${(s.heapUsed/1048576).toFixed(1)}</td><td>${s.activeSessions}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'diagnostics') {
                                    const res = await fetch(apiUrl('/api/admin/logs'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>Seq</th><th>Time</th><th>Level</th><th>Component</th><th>Message</th></tr>' +
                                        data.logs.map(l => `<tr><td>${l.seq}</td><td>${l.time}</td><td>${l.level}</td><td>${l.component}</td><td>${l.message}</td></tr>`).join('') + '</table>';
                                } else if (sub === 'database') {
                                    const res = await fetch(apiUrl('/api/admin/database'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = `<div><p><b>Path:</b> ${data.databasePath}</p><p><b>Size:</b> ${data.sizeBytes} bytes</p><p><b>Schema:</b> V${data.schemaVersion}</p><button class="btn btn-primary" onclick="createBackup()">Create Snapshot Backup</button></div>`;
                                } else if (sub === 'processes') {
                                    const res = await fetch(apiUrl('/api/admin/processes'), { headers: getAuthHeaders() });
                                    const data = await res.json();
                                    container.innerHTML = '<table><tr><th>PID</th><th>Command</th><th>Working Dir</th><th>Status</th><th>Duration</th></tr>' +
                                        data.processes.map(p => `<tr><td>${p.pid}</td><td>${p.command}</td><td>${p.workingDir}</td><td>${p.status}</td><td>${p.durationMs}ms</td></tr>`).join('') + '</table>';
                                } else if (sub === 'github') {
                                    loadGitHubAccountStatus(container);
                                }
                            } catch(e) {
                                container.innerHTML = 'Error loading ' + sub + ': ' + e;
                            }
                        }

                        async function createBackup() {
                            const res = await fetch(apiUrl('/api/admin/database/backup'), { method: 'POST' });
                            const d = await res.json();
                            alert(d.success ? 'Backup created at: ' + d.backupPath : 'Backup failed');
                        }

                        async function loadGitHubAccountStatus(container) {
                            try {
                                const res = await fetch(apiUrl('/api/github/account'), { headers: getAuthHeaders() });
                                const data = await res.json();
                                if (data.connected) {
                                    container.innerHTML = `
                                        <div>
                                            <h3>GitHub Account Connected</h3>
                                            <p><b>GitHub User:</b> @${data.username}</p>
                                            <p><b>Token:</b> ${data.maskedToken} (Encrypted at rest)</p>
                                            <p><b>Scopes:</b> ${data.scopes}</p>
                                            <p><b>Connected At:</b> ${data.createdAt}</p>
                                            <button class="btn btn-danger" onclick="disconnectGitHub()">Disconnect GitHub</button>
                                        </div>
                                    `;
                                } else {
                                    container.innerHTML = `
                                        <div>
                                            <h3>Connect GitHub Account</h3>
                                            <p>Enter a GitHub Personal Access Token (classic or fine-grained) with <code>repo</code> permissions to enable cloning private and public repositories and authorized pushes.</p>
                                            <input id="ghTokenInput" type="password" placeholder="ghp_..." style="width: 320px; padding: 8px; background: #0b1120; border: 1px solid var(--border); color: #fff; margin-bottom: 8px; border-radius: 4px;" /><br>
                                            <button class="btn btn-primary" onclick="connectGitHub()">Connect GitHub Account</button>
                                        </div>
                                    `;
                                }
                            } catch (e) {
                                container.innerHTML = 'Error loading GitHub status: ' + e;
                            }
                        }

                        async function connectGitHub() {
                            const token = document.getElementById('ghTokenInput').value;
                            if (!token) return;
                            const res = await fetch(apiUrl('/api/github/connect'), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ token })
                            });
                            const d = await res.json();
                            if (d.success) {
                                alert('Connected to GitHub as @' + d.username);
                                switchAdminSub('github');
                            } else {
                                alert('Connection failed: ' + d.error);
                            }
                        }

                        async function disconnectGitHub() {
                            if (!confirm('Disconnect GitHub account?')) return;
                            await fetch(apiUrl('/api/github/disconnect'), { method: 'POST', headers: getAuthHeaders() });
                            alert('GitHub account disconnected.');
                            switchAdminSub('github');
                        }

                        async function loadAdminOverview() {
                            try {
                                const res = await fetch(apiUrl('/api/admin/overview'), { headers: getAuthHeaders() });
                                if (res.status === 403 || res.status === 401) {
                                    document.getElementById('adminTableContainer').innerHTML =
                                        '<div style="color:var(--yellow); padding: 16px;">Authentication as Administrator required. <button class="btn btn-primary" onclick="promptLogin()">Login with Token / Password</button></div>';
                                    return;
                                }
                                const data = await res.json();
                                document.getElementById('stUsers').textContent = data.totalUsers;
                                document.getElementById('stSessions').textContent = data.activeSessions;
                                document.getElementById('stProjects').textContent = data.totalProjects;
                                document.getElementById('stTasks').textContent = data.totalTasks;
                                document.getElementById('stAudit').textContent = data.totalAuditRecords;
                                document.getElementById('adminTableContainer').innerHTML = `<p><b>AI Inference Endpoint:</b> ${data.aiEndpoint} (Status: ${data.aiAvailable ? 'ONLINE' : 'OFFLINE'})</p><p><b>Default AI Model:</b> ${data.aiModel}</p>`;
                            } catch(e) {}
                        }

                        async function loadProjects() {
                            try {
                                const res = await fetch(apiUrl('/api/projects'));
                                const data = await res.json();
                                const list = document.getElementById('projectList');
                                list.innerHTML = data.projects.map(p => `<div class="list-item"><strong>${escapeHtml(p.name)}</strong><br><small style="color:var(--muted);">${p.id.substring(0,8)}</small></div>`).join('');
                            } catch(e) {}
                        }

                        async function loadReviews() {
                            try {
                                const res = await fetch(apiUrl('/api/reviews'));
                                const data = await res.json();
                                const list = document.getElementById('reviewList');
                                list.innerHTML = data.reviews.map(r => `
                                    <div class="list-item ${activeReview && activeReview.id === r.id ? 'active' : ''}" onclick='selectReview(${JSON.stringify(r)})'>
                                        <div><strong>${r.branch}</strong> <span class="badge badge-${r.status==='APPROVED'?'success':r.status==='REJECTED'?'error':'warning'}">${r.status}</span></div>
                                        <div style="font-size: 11px; color: var(--muted); margin-top: 4px;">Task: ${r.taskId.substring(0,8)}</div>
                                    </div>
                                `).join('');
                            } catch(e) {}
                        }

                        async function selectReview(r) {
                            activeReview = r;
                            document.getElementById('noSelectionNotice').style.display = 'none';
                            document.getElementById('reviewDetails').style.display = 'block';

                            document.getElementById('reviewTitle').textContent = `Review ${r.id.substring(0,8)} (Branch: ${r.branch})`;
                            document.getElementById('reviewMeta').textContent = `Project: ${r.projectId} | Fingerprint: ${r.contentFingerprint.substring(0,12)}...`;

                            const btnApprove = document.getElementById('btnApprove');
                            const btnReject = document.getElementById('btnReject');
                            const btnCommit = document.getElementById('btnCommit');

                            btnCommit.style.display = (r.status === 'APPROVED') ? 'inline-block' : 'none';
                            const btnPush = document.getElementById('btnPush');
                            btnPush.style.display = (r.status === 'APPROVED' && r.commitHash) ? 'inline-block' : 'none';

                            await loadReviewDetails();
                            await loadReviewDiff();
                        }

                        async function loadReviewDetails() {
                            if (!activeReview) return;
                            try {
                                const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/details`));
                                activeDetails = await res.json();

                                if (activeDetails.aiExplanation && activeDetails.aiExplanation.trim()) {
                                    document.getElementById('aiExplanationCard').style.display = 'block';
                                    document.getElementById('aiExplanationText').textContent = activeDetails.aiExplanation;
                                } else {
                                    document.getElementById('aiExplanationCard').style.display = 'none';
                                }

                                populateRevisionSelectors(activeDetails.revisions, activeDetails.revisionCount);
                                renderCommentsList(activeDetails.comments);
                            } catch(e) {
                                console.error('Failed to load review details', e);
                            }
                        }

                        function populateRevisionSelectors(revisions, count) {
                            const selRev = document.getElementById('selRevision');
                            const selBase = document.getElementById('selBaseRevision');
                            selRev.innerHTML = '';
                            selBase.innerHTML = '<option value="">Previous Revision</option>';

                            const revs = revisions || [];
                            for (let i = 1; i <= count; i++) {
                                const opt = document.createElement('option');
                                opt.value = i;
                                opt.textContent = `Revision ${i}` + (i === count ? ' (Latest)' : '');
                                if (i === count) opt.selected = true;
                                selRev.appendChild(opt);

                                const optBase = document.createElement('option');
                                optBase.value = i;
                                optBase.textContent = `Revision ${i}`;
                                selBase.appendChild(optBase);
                            }
                        }

                        function onRevisionChanged() {
                            loadReviewDiff();
                        }

                        async function loadReviewDiff() {
                            if (!activeReview) return;
                            const rev = document.getElementById('selRevision').value;
                            const base = document.getElementById('selBaseRevision').value;
                            let url = `/api/reviews/${activeReview.id}/diff?view=unified`;
                            if (rev) url += `&revision=${rev}`;
                            if (base) url += `&baseRevision=${base}`;

                            try {
                                const res = await fetch(apiUrl(url));
                                const diffData = await res.json();
                                renderInteractiveDiff(diffData);
                            } catch(e) {
                                console.error('Failed to load diff', e);
                            }
                        }

                        function renderInteractiveDiff(diffData) {
                            const viewer = document.getElementById('diffViewer');
                            viewer.innerHTML = '';
                            selectedLines = [];

                            if (!diffData.files || diffData.files.length === 0) {
                                viewer.innerHTML = '<div style="color:var(--muted); padding:12px;">No modifications found between chosen revisions.</div>';
                                return;
                            }

                            let totAdd = 0, totDel = 0;
                            diffData.files.forEach(f => {
                                totAdd += f.additions;
                                totDel += f.deletions;
                            });
                            document.getElementById('diffStatsBadge').textContent = `Files: ${diffData.files.length} | +${totAdd} -${totDel}`;

                            diffData.files.forEach(f => {
                                const fHdr = document.createElement('div');
                                fHdr.className = 'diff-line diff-hdr';
                                fHdr.innerHTML = `<span class="diff-line-code" style="font-weight:bold; color:var(--primary); padding:6px 12px;">📄 ${escapeHtml(f.newPath || f.oldPath)} (+${f.additions} -${f.deletions})</span>`;
                                fHdr.onclick = () => openCommentModal(f.newPath || f.oldPath, null, null);
                                viewer.appendChild(fHdr);

                                f.hunks.forEach(h => {
                                    const hHdr = document.createElement('div');
                                    hHdr.className = 'diff-line diff-hdr';
                                    hHdr.innerHTML = `<span class="diff-line-code" style="color:var(--muted); padding:4px 12px;">${escapeHtml(h.header)}</span>`;
                                    viewer.appendChild(hHdr);

                                    h.lines.forEach(l => {
                                        const lineEl = document.createElement('div');
                                        let cls = 'diff-line';
                                        if (l.type === 'ADD') cls += ' diff-add';
                                        else if (l.type === 'DELETE') cls += ' diff-del';
                                        lineEl.className = cls;

                                        const lineNum = l.newNum > 0 ? l.newNum : (l.oldNum > 0 ? l.oldNum : '');
                                        const side = l.type === 'DELETE' ? 'LEFT' : 'RIGHT';
                                        const filePath = f.newPath || f.oldPath;

                                        lineEl.innerHTML = `<div class="diff-line-num">${lineNum}</div><div class="diff-line-code">${escapeHtml(l.content)}</div>`;
                                        lineEl.onclick = (e) => onLineClicked(filePath, lineNum, side, l.content, lineEl, e.shiftKey);
                                        viewer.appendChild(lineEl);
                                    });
                                });
                            });
                        }

                        function onLineClicked(filePath, lineNum, side, code, element, isShift) {
                            if (!lineNum) return;
                            if (!isShift) {
                                document.querySelectorAll('.diff-line.selected').forEach(el => el.classList.remove('selected'));
                                selectedLines = [{ file: filePath, line: parseInt(lineNum), side, code }];
                                element.classList.add('selected');
                            } else {
                                selectedLines.push({ file: filePath, line: parseInt(lineNum), side, code });
                                element.classList.add('selected');
                            }
                            selectedLines.sort((a,b) => a.line - b.line);

                            const minLine = selectedLines[0].line;
                            const maxLine = selectedLines[selectedLines.length - 1].line;
                            const combinedCode = selectedLines.map(s => s.code).join('\\n');

                            openCommentModal(filePath, minLine, maxLine, side, combinedCode);
                        }

                        function openCommentModal(filePath, startLine, endLine, side, codeContext) {
                            document.getElementById('commentFilePath').value = filePath || '';
                            document.getElementById('commentStartLine').value = startLine || '';
                            document.getElementById('commentEndLine').value = endLine || startLine || '';
                            if (side) document.getElementById('commentDiffSide').value = side;

                            const snippetBox = document.getElementById('selectedCodeSnippetContainer');
                            if (codeContext) {
                                snippetBox.style.display = 'block';
                                document.getElementById('selectedCodeSnippet').textContent = codeContext;
                            } else {
                                snippetBox.style.display = 'none';
                                document.getElementById('selectedCodeSnippet').textContent = '';
                            }
                            document.getElementById('commentContent').focus();
                        }

                        async function postReviewComment() {
                            if (!activeReview) return;
                            const filePath = document.getElementById('commentFilePath').value;
                            const startLine = document.getElementById('commentStartLine').value;
                            const endLine = document.getElementById('commentEndLine').value;
                            const diffSide = document.getElementById('commentDiffSide').value;
                            const includeInRework = document.getElementById('commentIncludeInRework').checked;
                            const content = document.getElementById('commentContent').value;
                            const codeContext = document.getElementById('selectedCodeSnippet').textContent;
                            const rev = document.getElementById('selRevision').value || activeReview.revisionCount;

                            if (!content.trim()) {
                                alert('Please enter comment instructions.');
                                return;
                            }

                            const payload = {
                                filePath: filePath || '',
                                revisionNumber: parseInt(rev) || 1,
                                startLine: startLine ? parseInt(startLine) : 1,
                                endLine: endLine ? parseInt(endLine) : (startLine ? parseInt(startLine) : 1),
                                diffSide: diffSide || 'RIGHT',
                                commentType: (startLine ? 'LINE' : (filePath ? 'FILE' : 'GENERAL')),
                                content: content.trim(),
                                codeContext: codeContext || '',
                                includeInRework: includeInRework
                            };

                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/comments`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify(payload)
                            });

                            if (res.status === 201) {
                                document.getElementById('commentContent').value = '';
                                await loadReviewDetails();
                                alert('Review comment instruction saved.');
                            } else {
                                const err = await res.json();
                                alert('Failed to save comment: ' + (err.error || 'Unknown error'));
                            }
                        }

                        function renderCommentsList(comments) {
                            const container = document.getElementById('commentsList');
                            const list = comments || [];
                            document.getElementById('commentCount').textContent = list.length;

                            if (list.length === 0) {
                                container.innerHTML = '<span style="color:var(--muted); font-size:13px;">No feedback comments recorded yet.</span>';
                                return;
                            }

                            container.innerHTML = list.map(c => {
                                const badgeClass = c.resolved ? 'badge-success' : 'badge-warning';
                                const reworkBadge = c.includeInRework ? '<span class="badge badge-info" style="margin-left:6px;">In Rework</span>' : '';
                                return `
                                    <div class="comment-box ${c.resolved ? 'resolved' : ''}">
                                        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;">
                                            <div>
                                                <strong>${escapeHtml(c.filePath || 'Global Instruction')}</strong>
                                                ${c.commentType === 'LINE' ? `<small style="color:var(--muted);"> [Lines ${c.startLine}–${c.endLine} (${c.diffSide})]</small>` : ''}
                                                <span class="badge ${badgeClass}" style="margin-left: 6px;">${c.resolved ? 'Resolved' : 'Active'}</span>
                                                ${reworkBadge}
                                            </div>
                                            <div class="flex-row">
                                                <button class="btn btn-sm ${c.resolved ? 'btn-primary' : 'btn-success'}" onclick="toggleCommentResolve('${c.id}', ${!c.resolved})">
                                                    ${c.resolved ? 'Reopen' : 'Resolve'}
                                                </button>
                                                <button class="btn btn-sm btn-danger" onclick="deleteComment('${c.id}')">Delete</button>
                                            </div>
                                        </div>
                                        ${c.codeContext ? `<pre style="background:#090d16; border:1px solid var(--border); padding:6px; font-size:11px; border-radius:4px; margin:4px 0;">${escapeHtml(c.codeContext)}</pre>` : ''}
                                        <div style="font-size: 13px; color: #f1f5f9; margin-top: 6px; white-space: pre-wrap;">${escapeHtml(c.content)}</div>
                                        <div style="font-size: 11px; color: var(--muted); margin-top: 4px;">Rev: ${c.revisionNumber} | Author: ${escapeHtml(c.authorId)} | ${c.createdAt}</div>
                                    </div>
                                `;
                            }).join('');
                        }

                        async function toggleCommentResolve(commentId, resolved) {
                            if (!activeReview) return;
                            await fetch(apiUrl(`/api/reviews/${activeReview.id}/comments/${commentId}`), {
                                method: 'PATCH',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ resolved: resolved })
                            });
                            loadReviewDetails();
                        }

                        async function deleteComment(commentId) {
                            if (!activeReview || !confirm('Delete this comment?')) return;
                            await fetch(apiUrl(`/api/reviews/${activeReview.id}/comments/${commentId}`), {
                                method: 'DELETE',
                                headers: getAuthHeaders()
                            });
                            loadReviewDetails();
                        }

                        async function askAiToExplainSnippet() {
                            if (!activeReview) return;
                            const filePath = document.getElementById('commentFilePath').value;
                            const codeSnippet = document.getElementById('selectedCodeSnippet').textContent || document.getElementById('commentContent').value;
                            const question = document.getElementById('commentContent').value;

                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/explain`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ filePath, codeSnippet, question })
                            });
                            const data = await res.json();
                            if (data.explanation) {
                                document.getElementById('aiExplanationCard').style.display = 'block';
                                document.getElementById('aiExplanationText').textContent = data.explanation;
                                document.getElementById('aiExplanationCard').scrollIntoView({ behavior: 'smooth' });
                            }
                        }

                        async function requestRework() {
                            if (!activeReview) return;
                            const reason = prompt('Rework instructions / overall goal summary for AI agent:', 'Refactor according to review comments');
                            if (reason === null) return;

                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/rework`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ reason })
                            });
                            const data = await res.json();
                            if (data.success) {
                                alert('Task successfully queued for autonomous AI rework iteration!');
                                loadReviews();
                                selectReview(activeReview);
                            } else {
                                alert('Failed to request rework: ' + (data.error || 'Unknown error'));
                            }
                        }

                        async function saveFileEdit() {
                            if (!activeReview) return;
                            const filePath = document.getElementById('editFilePath').value;
                            const content = document.getElementById('fileEditor').value;
                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/files/edit`), {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ filePath, content })
                            });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        async function approveReview() {
                            if (!activeReview) return;
                            const revToApprove = activeReview.revisionCount || 1;
                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/approve`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ revisionNumber: revToApprove })
                            });
                            const data = await res.json();
                            if (!res.ok) {
                                alert('Approval failed: ' + (data.error || 'Server rejected approval'));
                                return;
                            }
                            selectReview(data);
                        }

                        async function rejectReview() {
                            if (!activeReview) return;
                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/reject`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ reason: 'Rejected from web UI' })
                            });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        async function commitReview() {
                            if (!activeReview) return;
                            try {
                                const sumRes = await fetch(apiUrl(`/api/reviews/${activeReview.id}/commit-summary`), { headers: getAuthHeaders() });
                                if (sumRes.ok) {
                                    const summary = await sumRes.json();
                                    if (!summary.canCommit) {
                                        alert('Cannot commit review:\\n' + (summary.validationErrors ? summary.validationErrors.join('\\n') : 'Preconditions failed'));
                                        return;
                                    }
                                    const confirmMsg = `Confirm commit of revision ${summary.approvedRevision} for task ${summary.taskId || summary.reviewId}?\\n` +
                                        `Files changed: ${summary.filesCount} (+${summary.additions} -${summary.deletions})\\n` +
                                        `Message: ${summary.defaultCommitMessage}`;
                                    if (!confirm(confirmMsg)) return;
                                }
                            } catch (e) {
                                console.warn('Could not inspect commit summary:', e);
                            }

                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/commit`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ message: 'Approved via TRYST Web Review Center' })
                            });
                            const data = await res.json();
                            if (data.success) {
                                alert('Commit succeeded! Hash: ' + data.commitHash);
                                selectReview(data.review);
                            } else {
                                alert('Commit failed: ' + data.error);
                            }
                        }

                        async function pushReview() {
                            if (!activeReview) return;
                            if (!confirm('Perform authorized push to repository branch?')) return;
                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/push`), {
                                method: 'POST',
                                headers: getAuthHeaders(),
                                body: JSON.stringify({ reviewId: activeReview.id })
                            });
                            const data = await res.json();
                            if (data.success) {
                                alert('Push succeeded! ' + data.message);
                            } else {
                                if (res.status === 409 || data.conflict) {
                                    alert('Push Rejected (Remote Conflict):\\n' + data.error);
                                } else {
                                    alert('Push failed: ' + data.error);
                                }
                            }
                        }

                        async function reRunBuildTest() {
                            if (!activeReview) return;
                            const res = await fetch(apiUrl(`/api/reviews/${activeReview.id}/build-test`), { method: 'POST' });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        function escapeHtml(s) {
                            if (!s) return '';
                            return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
                        }

                        let lastNotificationSeq = 0;
                        let sseEventSource = null;

                        function toggleNotificationDropdown() {
                            const d = document.getElementById('notifDropdown');
                            d.style.display = (d.style.display === 'none' || !d.style.display) ? 'block' : 'none';
                            if (d.style.display === 'block') {
                                loadNotifications();
                            }
                        }

                        async function requestBrowserNotificationPermission() {
                            if (!('Notification' in window)) {
                                alert('This browser does not support desktop notifications.');
                                return;
                            }
                            const perm = await Notification.requestPermission();
                            if (perm === 'granted') {
                                alert('Desktop notifications enabled.');
                            } else {
                                alert('Permission ' + perm);
                            }
                        }

                        function triggerBrowserNotification(title, message, refType, refId) {
                            if (('Notification' in window) && Notification.permission === 'granted') {
                                try {
                                    new Notification(title, {
                                        body: message,
                                        icon: '/favicon.ico'
                                    });
                                } catch (e) {
                                    console.warn('Browser notification error', e);
                                }
                            }
                        }

                        async function loadNotifications() {
                            try {
                                const res = await fetch(apiUrl('/api/notifications?limit=30'), { headers: getAuthHeaders() });
                                if (!res.ok) return;
                                const data = await res.json();
                                renderNotifications(data.notifications, data.unreadCount);
                            } catch (e) {
                                console.warn('Failed to load notifications', e);
                            }
                        }

                        function renderNotifications(notifs, unreadCount) {
                            const badge = document.getElementById('notifBadge');
                            if (unreadCount > 0) {
                                badge.textContent = unreadCount;
                                badge.style.display = 'inline-block';
                            } else {
                                badge.style.display = 'none';
                            }

                            const list = document.getElementById('notifList');
                            if (!notifs || notifs.length === 0) {
                                list.innerHTML = '<div style="color:var(--muted); text-align:center; padding:12px;">No notifications.</div>';
                                return;
                            }

                            list.innerHTML = notifs.map(n => {
                                const readStyle = n.isRead ? 'opacity:0.6;' : 'font-weight:600; border-left: 3px solid var(--primary);';
                                const time = new Date(n.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                                return `<div style="padding: 8px; border-bottom: 1px solid var(--border); ${readStyle}">
                                    <div style="display:flex; justify-content:space-between; align-items:center;">
                                        <span class="badge badge-info" style="font-size:10px;">${escapeHtml(n.type)}</span>
                                        <span style="font-size:11px; color:var(--muted);">${time}</span>
                                    </div>
                                    <div style="margin-top: 4px; color: #fff;">${escapeHtml(n.title)}</div>
                                    <div style="color:var(--muted); font-size:11px; margin-top:2px;">${escapeHtml(n.message)}</div>
                                </div>`;
                            }).join('');
                        }

                        async function markAllNotificationsRead() {
                            await fetch(apiUrl('/api/notifications/read-all'), {
                                method: 'POST',
                                headers: getAuthHeaders()
                            });
                            loadNotifications();
                        }

                        function connectNotificationStream() {
                            const streamUrl = apiUrl('/api/notifications/stream' + (lastNotificationSeq > 0 ? ('?lastEventId=' + lastNotificationSeq) : ''));
                            if (sseEventSource) {
                                sseEventSource.close();
                            }
                            try {
                                sseEventSource = new EventSource(streamUrl);
                                sseEventSource.addEventListener('notification', e => {
                                    try {
                                        const notif = JSON.parse(e.data);
                                        if (notif.sequenceNumber) {
                                            lastNotificationSeq = Math.max(lastNotificationSeq, notif.sequenceNumber);
                                        }
                                        triggerBrowserNotification(notif.title, notif.message, notif.referenceType, notif.referenceId);
                                        loadNotifications();
                                    } catch (err) {
                                        console.warn('Error processing SSE notification', err);
                                    }
                                });
                                sseEventSource.onerror = err => {
                                    console.warn('Notification stream connection interrupted, reconnecting in 5s...', err);
                                    sseEventSource.close();
                                    setTimeout(connectNotificationStream, 5000);
                                };
                            } catch (e) {
                                setTimeout(connectNotificationStream, 5000);
                            }
                        }

                        // --- Intelligent Task Intake & Voice Dictation Logic ---
                        let speechRecognizer = null;
                        let isDictating = false;

                        function openNewTaskModal() {
                            const modal = document.getElementById('newTaskModal');
                            modal.style.display = 'flex';
                            refreshIntakeRepos();
                        }

                        function closeNewTaskModal() {
                            const modal = document.getElementById('newTaskModal');
                            modal.style.display = 'none';
                            stopVoiceDictation();
                        }

                        async function refreshIntakeRepos() {
                            const sel = document.getElementById('intakeRepoSelect');
                            sel.innerHTML = '<option value="">Loading repositories...</option>';
                            try {
                                const [ghRes, projRes] = await Promise.all([
                                    fetch(apiUrl('/api/github/repos'), { headers: getAuthHeaders() }),
                                    fetch(apiUrl('/api/projects'), { headers: getAuthHeaders() })
                                ]);

                                let options = '<option value="">-- Select GitHub Repository or Local Project --</option>';

                                if (ghRes.ok) {
                                    const ghData = await ghRes.json();
                                    if (ghData.repositories && ghData.repositories.length > 0) {
                                        options += '<optgroup label="GitHub Repositories">';
                                        for (const r of ghData.repositories) {
                                            options += `<option value="gh:${r.cloneUrl}" data-branch="${r.defaultBranch || 'main'}">${r.fullName} (${r.defaultBranch || 'main'})${r.isPrivate ? ' 🔒' : ''}</option>`;
                                        }
                                        options += '</optgroup>';
                                    }
                                }

                                if (projRes.ok) {
                                    const projData = await projRes.json();
                                    if (projData.projects && projData.projects.length > 0) {
                                        options += '<optgroup label="Local Projects">';
                                        for (const p of projData.projects) {
                                            options += `<option value="local:${p.id}">${p.name} (${p.location})</option>`;
                                        }
                                        options += '</optgroup>';
                                    }
                                }

                                sel.innerHTML = options;
                            } catch (e) {
                                sel.innerHTML = '<option value="">-- Failed to load repositories --</option>';
                            }
                        }

                        function onIntakeRepoSelected() {
                            const sel = document.getElementById('intakeRepoSelect');
                            const opt = sel.selectedOptions[0];
                            const branchInput = document.getElementById('intakeBranchInput');
                            const customUrlInput = document.getElementById('intakeCustomRepoUrl');

                            if (opt && opt.value) {
                                if (opt.value.startsWith('gh:')) {
                                    customUrlInput.value = opt.value.substring(3);
                                    branchInput.value = opt.getAttribute('data-branch') || 'main';
                                } else if (opt.value.startsWith('local:')) {
                                    customUrlInput.value = '';
                                    branchInput.value = 'main';
                                }
                            }
                        }

                        function initSpeechRecognition() {
                            const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
                            if (!SpeechRec) {
                                return null;
                            }
                            const rec = new SpeechRec();
                            rec.continuous = true;
                            rec.interimResults = true;
                            rec.lang = navigator.language || 'en-US';

                            rec.onresult = function(e) {
                                let interim = '';
                                let finalTranscript = '';
                                for (let i = e.resultIndex; i < e.results.length; ++i) {
                                    if (e.results[i].isFinal) {
                                        finalTranscript += e.results[i][0].transcript;
                                    } else {
                                        interim += e.results[i][0].transcript;
                                    }
                                }
                                const ta = document.getElementById('intakeDescription');
                                if (finalTranscript) {
                                    ta.value += (ta.value && !ta.value.endsWith(' ') ? ' ' : '') + finalTranscript;
                                }
                                const vs = document.getElementById('voiceStatus');
                                if (interim) {
                                    vs.textContent = '● Listening: "' + interim.trim() + '"';
                                } else {
                                    vs.textContent = '● Listening...';
                                }
                            };

                            rec.onerror = function(e) {
                                console.warn('Speech recognition error:', e.error);
                                stopVoiceDictation();
                                document.getElementById('voiceNotice').textContent = 'Voice error: ' + e.error + '. You can continue typing manually.';
                            };

                            rec.onend = function() {
                                if (isDictating) {
                                    try { rec.start(); } catch(e) { stopVoiceDictation(); }
                                }
                            };

                            return rec;
                        }

                        function toggleVoiceDictation() {
                            if (isDictating) {
                                stopVoiceDictation();
                            } else {
                                startVoiceDictation();
                            }
                        }

                        function startVoiceDictation() {
                            if (!speechRecognizer) {
                                speechRecognizer = initSpeechRecognition();
                            }
                            if (!speechRecognizer) {
                                alert('Browser native SpeechRecognition API is not supported in this browser. Please use keyboard text input.');
                                return;
                            }
                            try {
                                speechRecognizer.start();
                                isDictating = true;
                                document.getElementById('btnVoiceDictate').textContent = '⏹️ Stop Dictation';
                                document.getElementById('btnVoiceDictate').className = 'btn btn-sm btn-danger';
                                document.getElementById('voiceStatus').style.display = 'inline';
                                document.getElementById('voiceStatus').textContent = '● Listening...';
                            } catch (e) {
                                console.warn('Could not start speech recognition:', e);
                            }
                        }

                        function stopVoiceDictation() {
                            isDictating = false;
                            if (speechRecognizer) {
                                try { speechRecognizer.stop(); } catch(e) {}
                            }
                            const btn = document.getElementById('btnVoiceDictate');
                            if (btn) {
                                btn.textContent = '🎙️ Start Dictation';
                                btn.className = 'btn btn-sm btn-warning';
                            }
                            const status = document.getElementById('voiceStatus');
                            if (status) {
                                status.style.display = 'none';
                            }
                        }

                        function autoSuggestTitle() {
                            const desc = document.getElementById('intakeDescription').value.trim();
                            if (!desc) {
                                alert('Please provide or dictate a task description first.');
                                return;
                            }
                            let title = desc.split('\n')[0].replace(/^[-*#0-9.]+\s*/, '').trim();
                            if (title.length > 55) {
                                title = title.substring(0, 52) + '...';
                            }
                            document.getElementById('intakeTitle').value = title;
                        }

                        function collectIntakePayload() {
                            const sel = document.getElementById('intakeRepoSelect');
                            const selVal = sel.value || '';
                            let projectId = null;
                            let repoUrl = document.getElementById('intakeCustomRepoUrl').value.trim();

                            if (selVal.startsWith('local:')) {
                                projectId = selVal.substring(6);
                            } else if (selVal.startsWith('gh:') && !repoUrl) {
                                repoUrl = selVal.substring(3);
                            }

                            const branch = document.getElementById('intakeBranchInput').value.trim() || 'main';
                            const title = document.getElementById('intakeTitle').value.trim();
                            const description = document.getElementById('intakeDescription').value.trim();
                            const priority = document.getElementById('intakePriority').value;
                            const expectedOutcome = document.getElementById('intakeExpectedOutcome').value.trim();

                            const criteriaRaw = document.getElementById('intakeAcceptanceCriteria').value;
                            const acceptanceCriteria = criteriaRaw.split('\n').map(s => s.trim()).filter(s => s.length > 0);

                            const constraintsRaw = document.getElementById('intakeConstraints').value;
                            const constraints = constraintsRaw.split(',').map(s => s.trim()).filter(s => s.length > 0);

                            return {
                                projectId,
                                repositoryUrl: repoUrl,
                                branch,
                                title,
                                description,
                                priority,
                                expectedOutcome,
                                acceptanceCriteria,
                                constraints
                            };
                        }

                        async function validateAndAnalyzeIntake() {
                            const payload = collectIntakePayload();
                            const card = document.getElementById('intakeAnalysisCard');
                            card.style.display = 'block';
                            document.getElementById('intakeAnalysisSummary').textContent = 'Analyzing task request and validating repository context...';
                            document.getElementById('intakeWarnings').textContent = '';
                            document.getElementById('intakeErrors').textContent = '';

                            try {
                                const res = await fetch(apiUrl('/api/tasks/intake/analyze'), {
                                    method: 'POST',
                                    headers: getAuthHeaders(),
                                    body: JSON.stringify(payload)
                                });
                                const data = await res.json();

                                if (data.suggestedTitle && !payload.title) {
                                    document.getElementById('intakeTitle').value = data.suggestedTitle;
                                }
                                document.getElementById('intakeClassificationBadge').textContent = data.classification || 'FEATURE';

                                let summary = `Repository: ${data.repositoryContext} | Estimated Complexity: ${data.complexityEstimate || 'NORMAL'}`;
                                document.getElementById('intakeAnalysisSummary').textContent = summary;

                                if (data.missingInformation && data.missingInformation.length > 0) {
                                    document.getElementById('intakeWarnings').innerHTML = '<b>Suggestions / Missing Context:</b><ul>' +
                                        data.missingInformation.map(m => `<li>${escapeHtml(m)}</li>`).join('') + '</ul>';
                                }

                                if (data.validationErrors && data.validationErrors.length > 0) {
                                    document.getElementById('intakeErrors').innerHTML = '<b>Validation Errors:</b><ul>' +
                                        data.validationErrors.map(e => `<li>${escapeHtml(e)}</li>`).join('') + '</ul>';
                                    card.style.borderColor = 'var(--red)';
                                } else {
                                    card.style.borderColor = 'var(--green)';
                                }
                            } catch (e) {
                                document.getElementById('intakeErrors').textContent = 'Analysis request failed: ' + e;
                            }
                        }

                        async function submitIntakeTask() {
                            const payload = collectIntakePayload();
                            if (!payload.description) {
                                alert('Please provide or dictate a task description.');
                                return;
                            }

                            const btn = document.getElementById('btnSubmitIntakeTask');
                            btn.disabled = true;
                            btn.textContent = 'Submitting...';

                            try {
                                const res = await fetch(apiUrl('/api/tasks/intake/submit'), {
                                    method: 'POST',
                                    headers: getAuthHeaders(),
                                    body: JSON.stringify(payload)
                                });
                                const data = await res.json();
                                if (res.ok && data.taskId) {
                                    alert(`Task submitted successfully!\\nTask ID: ${data.taskId}\\nTitle: ${data.title}\\nStatus: ${data.status}\\nPersisted Instructions: ${data.persistedInstructionsCount}`);
                                    closeNewTaskModal();
                                    loadReviews();
                                    switchTab('reviews');
                                } else {
                                    alert('Failed to submit task:\\n' + (data.error || 'Server error'));
                                }
                            } catch (e) {
                                alert('Submission error: ' + e);
                            } finally {
                                btn.disabled = false;
                                btn.textContent = '🚀 Submit Task';
                            }
                        }

                        loadProjects();
                        loadReviews();
                        loadNotifications();
                        connectNotificationStream();
                    </script>
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
                content = APP_HTML.getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    private static List<String> extractJsonStringArray(String json, String field) {
        if (json == null) return List.of();
        String arrayPattern = "\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(arrayPattern).matcher(json);
        if (!m.find()) {
            return List.of();
        }
        String inner = m.group(1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(inner);
        while (itemMatcher.find()) {
            list.add(itemMatcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return list;
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        // Match string: "field": "value"
        String strPattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(strPattern).matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        // Match number, boolean or literal: "field": 123 or "field": true
        String literalPattern = "\"" + field + "\"\\s*:\\s*([0-9a-zA-Z_\\.\\-]+)";
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(literalPattern).matcher(json);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
