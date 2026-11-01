package eu.kalafatic.tryst.domain.tool;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Common initial file and workspace manipulation tools.
 */
public final class FileTools {

    private FileTools() {}

    public static class ReadFileTool implements Tool {
        @Override
        public String getName() {
            return "read_file";
        }

        @Override
        public String getDescription() {
            return "Reads the entire content of a file within the workspace. Param: 'path'";
        }

        @Override
        public PermissionManager.PermissionLevel getRequiredPermission() {
            return PermissionManager.PermissionLevel.READ_ONLY;
        }

        @Override
        public ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager) {
            String pathStr = parameters.get("path");
            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.fail("Missing required parameter 'path'");
            }
            try {
                Path target = resolvePath(pathStr, workspace);
                permissionManager.validatePathWithinWorkspace(target, workspace);
                if (!Files.exists(target)) {
                    return ToolResult.fail("File does not exist: " + pathStr);
                }
                String content = Files.readString(target);
                return ToolResult.ok(content);
            } catch (Exception e) {
                return ToolResult.fail(e.getMessage());
            }
        }
    }

    public static class WriteFileTool implements Tool {
        @Override
        public String getName() {
            return "write_file";
        }

        @Override
        public String getDescription() {
            return "Writes or overwrites content into a file within the workspace. Params: 'path', 'content'";
        }

        @Override
        public PermissionManager.PermissionLevel getRequiredPermission() {
            return PermissionManager.PermissionLevel.WORKSPACE_WRITE;
        }

        @Override
        public ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager) {
            String pathStr = parameters.get("path");
            String content = parameters.getOrDefault("content", "");
            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.fail("Missing required parameter 'path'");
            }
            try {
                Path target = resolvePath(pathStr, workspace);
                permissionManager.validatePathWithinWorkspace(target, workspace);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, content);
                return ToolResult.ok("File written: " + pathStr);
            } catch (Exception e) {
                return ToolResult.fail(e.getMessage());
            }
        }
    }

    public static class ListDirectoryTool implements Tool {
        @Override
        public String getName() {
            return "list_directory";
        }

        @Override
        public String getDescription() {
            return "Lists entries in a directory. Param: 'path' (optional, defaults to workspace root)";
        }

        @Override
        public PermissionManager.PermissionLevel getRequiredPermission() {
            return PermissionManager.PermissionLevel.READ_ONLY;
        }

        @Override
        public ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager) {
            String pathStr = parameters.getOrDefault("path", ".");
            try {
                Path target = resolvePath(pathStr, workspace);
                permissionManager.validatePathWithinWorkspace(target, workspace);
                if (!Files.exists(target) || !Files.isDirectory(target)) {
                    return ToolResult.fail("Path is not a valid directory: " + pathStr);
                }
                try (Stream<Path> stream = Files.list(target)) {
                    String list = stream.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                            .reduce((a, b) -> a + "\n" + b).orElse("<empty>");
                    return ToolResult.ok(list);
                }
            } catch (Exception e) {
                return ToolResult.fail(e.getMessage());
            }
        }
    }

    public static class CreateDirectoryTool implements Tool {
        @Override
        public String getName() {
            return "create_directory";
        }

        @Override
        public String getDescription() {
            return "Creates a directory within the workspace. Param: 'path'";
        }

        @Override
        public PermissionManager.PermissionLevel getRequiredPermission() {
            return PermissionManager.PermissionLevel.WORKSPACE_WRITE;
        }

        @Override
        public ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager) {
            String pathStr = parameters.get("path");
            if (pathStr == null || pathStr.isBlank()) {
                return ToolResult.fail("Missing required parameter 'path'");
            }
            try {
                Path target = resolvePath(pathStr, workspace);
                permissionManager.validatePathWithinWorkspace(target, workspace);
                Files.createDirectories(target);
                return ToolResult.ok("Directory created: " + pathStr);
            } catch (Exception e) {
                return ToolResult.fail(e.getMessage());
            }
        }
    }

    private static Path resolvePath(String relativeOrAbsolute, Workspace workspace) {
        Path p = Path.of(relativeOrAbsolute);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        if (workspace == null) {
            return p.toAbsolutePath().normalize();
        }
        return workspace.getRootPath().resolve(p).normalize();
    }
}
