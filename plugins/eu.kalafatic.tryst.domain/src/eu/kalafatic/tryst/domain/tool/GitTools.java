package eu.kalafatic.tryst.domain.tool;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Adapter tool allowing agent to invoke git status and diff inside workspace.
 */
public class GitTools {

    public interface GitRunner {
        int run(Path workingDir, List<String> args, StringBuilder output, StringBuilder error);
    }

    private static volatile GitRunner runner = (dir, args, out, err) -> {
        try {
            List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            }
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line).append("\n");
            }
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return p.exitValue();
        } catch (Exception e) {
            err.append(e.getMessage());
            return -1;
        }
    };

    public static void setRunner(GitRunner customRunner) {
        runner = customRunner;
    }

    public static class GitDiffTool implements Tool {
        @Override
        public String getName() {
            return "git_diff";
        }

        @Override
        public String getDescription() {
            return "Retrieves the unstaged and staged git diff for the active workspace repository.";
        }

        @Override
        public PermissionManager.PermissionLevel getRequiredPermission() {
            return PermissionManager.PermissionLevel.READ_ONLY;
        }

        @Override
        public ToolResult execute(Map<String, String> parameters, Workspace workspace, PermissionManager permissionManager) {
            if (workspace == null) return ToolResult.fail("No active workspace");
            Path root = workspace.getRootPath();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            int code = runner.run(root, List.of("diff"), out, err);
            if (code == 0) {
                return ToolResult.ok(out.toString());
            }
            return ToolResult.fail(err.toString());
        }
    }
}
