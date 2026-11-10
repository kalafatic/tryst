package eu.kalafatic.tryst.infrastructure.vcs;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository Pool manager for caching and reusing Git repositories via bare mirrors
 * and isolated git worktrees. Avoids repeatedly downloading entire repositories
 * across multiple tasks or workers while strictly isolating writable workspaces.
 */
public class RepositoryPoolManager {

    private final Path mirrorsBaseDir;
    private final GitService gitService;
    private final Map<String, Object> mirrorLocks = new ConcurrentHashMap<>();

    public RepositoryPoolManager(Path mirrorsBaseDir, GitService gitService) {
        this.mirrorsBaseDir = Objects.requireNonNull(mirrorsBaseDir, "mirrorsBaseDir must not be null");
        this.gitService = Objects.requireNonNull(gitService, "gitService must not be null");
        try {
            Files.createDirectories(mirrorsBaseDir);
        } catch (IOException e) {
            throw new TrystInfrastructureException("Failed to create mirrors directory: " + mirrorsBaseDir, e);
        }
    }

    /**
     * Obtains a mirror directory for a repository URL, creating or fetching updates as necessary.
     */
    public Path ensureMirror(String repoUrl, String token) {
        Objects.requireNonNull(repoUrl, "repoUrl must not be null");
        String safeName = sanitizeRepoUrl(repoUrl);
        Path mirrorDir = mirrorsBaseDir.resolve(safeName + ".git");
        Object lock = mirrorLocks.computeIfAbsent(safeName, k -> new Object());

        synchronized (lock) {
            String authenticatedUrl = GitService.buildAuthenticatedUrl(repoUrl, token);
            if (!Files.exists(mirrorDir) || !Files.exists(mirrorDir.resolve("config"))) {
                TrystLog.info(RepositoryPoolManager.class, "Creating bare mirror for " + GitService.maskUrl(repoUrl) + " at " + mirrorDir);
                try {
                    Files.createDirectories(mirrorDir.getParent());
                } catch (IOException ignored) {}

                GitService.GitCommandResult res = gitService.runGit(mirrorsBaseDir,
                        List.of("clone", "--mirror", authenticatedUrl, mirrorDir.getFileName().toString()));
                if (!res.isSuccess()) {
                    TrystLog.error(RepositoryPoolManager.class, "Failed to create bare mirror: " + GitService.maskOutput(res.error(), token));
                    throw new TrystInfrastructureException("Failed to create bare mirror: " + GitService.maskOutput(res.error(), token));
                }
            } else {
                TrystLog.info(RepositoryPoolManager.class, "Updating bare mirror at " + mirrorDir);
                GitService.GitCommandResult res = gitService.runGit(mirrorDir, List.of("remote", "update"));
                if (!res.isSuccess()) {
                    TrystLog.warn(RepositoryPoolManager.class, "Failed to update mirror: " + GitService.maskOutput(res.error(), token));
                }
            }
            recordMirrorPersistence(repoUrl, mirrorDir);
        }
        return mirrorDir;
    }

    private eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager databaseManager;

    public void setDatabaseManager(eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    private void recordMirrorPersistence(String repoUrl, Path mirrorDir) {
        if (databaseManager == null) return;
        synchronized (databaseManager.getLock()) {
            try {
                var conn = databaseManager.getConnection();
                try (var stmt = conn.prepareStatement(
                        "INSERT INTO repository_mirrors (id, repository_url, mirror_path, last_sync_at, status) " +
                        "VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(repository_url) DO UPDATE SET mirror_path = excluded.mirror_path, last_sync_at = excluded.last_sync_at, status = excluded.status")) {
                    stmt.setString(1, java.util.UUID.randomUUID().toString());
                    stmt.setString(2, repoUrl);
                    stmt.setString(3, mirrorDir.toAbsolutePath().toString());
                    stmt.setString(4, java.time.Instant.now().toString());
                    stmt.setString(5, "READY");
                    stmt.executeUpdate();
                }
            } catch (Exception e) {
                TrystLog.warn(RepositoryPoolManager.class, "Failed to persist repository mirror metadata: " + e.getMessage());
            }
        }
    }

    /**
     * Creates an isolated workspace by creating a git worktree or lightweight clone from the bare mirror.
     */
    public boolean createWorktree(Path mirrorDir, Path targetWorkspaceDir, String branch) {
        Objects.requireNonNull(mirrorDir, "mirrorDir must not be null");
        Objects.requireNonNull(targetWorkspaceDir, "targetWorkspaceDir must not be null");

        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        try {
            Files.createDirectories(targetWorkspaceDir.getParent());
            List<String> cloneArgs = new ArrayList<>();
            cloneArgs.add("clone");
            cloneArgs.add("--branch");
            cloneArgs.add(effectiveBranch);
            cloneArgs.add("--reference");
            cloneArgs.add(mirrorDir.toString());
            cloneArgs.add(mirrorDir.toString());
            cloneArgs.add(targetWorkspaceDir.getFileName().toString());

            // Clone from local mirror referencing shared objects
            GitService.GitCommandResult res = gitService.runGit(targetWorkspaceDir.getParent(), cloneArgs);
            if (!res.isSuccess()) {
                // If branch checkout in clone failed, attempt without explicit branch
                res = gitService.runGit(targetWorkspaceDir.getParent(),
                        List.of("clone", "--reference", mirrorDir.toString(), mirrorDir.toString(), targetWorkspaceDir.getFileName().toString()));
                if (!res.isSuccess()) {
                    TrystLog.error(RepositoryPoolManager.class, "Failed to clone from mirror: " + res.error());
                    return false;
                }
            }
            // Ensure checkout of requested branch if available, otherwise stay on default branch
            GitService.GitCommandResult chkRes = gitService.runGit(targetWorkspaceDir, List.of("checkout", effectiveBranch));
            if (!chkRes.isSuccess()) {
                TrystLog.info(RepositoryPoolManager.class, "Requested branch '" + effectiveBranch + "' not found; remaining on default clone branch.");
            }
            return true;
        } catch (Exception e) {
            TrystLog.error(RepositoryPoolManager.class, "Failed to create worktree workspace: " + e.getMessage(), e);
            return false;
        }
    }


    public Path getMirrorsBaseDir() {
        return mirrorsBaseDir;
    }

    public List<String> listMirrors() {
        if (!java.nio.file.Files.exists(mirrorsBaseDir)) return List.of();
        try (var s = java.nio.file.Files.list(mirrorsBaseDir)) {
            return s.filter(java.nio.file.Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private String sanitizeRepoUrl(String url) {
        return url.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
