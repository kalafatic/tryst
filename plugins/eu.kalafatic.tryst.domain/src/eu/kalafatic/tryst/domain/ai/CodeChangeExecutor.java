package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.tool.Tool;
import eu.kalafatic.tryst.domain.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes code changes incrementally within strict workspace boundaries,
 * tracking every touched or modified file.
 */
public class CodeChangeExecutor {

    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final Set<Path> modifiedFiles = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public record ExecutionOutcome(boolean success, String output, String error, Set<Path> touchedFiles) {}

    public CodeChangeExecutor(ToolRegistry toolRegistry, PermissionManager permissionManager) {
        this.toolRegistry = toolRegistry;
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
    }

    public synchronized void reset() {
        modifiedFiles.clear();
    }

    public Set<Path> getModifiedFiles() {
        return Collections.unmodifiableSet(new HashSet<>(modifiedFiles));
    }

    /**
     * Directly writes content to a target path within the workspace.
     */
    public ExecutionOutcome applyFileChange(Workspace workspace, String relativePath, String content) {
        if (workspace == null) {
            return new ExecutionOutcome(false, "", "No workspace assigned", Set.of());
        }
        try {
            Path target = workspace.getRootPath().resolve(relativePath).normalize();
            permissionManager.validatePathWithinWorkspace(target, workspace);

            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            Files.writeString(target, content != null ? content : "");
            Path rel = workspace.getRootPath().relativize(target);
            modifiedFiles.add(rel);

            return new ExecutionOutcome(true, "File updated: " + rel, "", Set.of(rel));
        } catch (Exception e) {
            return new ExecutionOutcome(false, "", "Error applying file change: " + e.getMessage(), Set.of());
        }
    }

    /**
     * Executes a tool command within the workspace.
     */
    public ExecutionOutcome executeTool(Workspace workspace, String toolName, Map<String, String> params) {
        if (toolRegistry == null) {
            return new ExecutionOutcome(false, "", "No tool registry configured", Set.of());
        }
        Optional<Tool> toolOpt = toolRegistry.getTool(toolName);
        if (toolOpt.isEmpty()) {
            return new ExecutionOutcome(false, "", "Tool not found: " + toolName, Set.of());
        }

        Tool tool = toolOpt.get();
        Tool.ToolResult result = tool.execute(params, workspace, permissionManager);

        if (result.success() && ("write_file".equals(toolName) || "create_directory".equals(toolName))) {
            String p = params.get("path");
            if (p != null && workspace != null) {
                Path rel = Path.of(p).normalize();
                modifiedFiles.add(rel);
            }
        }

        return new ExecutionOutcome(result.success(), result.output(), result.error(), getModifiedFiles());
    }
}
