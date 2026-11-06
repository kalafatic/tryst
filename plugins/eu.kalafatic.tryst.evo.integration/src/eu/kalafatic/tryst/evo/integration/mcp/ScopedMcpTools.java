package eu.kalafatic.tryst.evo.integration.mcp;

import eu.kalafatic.tryst.domain.build.BuildAndQualityService;
import eu.kalafatic.tryst.domain.build.QualityReport;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.repository.ProjectRepository;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
import eu.kalafatic.tryst.domain.repository.WorkspaceRepository;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.evo.integration.mcp.McpTool.McpToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Suite of scoped MCP tools exposed to EVO, strictly bounded to authorized task sandboxes.
 */
public final class ScopedMcpTools {

    private ScopedMcpTools() {}

    public static class GetProjectMetadataTool implements McpTool {
        private final ProjectRepository projectRepository;

        public GetProjectMetadataTool(ProjectRepository projectRepository) {
            this.projectRepository = projectRepository;
        }

        @Override
        public String getName() {
            return "get_project_metadata";
        }

        @Override
        public String getDescription() {
            return "Returns project configuration and location information";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"projectId\":{\"type\":\"string\"}},\"required\":[\"projectId\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "read:task";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String projId = arguments.getOrDefault("projectId", callerProjectId);
            if (projId == null || projId.isBlank()) {
                return McpToolResult.error("Missing required parameter: projectId");
            }
            if (projectRepository == null) {
                return McpToolResult.error("ProjectRepository unavailable");
            }
            Optional<Project> opt = projectRepository.findById(projId);
            if (opt.isEmpty()) {
                return McpToolResult.error("Project not found: " + projId);
            }
            Project p = opt.get();
            return McpToolResult.success(String.format("{\"id\":\"%s\",\"name\":\"%s\",\"description\":\"%s\",\"ownerId\":\"%s\"}",
                    p.getId(), p.getName(), p.getDescription(), p.getOwnerId()));
        }
    }

    public static class ListWorkspaceFilesTool implements McpTool {
        private final WorkspaceRepository workspaceRepository;
        private final PermissionManager permissionManager;

        public ListWorkspaceFilesTool(WorkspaceRepository workspaceRepository, PermissionManager permissionManager) {
            this.workspaceRepository = workspaceRepository;
            this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        }

        @Override
        public String getName() {
            return "list_workspace_files";
        }

        @Override
        public String getDescription() {
            return "Lists directory contents within task workspace sandbox";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}},\"required\":[\"taskId\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "read:workspace";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            String relPath = arguments.getOrDefault("path", ".");
            if (taskId == null || taskId.isBlank()) {
                return McpToolResult.error("Missing required parameter: taskId");
            }
            Workspace ws = resolveWorkspace(taskId, workspaceRepository);
            if (ws == null) {
                return McpToolResult.error("Workspace not found for task: " + taskId);
            }
            try {
                Path target = resolveSafePath(ws, relPath, permissionManager);
                if (!Files.exists(target) || !Files.isDirectory(target)) {
                    return McpToolResult.error("Path is not a valid directory: " + relPath);
                }
                try (Stream<Path> s = Files.list(target)) {
                    List<String> entries = s.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "")).toList();
                    return McpToolResult.success(String.join("\n", entries));
                }
            } catch (Exception e) {
                return McpToolResult.error("Access denied or failed: " + e.getMessage());
            }
        }
    }

    public static class ReadWorkspaceFileTool implements McpTool {
        private final WorkspaceRepository workspaceRepository;
        private final PermissionManager permissionManager;

        public ReadWorkspaceFileTool(WorkspaceRepository workspaceRepository, PermissionManager permissionManager) {
            this.workspaceRepository = workspaceRepository;
            this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        }

        @Override
        public String getName() {
            return "read_workspace_file";
        }

        @Override
        public String getDescription() {
            return "Reads file contents within task workspace sandbox";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}},\"required\":[\"taskId\",\"path\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "read:workspace";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            String relPath = arguments.get("path");
            if (taskId == null || taskId.isBlank() || relPath == null || relPath.isBlank()) {
                return McpToolResult.error("Missing required parameters: taskId and path");
            }
            Workspace ws = resolveWorkspace(taskId, workspaceRepository);
            if (ws == null) {
                return McpToolResult.error("Workspace not found for task: " + taskId);
            }
            try {
                Path target = resolveSafePath(ws, relPath, permissionManager);
                if (!Files.exists(target) || Files.isDirectory(target)) {
                    return McpToolResult.error("File does not exist: " + relPath);
                }
                return McpToolResult.success(Files.readString(target));
            } catch (Exception e) {
                return McpToolResult.error("Access denied or failed: " + e.getMessage());
            }
        }
    }

    public static class SearchWorkspaceCodeTool implements McpTool {
        private final WorkspaceRepository workspaceRepository;
        private final PermissionManager permissionManager;

        public SearchWorkspaceCodeTool(WorkspaceRepository workspaceRepository, PermissionManager permissionManager) {
            this.workspaceRepository = workspaceRepository;
            this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        }

        @Override
        public String getName() {
            return "search_workspace_code";
        }

        @Override
        public String getDescription() {
            return "Searches text patterns within task workspace files";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"}},\"required\":[\"taskId\",\"query\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "read:workspace";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            String query = arguments.get("query");
            if (taskId == null || taskId.isBlank() || query == null || query.isBlank()) {
                return McpToolResult.error("Missing required parameters: taskId and query");
            }
            Workspace ws = resolveWorkspace(taskId, workspaceRepository);
            if (ws == null) {
                return McpToolResult.error("Workspace not found for task: " + taskId);
            }
            try {
                StringBuilder matches = new StringBuilder();
                Path root = ws.getRootPath();
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(".git"))
                        .forEach(p -> {
                            try {
                                List<String> lines = Files.readAllLines(p);
                                for (int i = 0; i < lines.size(); i++) {
                                    if (lines.get(i).contains(query)) {
                                        Path rel = root.relativize(p);
                                        matches.append(rel).append(":").append(i + 1).append(": ").append(lines.get(i)).append("\n");
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                }
                return McpToolResult.success(matches.length() > 0 ? matches.toString() : "No matches found.");
            } catch (Exception e) {
                return McpToolResult.error("Search failed: " + e.getMessage());
            }
        }
    }

    public static class GetTaskContextTool implements McpTool {
        private final TaskRepository taskRepository;
        private final TaskInstructionRepository taskInstructionRepository;

        public GetTaskContextTool(TaskRepository taskRepository, TaskInstructionRepository taskInstructionRepository) {
            this.taskRepository = taskRepository;
            this.taskInstructionRepository = taskInstructionRepository;
        }

        @Override
        public String getName() {
            return "get_task_context";
        }

        @Override
        public String getDescription() {
            return "Returns task title, description, intake instructions, and acceptance criteria";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"}},\"required\":[\"taskId\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "read:task";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            if (taskId == null || taskId.isBlank()) {
                return McpToolResult.error("Missing required parameter: taskId");
            }
            if (taskRepository == null) {
                return McpToolResult.error("TaskRepository unavailable");
            }
            Optional<Task> opt = taskRepository.findTaskById(taskId);
            if (opt.isEmpty()) {
                return McpToolResult.error("Task not found: " + taskId);
            }
            Task t = opt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Title: ").append(t.getTitle()).append("\n");
            sb.append("Description: ").append(t.getDescription() != null ? t.getDescription() : "").append("\n");

            if (taskInstructionRepository != null) {
                List<TaskInstruction> instructions = taskInstructionRepository.findByTaskId(taskId);
                if (!instructions.isEmpty()) {
                    sb.append("\nIntake Instructions:\n");
                    for (TaskInstruction inst : instructions) {
                        sb.append("- [").append(inst.getInstructionType()).append("]: ").append(inst.getContent()).append("\n");
                    }
                }
            }
            return McpToolResult.success(sb.toString());
        }
    }

    public static class ProposePatchTool implements McpTool {
        private final WorkspaceRepository workspaceRepository;
        private final PermissionManager permissionManager;

        public ProposePatchTool(WorkspaceRepository workspaceRepository, PermissionManager permissionManager) {
            this.workspaceRepository = workspaceRepository;
            this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
        }

        @Override
        public String getName() {
            return "propose_patch";
        }

        @Override
        public String getDescription() {
            return "Applies a unified diff patch to the task workspace sandbox if write permission is granted";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"},\"patch\":{\"type\":\"string\"}},\"required\":[\"taskId\",\"patch\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "write:workspace";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            String patch = arguments.get("patch");
            if (taskId == null || taskId.isBlank() || patch == null || patch.isBlank()) {
                return McpToolResult.error("Missing required parameters: taskId and patch");
            }
            Workspace ws = resolveWorkspace(taskId, workspaceRepository);
            if (ws == null) {
                return McpToolResult.error("Workspace not found for task: " + taskId);
            }
            try {
                // Apply patch via git apply in sandbox
                ProcessBuilder pb = new ProcessBuilder("git", "apply", "-")
                        .directory(ws.getRootPath().toFile());
                Process p = pb.start();
                p.getOutputStream().write(patch.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                p.getOutputStream().close();
                int code = p.waitFor();
                if (code == 0) {
                    return McpToolResult.success("Patch applied cleanly into sandbox.");
                } else {
                    String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return McpToolResult.error("Failed to apply patch: " + err);
                }
            } catch (Exception e) {
                return McpToolResult.error("Patch application error: " + e.getMessage());
            }
        }
    }

    public static class RunBuildAndTestTool implements McpTool {
        private final WorkspaceRepository workspaceRepository;
        private final BuildAndQualityService buildService;

        public RunBuildAndTestTool(WorkspaceRepository workspaceRepository, BuildAndQualityService buildService) {
            this.workspaceRepository = workspaceRepository;
            this.buildService = buildService;
        }

        @Override
        public String getName() {
            return "run_build_and_test";
        }

        @Override
        public String getDescription() {
            return "Runs build verification in workspace sandbox if build permission is granted";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"taskId\":{\"type\":\"string\"}},\"required\":[\"taskId\"]}";
        }

        @Override
        public String getRequiredScope() {
            return "execute:build";
        }

        @Override
        public McpToolResult execute(Map<String, String> arguments, String callerUserId, String callerProjectId, String callerTaskId) {
            String taskId = arguments.getOrDefault("taskId", callerTaskId);
            if (taskId == null || taskId.isBlank()) {
                return McpToolResult.error("Missing required parameter: taskId");
            }
            Workspace ws = resolveWorkspace(taskId, workspaceRepository);
            if (ws == null) {
                return McpToolResult.error("Workspace not found for task: " + taskId);
            }
            if (buildService == null) {
                return McpToolResult.error("BuildAndQualityService unavailable");
            }
            try {
                QualityReport report = buildService.runQualityAssurance(ws, taskId, Duration.ofMinutes(5), () -> false, null);
                return McpToolResult.success(String.format("Build: %s, Tests: %s. Details: %s",
                        report.buildStatus(), report.testStatus(), report.failureDetails()));
            } catch (Exception e) {
                return McpToolResult.error("Build execution failed: " + e.getMessage());
            }
        }
    }

    private static Workspace resolveWorkspace(String taskId, WorkspaceRepository workspaceRepository) {
        if (workspaceRepository == null) return null;
        return workspaceRepository.findByTaskId(taskId).orElse(null);
    }

    private static Path resolveSafePath(Workspace ws, String relPath, PermissionManager permissionManager) {
        if (relPath == null || relPath.isBlank() || relPath.equals(".")) {
            return ws.getRootPath();
        }
        Path raw = Path.of(relPath);
        if (raw.isAbsolute()) {
            throw new SecurityException("Absolute paths are forbidden: " + relPath);
        }
        Path target = ws.getRootPath().resolve(raw).normalize();
        permissionManager.validatePathWithinWorkspace(target, ws);
        return target;
    }
}
