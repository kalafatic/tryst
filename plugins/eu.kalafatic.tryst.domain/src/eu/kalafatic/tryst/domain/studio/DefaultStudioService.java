package eu.kalafatic.tryst.domain.studio;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.review.DiffModel;
import eu.kalafatic.tryst.domain.review.DiffParser;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.service.NotificationService;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultStudioService implements StudioService {

    private final TaskRepository taskRepository;
    private final WorkspaceService workspaceService;
    private final ProjectRepository projectRepository;
    private final TaskInstructionRepository taskInstructionRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final TaskOrchestrator taskOrchestrator;
    private final NotificationService notificationService;
    private final EventBus eventBus;
    private final java.util.function.Supplier<Connection> connectionSupplier;

    private final Map<String, List<StudioFileRevision>> fileRevisionsCache = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(1000);

    public DefaultStudioService(
            TaskRepository taskRepository,
            WorkspaceService workspaceService,
            ProjectRepository projectRepository,
            TaskInstructionRepository taskInstructionRepository,
            CodeReviewRepository codeReviewRepository,
            TaskOrchestrator taskOrchestrator,
            NotificationService notificationService,
            EventBus eventBus,
            java.util.function.Supplier<Connection> connectionSupplier
    ) {
        this.taskRepository = taskRepository;
        this.workspaceService = workspaceService;
        this.projectRepository = projectRepository;
        this.taskInstructionRepository = taskInstructionRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.taskOrchestrator = taskOrchestrator;
        this.notificationService = notificationService;
        this.eventBus = eventBus;
        this.connectionSupplier = connectionSupplier;
    }

    @Override
    public StudioTaskSnapshot getTaskSnapshot(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Task task = taskRepository != null ? taskRepository.findTaskById(taskId).orElse(null) : null;
        Workspace ws = workspaceService != null ? workspaceService.getWorkspaceForTask(taskId) : null;

        String repoName = "Workspace Sandbox";
        String branch = "master";
        if (task != null && task.getProjectId() != null && projectRepository != null) {
            var optP = projectRepository.findById(task.getProjectId());
            if (optP.isPresent()) {
                repoName = optP.get().getName();
                if (optP.get().getLocation() != null) {
                    repoName += " (" + optP.get().getLocation().getFileName().toString() + ")";
                }
            }
        }
        if (ws != null && ws.getMetadata() != null && ws.getMetadata().containsKey("branch")) {
            branch = ws.getMetadata().get("branch");
        }

        boolean isRunning = (task != null && task.getStatus() != null && task.getStatus().isActive());
        List<TaskEvent> events = taskRepository != null ? taskRepository.findEventsByTaskId(taskId) : List.of();
        List<TaskInstruction> instructions = taskInstructionRepository != null ? taskInstructionRepository.findByTaskId(taskId) : List.of();
        List<ReviewComment> comments = codeReviewRepository != null ? codeReviewRepository.findCommentsByTaskId(taskId) : List.of();
        List<StudioPlanStep> planSteps = getPlanSteps(taskId);
        List<StudioAlternative> alternatives = getAlternatives(taskId);

        List<String> modified = new ArrayList<>();
        String fullDiff = "";
        if (taskOrchestrator != null) {
            var optCtx = taskOrchestrator.getExecutionContext(taskId);
            if (optCtx.isPresent()) {
                fullDiff = optCtx.get().getCapturedDiff() != null ? optCtx.get().getCapturedDiff() : "";
            }
        }

        if (fullDiff.isBlank() && codeReviewRepository != null) {
            var optRev = codeReviewRepository.findByTaskId(taskId);
            if (optRev.isPresent()) {
                List<eu.kalafatic.tryst.domain.review.ReviewRevision> revList = codeReviewRepository.findRevisionsByReviewId(optRev.get().getId());
                if (!revList.isEmpty()) {
                    fullDiff = revList.get(revList.size() - 1).getDiff();
                }
            }
        }

        if (!fullDiff.isBlank()) {
            DiffModel.ParsedDiff parsed = DiffParser.parse(fullDiff);
            if (parsed != null && parsed.files() != null) {
                for (DiffModel.FileDiff f : parsed.files()) {
                    String path = f.newPath() != null ? f.newPath() : f.oldPath();
                    if (path != null && !modified.contains(path)) {
                        modified.add(path);
                    }
                }
            }
        }

        long lastSeq = sequenceCounter.get();

        return new StudioTaskSnapshot(
                task,
                ws,
                repoName,
                branch,
                isRunning,
                events,
                instructions,
                comments,
                planSteps,
                alternatives,
                modified,
                fullDiff,
                lastSeq
        );
    }

    @Override
    public StudioAlternative proposeAlternative(StudioAlternative alternative) {
        Objects.requireNonNull(alternative, "alternative must not be null");
        if (connectionSupplier == null) {
            return alternative;
        }
        String sql = """
            INSERT INTO studio_alternatives (id, task_id, title, description, advantages, disadvantages, affected_files, risks, compatibility_impact, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """;
        try {
            Connection conn = connectionSupplier.get();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, alternative.id());
                ps.setString(2, alternative.taskId());
                ps.setString(3, alternative.title());
                ps.setString(4, alternative.description());
                ps.setString(5, alternative.advantages() != null ? alternative.advantages() : "");
                ps.setString(6, alternative.disadvantages() != null ? alternative.disadvantages() : "");
                ps.setString(7, String.join(",", alternative.affectedFiles()));
                ps.setString(8, alternative.risks() != null ? alternative.risks() : "");
                ps.setString(9, alternative.compatibilityImpact() != null ? alternative.compatibilityImpact() : "");
                ps.setString(10, alternative.status().name());
                ps.setString(11, alternative.createdAt().toString());
                ps.executeUpdate();

                broadcastStudioEvent(alternative.taskId(), "ALTERNATIVES_PROPOSED",
                        "{\"alternativeId\":\"" + alternative.id() + "\", \"title\":\"" + escapeJson(alternative.title()) + "\"}");
            }
        } catch (SQLException e) {
            TrystLog.error(DefaultStudioService.class, "Failed to persist studio alternative: " + e.getMessage(), e);
        }
        return alternative;
    }

    @Override
    public List<StudioAlternative> getAlternatives(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (connectionSupplier == null) return List.of();
        String sql = "SELECT * FROM studio_alternatives WHERE task_id = ? ORDER BY created_at ASC;";
        List<StudioAlternative> list = new ArrayList<>();
        try {
            Connection conn = connectionSupplier.get();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String affStr = rs.getString("affected_files");
                        List<String> aff = (affStr != null && !affStr.isBlank()) ? List.of(affStr.split(",")) : List.of();
                        list.add(new StudioAlternative(
                                rs.getString("id"),
                                rs.getString("task_id"),
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("advantages"),
                                rs.getString("disadvantages"),
                                aff,
                                rs.getString("risks"),
                                rs.getString("compatibility_impact"),
                                StudioAlternative.AlternativeStatus.valueOf(rs.getString("status")),
                                Instant.parse(rs.getString("created_at"))
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            TrystLog.error(DefaultStudioService.class, "Failed to load studio alternatives for task " + taskId + ": " + e.getMessage(), e);
        }
        return list;
    }

    @Override
    public boolean selectAlternative(String alternativeId, String taskId, String userId) {
        Objects.requireNonNull(alternativeId, "alternativeId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (connectionSupplier == null) return false;

        String sql1 = "UPDATE studio_alternatives SET status = 'REJECTED' WHERE task_id = ?;";
        String sql2 = "UPDATE studio_alternatives SET status = 'SELECTED' WHERE id = ? AND task_id = ?;";

        try {
            Connection conn = connectionSupplier.get();
            try (PreparedStatement ps1 = conn.prepareStatement(sql1);
                 PreparedStatement ps2 = conn.prepareStatement(sql2)) {
                ps1.setString(1, taskId);
                ps1.executeUpdate();

                ps2.setString(1, alternativeId);
                ps2.setString(2, taskId);
                int updated = ps2.executeUpdate();

                if (updated > 0) {
                    submitUserInstruction(taskId, userId, "Selected Implementation Alternative ID: " + alternativeId);

                    broadcastStudioEvent(taskId, "USER_INPUT_RECEIVED",
                            "{\"decision\":\"ALTERNATIVE_SELECTED\", \"alternativeId\":\"" + alternativeId + "\"}");
                    return true;
                }
            }
        } catch (SQLException e) {
            TrystLog.error(DefaultStudioService.class, "Error selecting alternative " + alternativeId + ": " + e.getMessage(), e);
        }
        return false;
    }

    @Override
    public void updatePlan(String taskId, List<StudioPlanStep> steps) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (connectionSupplier == null || steps == null) return;

        String delSql = "DELETE FROM studio_plans WHERE task_id = ?;";
        String insSql = """
            INSERT INTO studio_plans (id, task_id, step_order, description, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?);
        """;

        try {
            Connection conn = connectionSupplier.get();
            try (PreparedStatement dps = conn.prepareStatement(delSql);
                 PreparedStatement ips = conn.prepareStatement(insSql)) {
                dps.setString(1, taskId);
                dps.executeUpdate();

                for (StudioPlanStep step : steps) {
                    ips.setString(1, step.id());
                    ips.setString(2, taskId);
                    ips.setInt(3, step.stepOrder());
                    ips.setString(4, step.description());
                    ips.setString(5, step.status().name());
                    ips.setString(6, step.updatedAt().toString());
                    ips.addBatch();
                }
                ips.executeBatch();

                broadcastStudioEvent(taskId, "PLAN_UPDATE", "{\"stepCount\":" + steps.size() + "}");
            }
        } catch (SQLException e) {
            TrystLog.error(DefaultStudioService.class, "Failed to update studio plan: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StudioPlanStep> getPlanSteps(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (connectionSupplier == null) return List.of();
        String sql = "SELECT * FROM studio_plans WHERE task_id = ? ORDER BY step_order ASC;";
        List<StudioPlanStep> list = new ArrayList<>();
        try {
            Connection conn = connectionSupplier.get();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new StudioPlanStep(
                            rs.getString("id"),
                            rs.getString("task_id"),
                            rs.getInt("step_order"),
                            rs.getString("description"),
                            StudioPlanStep.StepStatus.valueOf(rs.getString("status")),
                            Instant.parse(rs.getString("updated_at"))
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            TrystLog.error(DefaultStudioService.class, "Failed to retrieve studio plans: " + e.getMessage(), e);
        }
        return list;
    }

    @Override
    public StudioFileRevision recordFileModification(String taskId, String filePath, String diffHunk, int additions, int deletions) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");

        List<StudioFileRevision> revs = fileRevisionsCache.computeIfAbsent(taskId + ":" + filePath, k -> new ArrayList<>());
        int nextRev = revs.size() + 1;
        StudioFileRevision rev = new StudioFileRevision(taskId, filePath, nextRev, diffHunk, additions, deletions, Instant.now());
        revs.add(rev);

        broadcastStudioEvent(taskId, "FILE_MODIFIED",
                "{\"filePath\":\"" + escapeJson(filePath) + "\", \"revision\":" + nextRev + ", \"additions\":" + additions + ", \"deletions\":" + deletions + "}");

        return rev;
    }

    @Override
    public List<StudioFileRevision> getFileRevisions(String taskId, String filePath) {
        List<StudioFileRevision> revs = fileRevisionsCache.get(taskId + ":" + filePath);
        return revs != null ? List.copyOf(revs) : List.of();
    }

    @Override
    public TaskInstruction submitUserInstruction(String taskId, String userId, String content) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(content, "content must not be null");

        TaskInstruction inst = new TaskInstruction(
                taskId,
                TaskInstruction.InstructionType.SUPPLEMENTARY,
                content,
                userId != null && !userId.isBlank() ? userId : "user"
        );

        if (taskInstructionRepository != null) {
            taskInstructionRepository.save(inst);
        }

        if (taskOrchestrator != null) {
            var optCtx = taskOrchestrator.getExecutionContext(taskId);
            optCtx.ifPresent(ctx -> ctx.queueInstruction(content));
        }

        broadcastStudioEvent(taskId, "USER_MESSAGE_RECEIVED",
                "{\"instructionId\":\"" + inst.getId() + "\", \"content\":\"" + escapeJson(content) + "\"}");

        return inst;
    }

    @Override
    public ReviewComment addComment(ReviewComment comment) {
        Objects.requireNonNull(comment, "comment must not be null");
        if (codeReviewRepository != null) {
            codeReviewRepository.saveComment(comment);
        }

        broadcastStudioEvent(comment.getTaskId(), "COMMENT_RECEIVED",
                "{\"commentId\":\"" + comment.getId() + "\", \"filePath\":\"" + escapeJson(comment.getFilePath()) + "\", \"line\":" + comment.getStartLine() + "}");

        return comment;
    }

    @Override
    public List<ReviewComment> getComments(String taskId) {
        return codeReviewRepository != null ? codeReviewRepository.findCommentsByTaskId(taskId) : List.of();
    }

    @Override
    public Map<String, String> getFileContent(String taskId, String filePath) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        Map<String, String> result = new HashMap<>();
        result.put("filePath", filePath);
        result.put("currentContent", "");
        result.put("originalContent", "");

        if (workspaceService == null) return result;
        Workspace ws = workspaceService.getWorkspaceForTask(taskId);
        if (ws == null) return result;

        Path root = ws.getRootPath();
        if (root == null) return result;

        Path target = root.resolve(filePath).normalize();
        if (Files.exists(target) && Files.isRegularFile(target)) {
            try {
                result.put("currentContent", Files.readString(target, StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }

        return result;
    }

    private void broadcastStudioEvent(String taskId, String eventType, String jsonPayload) {
        long seq = sequenceCounter.incrementAndGet();
        if (notificationService != null) {
            notificationService.notifyUser(
                    "all",
                    eventType,
                    "TRYST Studio: " + eventType,
                    jsonPayload,
                    "studio",
                    taskId,
                    "studio:" + taskId + ":" + seq
            );
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
