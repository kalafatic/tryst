package eu.kalafatic.tryst.infrastructure.vcs;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Git integration service executing git CLI commands within authorized repositories.
 * Never performs implicit remote pushes or destructive operations without safety checks.
 */
public class GitService {

    public record GitCommandResult(int exitCode, String output, String error) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public boolean isGitRepository(Path directory) {
        if (directory == null || !Files.exists(directory)) return false;
        return Files.isDirectory(directory.resolve(".git"));
    }

    public GitCommandResult getStatus(Path repoDir) {
        return runGit(repoDir, List.of("status", "--short"));
    }

    public GitCommandResult getDiff(Path repoDir) {
        return runGit(repoDir, List.of("diff"));
    }

    public GitCommandResult getDiffStaged(Path repoDir) {
        return runGit(repoDir, List.of("diff", "--cached"));
    }

    public GitCommandResult listBranches(Path repoDir) {
        return runGit(repoDir, List.of("branch", "--list"));
    }

    public GitCommandResult createBranch(Path repoDir, String branchName) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        return runGit(repoDir, List.of("checkout", "-b", branchName));
    }

    public GitCommandResult stageAll(Path repoDir) {
        return runGit(repoDir, List.of("add", "."));
    }

    public GitCommandResult commit(Path repoDir, String message) {
        Objects.requireNonNull(message, "message must not be null");
        return runGit(repoDir, List.of("commit", "-m", message));
    }

    public GitCommandResult runGit(Path repoDir, List<String> args) {
        Objects.requireNonNull(repoDir, "repoDir must not be null");
        Objects.requireNonNull(args, "args must not be null");

        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoDir.toFile());

        try {
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        err.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitCommandResult(-1, out.toString(), "Git command timed out after 30 seconds");
            }

            outThread.join();
            errThread.join();

            return new GitCommandResult(process.exitValue(), out.toString().trim(), err.toString().trim());
        } catch (Exception e) {
            TrystLog.error(GitService.class, "Git execution failed: " + args, e);
            return new GitCommandResult(-1, "", e.getMessage());
        }
    }
}
