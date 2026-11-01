package eu.kalafatic.tryst.infrastructure.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.build.BuildAndTestService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure Java 21 embedded HTTP server delivering REST endpoints and static Single Page Application.
 * Authoritative web delivery mechanism for TRYST Code Review Center, tasks, and projects.
 */
public class TrystHttpServer implements TrystHttpService {

    private final String host;
    private final int port;
    private final WorkspaceService workspaceService;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final GitService gitService;
    private final BuildAndTestService buildAndTestService;
    private final PermissionManager permissionManager;

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
        this.port = port;
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.gitService = gitService != null ? gitService : new GitService();
        this.buildAndTestService = buildAndTestService != null ? buildAndTestService : new BuildAndTestService();
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
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
        server.createContext("/api/reviews", new ReviewsHandler());
        server.createContext("/", new StaticAssetHandler());
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-User-Id");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String getUserId(HttpExchange exchange) {
        String uid = exchange.getRequestHeaders().getFirst("X-User-Id");
        return (uid != null && !uid.isBlank()) ? uid.trim() : Project.DEFAULT_OWNER_ID;
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
            String userId = getUserId(exchange);

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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
                        .filter(p -> "admin".equals(userId) || userId.equals(p.getOwnerId()))
                        .toList();
            } else if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                allProjects = workspaceService.getCurrentWorkspace().getProjects().stream()
                        .filter(p -> "admin".equals(userId) || userId.equals(p.getOwnerId()))
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

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String title = extractJsonField(body, "title");
                String description = extractJsonField(body, "description");
                String projectId = extractJsonField(body, "projectId");
                if (title == null || title.isBlank()) {
                    sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'title'\"}");
                    return;
                }
                AgentTask t = workspaceService.createTask(projectId, title, description);
                String json = String.format("{\"id\": \"%s\", \"projectId\": \"%s\", \"title\": \"%s\", \"status\": \"%s\"}",
                        escapeJson(t.getId()), escapeJson(t.getProjectId() != null ? t.getProjectId() : ""),
                        escapeJson(t.getTitle()), t.getTaskStatus().name());
                sendJsonResponse(exchange, 201, json);
                return;
            }

            // GET
            List<AgentTask> tasks;
            if (taskRepository != null) {
                tasks = taskRepository.findAll();
            } else if (workspaceService != null && workspaceService.getCurrentWorkspace() != null) {
                tasks = workspaceService.getCurrentWorkspace().getTasks();
            } else {
                tasks = List.of();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"tasks\": [");
            for (int i = 0; i < tasks.size(); i++) {
                AgentTask t = tasks.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format("{\"id\": \"%s\", \"projectId\": \"%s\", \"title\": \"%s\", \"status\": \"%s\", \"reviewId\": \"%s\"}",
                        escapeJson(t.getId()), escapeJson(t.getProjectId() != null ? t.getProjectId() : ""),
                        escapeJson(t.getTitle()), t.getTaskStatus().name(), escapeJson(t.getReviewId() != null ? t.getReviewId() : "")));
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

            String path = exchange.getRequestURI().getPath(); // e.g. /api/reviews or /api/reviews/{id}/action
            String userId = getUserId(exchange);

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

            // Parsing reviewId and sub-actions
            String subPath = path.substring("/api/reviews/".length());
            String[] parts = subPath.split("/");
            String reviewId = parts[0];

            Optional<CodeReview> optRev = codeReviewRepository.findById(reviewId);
            if (optRev.isEmpty()) {
                sendJsonResponse(exchange, 404, "{\"error\": \"CodeReview not found: " + reviewId + "\"}");
                return;
            }
            CodeReview review = optRev.get();

            // Authorization: check project ownership
            if (projectRepository != null) {
                Optional<Project> optProj = projectRepository.findById(review.getProjectId());
                optProj.ifPresent(p -> permissionManager.validateUserProjectAccess(userId, p));
            }

            // Sub-actions
            if (parts.length == 1 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // GET /api/reviews/{id}
                sendJsonResponse(exchange, 200, serializeReview(review));
                return;
            }

            if (parts.length > 1) {
                String action = parts[1];
                switch (action) {
                    case "diff" -> {
                        // GET /api/reviews/{id}/diff
                        String json = String.format("{\"reviewId\": \"%s\", \"diff\": \"%s\", \"contentFingerprint\": \"%s\"}",
                                escapeJson(review.getId()), escapeJson(review.getEffectiveDiff()), escapeJson(review.getContentFingerprint()));
                        sendJsonResponse(exchange, 200, json);
                        return;
                    }
                    case "files" -> {
                        // Sub-action: edit file -> POST /api/reviews/{id}/files/edit
                        if (parts.length > 2 && "edit".equalsIgnoreCase(parts[2]) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String filePath = extractJsonField(body, "filePath");
                            String content = extractJsonField(body, "content");

                            if (filePath == null || filePath.isBlank()) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"Missing required field 'filePath'\"}");
                                return;
                            }

                            // Write edit to project workspace
                            Workspace ws = workspaceService.getWorkspaceForProject(review.getProjectId());
                            Path targetFile = ws.getRootPath().resolve(filePath).normalize();
                            permissionManager.validatePathWithinWorkspace(targetFile, ws);

                            String original = Files.exists(targetFile) ? Files.readString(targetFile) : "";
                            if (targetFile.getParent() != null) {
                                Files.createDirectories(targetFile.getParent());
                            }
                            Files.writeString(targetFile, content != null ? content : "");

                            ReviewFileEdit fileEdit = new ReviewFileEdit(review.getId(), filePath, original, content);
                            codeReviewRepository.saveFileEdit(fileEdit);

                            // Recalculate effective Git diff
                            GitService.GitCommandResult diffRes = gitService.getDiff(ws.getRootPath());
                            review.updateEffectiveDiff(diffRes.output());
                            codeReviewRepository.save(review);

                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "build-test" -> {
                        // POST /api/reviews/{id}/build-test
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            Workspace ws = workspaceService.getWorkspaceForProject(review.getProjectId());
                            var buildRes = buildAndTestService.runBuild(ws.getRootPath(), 120, null);
                            var testRes = buildAndTestService.runTests(ws.getRootPath(), 180, null);

                            String buildStatus = (buildRes.getExitCode() == 0 && testRes.getExitCode() == 0) ? "SUCCESS" : "FAILURE";
                            String summary = "Build: " + (buildRes.getExitCode() == 0 ? "PASSED" : "FAILED") +
                                    ", Tests: " + (testRes.getExitCode() == 0 ? "PASSED" : "FAILED");

                            review.updateBuildAndTestStatus(buildStatus, summary);
                            codeReviewRepository.save(review);

                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "approve" -> {
                        // POST /api/reviews/{id}/approve
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            review.approve(userId);
                            codeReviewRepository.save(review);

                            if (taskRepository != null) {
                                taskRepository.findById(review.getTaskId()).ifPresent(t -> {
                                    t.approve();
                                    taskRepository.save(t);
                                });
                            }

                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "reject" -> {
                        // POST /api/reviews/{id}/reject
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String reason = extractJsonField(body, "reason");
                            review.reject(userId, reason);
                            codeReviewRepository.save(review);

                            if (taskRepository != null) {
                                taskRepository.findById(review.getTaskId()).ifPresent(t -> {
                                    t.reject();
                                    taskRepository.save(t);
                                });
                            }

                            sendJsonResponse(exchange, 200, serializeReview(review));
                            return;
                        }
                    }
                    case "commit" -> {
                        // POST /api/reviews/{id}/commit
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                            String commitMsg = extractJsonField(body, "message");

                            Workspace ws = workspaceService.getWorkspaceForProject(review.getProjectId());
                            GitService.AuthorizedCommitResult commitResult = gitService.commitAuthorizedReview(
                                    ws.getRootPath(), review, userId, commitMsg
                            );

                            if (!commitResult.success()) {
                                sendJsonResponse(exchange, 400, "{\"error\": \"" + escapeJson(commitResult.error()) + "\"}");
                                return;
                            }

                            codeReviewRepository.save(review);
                            if (taskRepository != null) {
                                taskRepository.findById(review.getTaskId()).ifPresent(t -> {
                                    t.startCommitting();
                                    t.complete();
                                    taskRepository.save(t);
                                });
                            }

                            String resp = String.format("{\"success\": true, \"commitHash\": \"%s\", \"review\": %s}",
                                    escapeJson(commitResult.commitHash()), serializeReview(review));
                            sendJsonResponse(exchange, 200, resp);
                            return;
                        }
                    }
                }
            }

            sendJsonResponse(exchange, 404, "{\"error\": \"Unknown action\"}");
        }

        private String serializeReview(CodeReview r) {
            return String.format(
                    "{\"id\": \"%s\", \"projectId\": \"%s\", \"taskId\": \"%s\", \"branch\": \"%s\", \"status\": \"%s\", " +
                            "\"contentFingerprint\": \"%s\", \"approvedFingerprint\": \"%s\", \"commitHash\": \"%s\", " +
                            "\"approvedBy\": \"%s\", \"rejectedBy\": \"%s\", \"rejectionReason\": \"%s\", " +
                            "\"buildStatus\": \"%s\", \"testSummary\": \"%s\", \"createdAt\": \"%s\", \"updatedAt\": \"%s\"}",
                    escapeJson(r.getId()), escapeJson(r.getProjectId()), escapeJson(r.getTaskId()), escapeJson(r.getBranch()),
                    r.getStatus().name(), escapeJson(r.getContentFingerprint()), escapeJson(r.getApprovedFingerprint() != null ? r.getApprovedFingerprint() : ""),
                    escapeJson(r.getCommitHash() != null ? r.getCommitHash() : ""), escapeJson(r.getApprovedBy() != null ? r.getApprovedBy() : ""),
                    escapeJson(r.getRejectedBy() != null ? r.getRejectedBy() : ""), escapeJson(r.getRejectionReason() != null ? r.getRejectionReason() : ""),
                    escapeJson(r.getBuildStatus()), escapeJson(r.getTestSummary()), r.getCreatedAt().toString(), r.getUpdatedAt().toString()
            );
        }
    }

    private static class StaticAssetHandler implements HttpHandler {
        private static final String APP_HTML = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>TRYST – Autonomous Development & Code Review Center</title>
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
                        .container { display: flex; height: calc(100vh - 54px); }
                        .sidebar { width: 320px; border-right: 1px solid var(--border); background: #131d2e; display: flex; flex-direction: column; overflow-y: auto; }
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
                        .btn { padding: 8px 16px; border-radius: 6px; font-weight: 600; cursor: pointer; border: none; font-size: 13px; }
                        .btn-primary { background: #0284c7; color: white; }
                        .btn-success { background: #16a34a; color: white; }
                        .btn-danger { background: #dc2626; color: white; }
                        .btn:hover { opacity: 0.9; }
                        pre.diff-view { background: #0b1120; border: 1px solid var(--border); border-radius: 6px; padding: 12px; font-family: monospace; font-size: 13px; overflow-x: auto; line-height: 1.5; }
                        .diff-add { color: #4ade80; background: rgba(34, 197, 94, 0.15); display: block; }
                        .diff-del { color: #f87171; background: rgba(239, 68, 68, 0.15); display: block; }
                        .diff-hdr { color: #94a3b8; font-weight: bold; display: block; }
                        textarea.code-editor { width: 100%; height: 260px; background: #090d16; border: 1px solid var(--border); color: #e2e8f0; font-family: monospace; font-size: 13px; padding: 12px; border-radius: 6px; resize: vertical; }
                        .flex-row { display: flex; gap: 12px; align-items: center; }
                    </style>
                </head>
                <body>
                    <header>
                        <h1>TRYST Server & Review Center</h1>
                        <div class="flex-row">
                            <span id="userBadge" class="badge badge-warning">User: default-user</span>
                            <span id="serverBadge" class="badge badge-success">ONLINE</span>
                        </div>
                    </header>
                    <div class="container">
                        <div class="sidebar" style="padding: 16px;">
                            <div class="section-title">Projects</div>
                            <div id="projectsList">Loading...</div>
                            <hr style="border: 0; border-top: 1px solid var(--border); margin: 16px 0;">
                            <div class="section-title">Reviews Queue</div>
                            <div id="reviewsList">Loading...</div>
                        </div>
                        <div class="main-content">
                            <div id="noSelectionNotice" class="card">
                                <h3>Select a Code Review from the queue to inspect proposed changes.</h3>
                                <p style="color: var(--muted);">The TRYST Autonomous Lifecycle ensures all tool modifications require explicit human approval and content verification before committing.</p>
                            </div>
                            <div id="reviewDetails" style="display: none;">
                                <div class="card">
                                    <div class="flex-row" style="justify-content: space-between;">
                                        <div>
                                            <h2 id="reviewTitle" style="margin: 0; font-size: 18px;">Review</h2>
                                            <p id="reviewMeta" style="color: var(--muted); margin: 4px 0 0; font-size: 13px;"></p>
                                        </div>
                                        <div class="flex-row">
                                            <button class="btn btn-primary" onclick="reRunBuildTest()">Re-run Build/Test</button>
                                            <button class="btn btn-success" id="btnApprove" onclick="approveReview()">Approve</button>
                                            <button class="btn btn-danger" id="btnReject" onclick="rejectReview()">Reject</button>
                                            <button class="btn btn-primary" id="btnCommit" onclick="commitReview()" style="display: none;">Commit</button>
                                        </div>
                                    </div>
                                    <div id="statusAlert" style="margin-top: 12px;"></div>
                                </div>

                                <div class="card">
                                    <div class="section-title">Proposed Changes (Unified Diff)</div>
                                    <pre id="diffViewer" class="diff-view"></pre>
                                </div>

                                <div class="card">
                                    <div class="section-title">Edit Proposed File Content</div>
                                    <div class="flex-row" style="margin-bottom: 8px;">
                                        <input type="text" id="editFilePath" placeholder="File path (e.g. src/Feature.java)" style="flex: 1; padding: 8px; background: #090d16; border: 1px solid var(--border); color: white; border-radius: 4px;">
                                        <button class="btn btn-primary" onclick="saveFileEdit()">Save Edit to Workspace</button>
                                    </div>
                                    <textarea id="fileEditor" class="code-editor" placeholder="Enter updated file content here..."></textarea>
                                </div>
                            </div>
                        </div>
                    </div>

                    <script>
                        let activeReview = null;

                        async function loadProjects() {
                            try {
                                const res = await fetch('/api/projects');
                                const data = await res.json();
                                const container = document.getElementById('projectsList');
                                container.innerHTML = '';
                                data.projects.forEach(p => {
                                    const d = document.createElement('div');
                                    d.className = 'list-item';
                                    d.innerHTML = `<strong>${escapeHtml(p.name)}</strong><br><small style="color: var(--muted);">${p.id.substring(0,8)}...</small>`;
                                    container.appendChild(d);
                                });
                            } catch(e) {}
                        }

                        async function loadReviews() {
                            try {
                                const res = await fetch('/api/reviews');
                                const data = await res.json();
                                const container = document.getElementById('reviewsList');
                                container.innerHTML = '';
                                if (!data.reviews || data.reviews.length === 0) {
                                    container.innerHTML = '<p style="color: var(--muted); font-size: 13px;">No pending reviews</p>';
                                    return;
                                }
                                data.reviews.forEach(r => {
                                    const d = document.createElement('div');
                                    d.className = 'list-item' + (activeReview && activeReview.id === r.id ? ' active' : '');
                                    d.innerHTML = `<strong>${escapeHtml(r.branch)}</strong> <span class="badge ${r.status==='APPROVED'?'badge-success':(r.status==='REJECTED'?'badge-error':'badge-warning')}">${r.status}</span><br><small style="color: var(--muted);">Task: ${r.taskId.substring(0,8)}...</small>`;
                                    d.onclick = () => selectReview(r);
                                    container.appendChild(d);
                                });
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

                            renderDiff(r.effectiveDiff || r.initialDiff || '');
                            loadReviews();
                        }

                        function renderDiff(diffText) {
                            const viewer = document.getElementById('diffViewer');
                            if (!diffText.trim()) {
                                viewer.innerHTML = '<span style="color: var(--muted);">No modified lines</span>';
                                return;
                            }
                            const lines = diffText.split('\\n');
                            viewer.innerHTML = lines.map(line => {
                                const esc = escapeHtml(line);
                                if (line.startsWith('+') && !line.startsWith('+++')) return `<span class="diff-add">${esc}</span>`;
                                if (line.startsWith('-') && !line.startsWith('---')) return `<span class="diff-del">${esc}</span>`;
                                if (line.startsWith('@@') || line.startsWith('diff --git')) return `<span class="diff-hdr">${esc}</span>`;
                                return esc;
                            }).join('\\n');
                        }

                        async function saveFileEdit() {
                            if (!activeReview) return;
                            const filePath = document.getElementById('editFilePath').value;
                            const content = document.getElementById('fileEditor').value;
                            const res = await fetch(`/api/reviews/${activeReview.id}/files/edit`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ filePath, content })
                            });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        async function approveReview() {
                            if (!activeReview) return;
                            const res = await fetch(`/api/reviews/${activeReview.id}/approve`, { method: 'POST' });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        async function rejectReview() {
                            if (!activeReview) return;
                            const res = await fetch(`/api/reviews/${activeReview.id}/reject`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ reason: 'Rejected from web UI' })
                            });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        async function commitReview() {
                            if (!activeReview) return;
                            const res = await fetch(`/api/reviews/${activeReview.id}/commit`, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
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

                        async function reRunBuildTest() {
                            if (!activeReview) return;
                            const res = await fetch(`/api/reviews/${activeReview.id}/build-test`, { method: 'POST' });
                            const updated = await res.json();
                            selectReview(updated);
                        }

                        function escapeHtml(s) {
                            if (!s) return '';
                            return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
                        }

                        loadProjects();
                        loadReviews();
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

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
