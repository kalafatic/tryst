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

    public GitCommandResult createBranch(Path repoDir, String branchName, String startPoint) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        if (startPoint == null || startPoint.isBlank()) {
            return createBranch(repoDir, branchName);
        }
        return runGit(repoDir, List.of("checkout", "-b", branchName, startPoint));
    }

    public GitCommandResult checkout(Path repoDir, String branchName) {
        Objects.requireNonNull(branchName, "branchName must not be null");
        return runGit(repoDir, List.of("checkout", branchName));
    }

    public GitCommandResult cloneOrFetch(Path targetDir, String cloneUrl, String branch, String token) {
        Objects.requireNonNull(targetDir, "targetDir must not be null");
        Objects.requireNonNull(cloneUrl, "cloneUrl must not be null");

        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        String authenticatedUrl = buildAuthenticatedUrl(cloneUrl, token);

        try {
            if (isGitRepository(targetDir)) {
                TrystLog.info(GitService.class, "Existing repository found in " + targetDir + "; fetching latest changes for branch " + effectiveBranch);
                // Update remote origin url with authentication if token provided
                if (token != null && !token.isBlank()) {
                    runGit(targetDir, List.of("remote", "set-url", "origin", authenticatedUrl));
                }
                GitCommandResult fetchRes = runGit(targetDir, List.of("fetch", "origin", effectiveBranch));
                if (!fetchRes.isSuccess()) {
                    TrystLog.warn(GitService.class, "Fetch failed: " + maskOutput(fetchRes.error(), token));
                    return new GitCommandResult(fetchRes.exitCode(), maskOutput(fetchRes.output(), token), maskOutput(fetchRes.error(), token));
                }
                GitCommandResult checkoutRes = runGit(targetDir, List.of("checkout", effectiveBranch));
                return new GitCommandResult(checkoutRes.exitCode(), maskOutput(checkoutRes.output(), token), maskOutput(checkoutRes.error(), token));
            } else {
                // If directory exists but is incomplete (e.g., interrupted clone), clean up safely
                if (Files.exists(targetDir)) {
                    cleanDirectoryContents(targetDir);
                } else {
                    Files.createDirectories(targetDir);
                }

                TrystLog.info(GitService.class, "Cloning " + maskUrl(cloneUrl) + " (branch: " + effectiveBranch + ") into " + targetDir);
                Path parent = targetDir.getParent() != null ? targetDir.getParent() : targetDir;
                String dirName = targetDir.getFileName().toString();

                List<String> cloneArgs = new ArrayList<>();
                cloneArgs.add("clone");
                cloneArgs.add("--branch");
                cloneArgs.add(effectiveBranch);
                cloneArgs.add(authenticatedUrl);
                cloneArgs.add(dirName);

                GitCommandResult cloneRes = runGit(parent, cloneArgs);
                if (!cloneRes.isSuccess()) {
                    TrystLog.error(GitService.class, "Clone failed: " + maskOutput(cloneRes.error(), token));
                    return new GitCommandResult(cloneRes.exitCode(), maskOutput(cloneRes.output(), token), maskOutput(cloneRes.error(), token));
                }
                return new GitCommandResult(0, maskOutput(cloneRes.output(), token), "");
            }
        } catch (Exception e) {
            TrystLog.error(GitService.class, "Failed to clone/fetch repository: " + e.getMessage(), e);
            return new GitCommandResult(-1, "", maskOutput(e.getMessage(), token));
        }
    }

    public boolean hasRemoteConflicts(Path repoDir, String remote, String branch) {
        return hasRemoteConflicts(repoDir, remote, branch, null);
    }

    public String getRemoteConflictDetails(Path repoDir, String remote, String branch) {
        return getRemoteConflictDetails(repoDir, remote, branch, null);
    }

    public boolean hasRemoteConflicts(Path repoDir, String remote, String branch, String token) {
        if (!isGitRepository(repoDir)) return false;
        String effRemote = (remote != null && !remote.isBlank()) ? remote : "origin";
        String effBranch = (branch != null && !branch.isBlank()) ? branch : "main";

        // Fetch remote state
        GitCommandResult fetchRes = runGit(repoDir, List.of("fetch", effRemote, effBranch));
        if (!fetchRes.isSuccess()) {
            return false;
        }

        // Check if remote branch has diverged from local branch
        GitCommandResult revListRes = runGit(repoDir, List.of("rev-list", "--count", "HEAD.." + effRemote + "/" + effBranch));
        if (revListRes.isSuccess()) {
            try {
                int count = Integer.parseInt(revListRes.output().trim());
                return count > 0;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    public String getRemoteConflictDetails(Path repoDir, String remote, String branch, String token) {
        if (!isGitRepository(repoDir)) return null;
        String effRemote = (remote != null && !remote.isBlank()) ? remote : "origin";
        String effBranch = (branch != null && !branch.isBlank()) ? branch : "main";

        GitCommandResult revListRes = runGit(repoDir, List.of("rev-list", "--left-right", "--count", "HEAD..." + effRemote + "/" + effBranch));
        if (revListRes.isSuccess()) {
            String[] parts = revListRes.output().trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    int ahead = Integer.parseInt(parts[0]);
                    int behind = Integer.parseInt(parts[1]);
                    if (behind > 0) {
                        return "Local branch is " + ahead + " commit(s) ahead and " + behind + " commit(s) behind " + effRemote + "/" + effBranch + ". Rebase or pull required before push.";
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return "Remote branch " + effRemote + "/" + effBranch + " contains conflicting changes.";
    }

    public GitCommandResult push(Path repoDir, String remote, String branch, String token, boolean force) {
        Objects.requireNonNull(repoDir, "repoDir must not be null");
        String effRemote = (remote != null && !remote.isBlank()) ? remote : "origin";
        String effBranch = (branch != null && !branch.isBlank()) ? branch : "main";

        List<String> args = new ArrayList<>();
        args.add("push");
        if (force) {
            args.add("--force");
        }
        args.add(effRemote);
        args.add(effBranch);

        GitCommandResult res = runGit(repoDir, args);
        return new GitCommandResult(res.exitCode(), maskOutput(res.output(), token), maskOutput(res.error(), token));
    }

    public void cleanDirectoryContents(Path directory) {
        if (!Files.exists(directory)) return;
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .filter(p -> !p.equals(directory))
                .map(Path::toFile)
                .forEach(java.io.File::delete);
        } catch (Exception ignored) {}
    }

    public static String buildAuthenticatedUrl(String cloneUrl, String token) {
        if (token == null || token.isBlank() || cloneUrl == null) {
            return cloneUrl;
        }
        String cleanUrl = cloneUrl.trim();
        if (cleanUrl.startsWith("https://")) {
            return "https://" + token.trim() + "@" + cleanUrl.substring("https://".length());
        }
        return cleanUrl;
    }

    public static String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("https://[^@]+@", "https://****@");
    }

    public static String maskOutput(String text, String token) {
        if (text == null) return "";
        String res = maskUrl(text);
        if (token != null && !token.isBlank()) {
            res = res.replace(token.trim(), "********");
        }
        return res;
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

        // 1. Authorizer validation: No AI agent may independently approve or commit changes
        if (authorizerId == null || authorizerId.isBlank() || CodeReview.isAiAgent(authorizerId)) {
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: AI agents are strictly forbidden from committing changes. Human authorization required.");
        }

        if (!isGitRepository(repoDir)) {
            return new AuthorizedCommitResult(false, null, commitMessage, "Directory is not a valid Git repository: " + repoDir);
        }

        // 2. Failure recovery & duplicate commit prevention:
        // If already committed and HEAD matches the review commit hash, return idempotently without duplicate commits
        if (review.getStatus() == CodeReview.ReviewStatus.COMMITTED && review.getCommitHash() != null) {
            GitCommandResult headRes = runGit(repoDir, List.of("rev-parse", "HEAD"));
            if (headRes.isSuccess() && headRes.output().trim().equals(review.getCommitHash())) {
                return new AuthorizedCommitResult(true, review.getCommitHash(), commitMessage, null);
            }
        }

        if (!review.isApproved()) {
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Code review " + review.getId() + " is not approved (status: " + review.getStatus() + ")");
        }

        // 3. Git status must be checked immediately before committing
        GitCommandResult statusRes = getStatus(repoDir);
        if (!statusRes.isSuccess()) {
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Failed to check git status before committing: " + statusRes.error());
        }

        // 4. Verify that the current working tree diff matches the approved content fingerprint
        GitCommandResult diffRes = getDiff(repoDir);
        String currentDiff = diffRes.output();
        String currentFingerprint = CodeReview.calculateFingerprint(currentDiff);

        if (!currentFingerprint.equals(review.getApprovedFingerprint())) {
            // Concurrent workspace modification or tampered state: invalidate approval immediately
            review.invalidateApproval("Working tree was modified after approval: content fingerprint mismatch (" +
                    currentFingerprint + " vs approved " + review.getApprovedFingerprint() + ")");
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Working tree content fingerprint (" + currentFingerprint +
                            ") does not match approved review fingerprint (" + review.getApprovedFingerprint() +
                            "). Content was modified after approval. Approval has been invalidated.");
        }

        // 5. Stage all authorized changes
        GitCommandResult stageRes = stageAll(repoDir);
        if (!stageRes.isSuccess()) {
            return new AuthorizedCommitResult(false, null, commitMessage, "Failed to stage changes: " + stageRes.error());
        }

        // Verify staged diff matches approved fingerprint
        GitCommandResult stagedDiffRes = getDiffStaged(repoDir);
        String stagedDiff = stagedDiffRes.output();
        String stagedFingerprint = CodeReview.calculateFingerprint(stagedDiff);
        if (!stagedFingerprint.equals(review.getApprovedFingerprint())) {
            runGit(repoDir, List.of("reset"));
            review.invalidateApproval("Staged content fingerprint mismatch");
            return new AuthorizedCommitResult(false, null, commitMessage,
                    "Commit rejected: Staged content fingerprint does not match approved review fingerprint. Approval has been invalidated.");
        }

        String message = commitMessage != null && !commitMessage.isBlank()
                ? commitMessage
                : "TRYST: Approved changes for task " + review.getTaskId() + " (Review: " + review.getId() + ", ApprovedBy: " + authorizerId + ")";

        // 6. Safe commit execution
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
        // Prevent interactive terminal prompts (passwords/usernames) from hanging background workers
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");

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

            int exitCode = process.exitValue();
            String errStr = err.toString().trim();
            if (exitCode != 0 && !args.contains("status")) {
                TrystLog.warn(GitService.class, "Git command failed (exit " + exitCode + "): " + args + " -> " + errStr);
            }
            return new GitCommandResult(exitCode, out.toString().trim(), errStr);
        } catch (Exception e) {
            TrystLog.error(GitService.class, "Git execution failed: " + args, e);
            return new GitCommandResult(-1, "", e.getMessage());
        }
    }
}
