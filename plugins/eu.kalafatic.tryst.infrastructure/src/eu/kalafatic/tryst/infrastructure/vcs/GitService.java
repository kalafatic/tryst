package eu.kalafatic.tryst.infrastructure.vcs;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.review.CodeReview;

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
 * Enforces strict commit authorization: commits are only permitted if an authoritative
 * CodeReview has been approved and its content fingerprint matches the working tree diff.
 */
public class GitService {

    public record GitCommandResult(int exitCode, String output, String error) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public record AuthorizedCommitResult(
            boolean success,
            String commitHash,
            String message,
            String error
    ) {}

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

    /**
     * Executes a commit with strict authorization and content fingerprint verification.
     * Prevents time-of-check/time-of-use bypass: commits only if the review is APPROVED
     * and the current effective diff matches the approved fingerprint.
     */
    public AuthorizedCommitResult commitAuthorizedReview(Path repoDir, CodeReview review, String authorizerId, String commitMessage) {
        Objects.requireNonNull(repoDir, "repoDir must not be null");
        Objects.requireNonNull(review, "review must not be null");

        if (!isGitRepository(repoDir)) {
            return new AuthorizedCommitResult(false, null, commitMessage, "Directory is not a valid Git repository: " + repoDir);
        }

        if (!review.isApproved()) {
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Code review " + review.getId() + " is not approved (status: " + review.getStatus() + ")");
        }

        // Verify that the current working tree diff matches the approved content fingerprint
        GitCommandResult diffRes = getDiff(repoDir);
        String currentDiff = diffRes.output();
        String currentFingerprint = CodeReview.calculateFingerprint(currentDiff);

        if (!currentFingerprint.equals(review.getApprovedFingerprint())) {
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Working tree content fingerprint (" + currentFingerprint +
                            ") does not match approved review fingerprint (" + review.getApprovedFingerprint() + "). Content was modified after approval.");
        }

        // Stage all authorized changes
        GitCommandResult stageRes = stageAll(repoDir);
        if (!stageRes.isSuccess()) {
            return new AuthorizedCommitResult(false, null, commitMessage, "Failed to stage changes: " + stageRes.error());
        }

        String message = commitMessage != null && !commitMessage.isBlank()
                ? commitMessage
                : "TRYST: Approved changes for task " + review.getTaskId() + " (Review: " + review.getId() + ", ApprovedBy: " + authorizerId + ")";

        GitCommandResult commitRes = commit(repoDir, message);
        if (!commitRes.isSuccess()) {
            return new AuthorizedCommitResult(false, null, message, "Git commit failed: " + commitRes.error());
        }

        // Retrieve resulting commit hash
        GitCommandResult hashRes = runGit(repoDir, List.of("rev-parse", "HEAD"));
        String commitHash = hashRes.isSuccess() ? hashRes.output().trim() : "UNKNOWN";

        review.recordCommit(commitHash);
        return new AuthorizedCommitResult(true, commitHash, message, null);
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
