package eu.kalafatic.tryst.infrastructure.github;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.github.*;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java 21 implementation of GitHubService using standard java.net.http.HttpClient.
 * Interacts with GitHub REST API v3, managing token validation, discovery,
 * isolated Git operations via GitService, and user-authorized pushes.
 */
public class DefaultGitHubService implements GitHubService {

    private static final String DEFAULT_API_BASE = "https://api.github.com";
    private final String apiBase;
    private final GitHubCredentialRepository credentialRepository;
    private final GitHubCredentialEncryptionService encryptionService;
    private final GitService gitService;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager repositoryPoolManager;

    public DefaultGitHubService(GitHubCredentialRepository credentialRepository,
                                GitHubCredentialEncryptionService encryptionService,
                                GitService gitService) {
        this(DEFAULT_API_BASE, credentialRepository, encryptionService, gitService, Duration.ofSeconds(30));
    }

    public void setRepositoryPoolManager(eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager repositoryPoolManager) {
        this.repositoryPoolManager = repositoryPoolManager;
    }

    public eu.kalafatic.tryst.infrastructure.vcs.RepositoryPoolManager getRepositoryPoolManager() {
        return repositoryPoolManager;
    }

    public DefaultGitHubService(String apiBase,
                                GitHubCredentialRepository credentialRepository,
                                GitHubCredentialEncryptionService encryptionService,
                                GitService gitService,
                                Duration requestTimeout) {
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase must not be null").replaceAll("/+$", "");
        this.credentialRepository = Objects.requireNonNull(credentialRepository, "credentialRepository must not be null");
        this.encryptionService = Objects.requireNonNull(encryptionService, "encryptionService must not be null");
        this.gitService = Objects.requireNonNull(gitService, "gitService must not be null");
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public GitHubUser validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new TrystDomainException("GitHub personal access token must not be blank");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/user"))
                .header("Authorization", "Bearer " + token.trim())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TRYST-Autonomous-Agent")
                .timeout(requestTimeout)
                .GET()
                .build();

        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new TrystDomainException("GitHub token validation failed: Invalid or expired token (HTTP " + res.statusCode() + ")");
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new TrystInfrastructureException("GitHub API returned error: HTTP " + res.statusCode() + " " + res.body());
            }

            String body = res.body();
            String login = extractJsonField(body, "login");
            String name = extractJsonField(body, "name");
            String email = extractJsonField(body, "email");
            String avatarUrl = extractJsonField(body, "avatar_url");

            if (login == null || login.isBlank()) {
                throw new TrystInfrastructureException("GitHub API response missing login field");
            }

            return new GitHubUser(login, name != null ? name : login, email, avatarUrl);
        } catch (TrystDomainException | TrystInfrastructureException e) {
            throw e;
        } catch (Exception e) {
            throw new TrystInfrastructureException("Error connecting to GitHub API: " + e.getMessage(), e);
        }
    }

    @Override
    public GitHubCredential connectAccount(String userId, String token) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(token, "token must not be null");

        // Validate token with GitHub
        GitHubUser user = validateToken(token);

        String encrypted = encryptionService.encrypt(token.trim());
        String masked = encryptionService.maskToken(token.trim());
        TrystLog.info(DefaultGitHubService.class, "Connecting GitHub account @" + user.login() + " for TRYST user " + userId + " (Token: " + masked + ")");

        Optional<GitHubCredential> existing = credentialRepository.findByUserId(userId);
        GitHubCredential credential;
        if (existing.isPresent()) {
            credential = existing.get();
            credential.updateGithubUsername(user.login());
            credential.updateCredential(encrypted, "repo,read:user", null);
        } else {
            credential = new GitHubCredential(
                    userId,
                    user.login(),
                    encrypted,
                    "repo,read:user",
                    "pat",
                    null
            );
        }

        credentialRepository.save(credential);
        return credential;
    }

    @Override
    public void disconnectAccount(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        TrystLog.info(DefaultGitHubService.class, "Disconnecting GitHub account for TRYST user " + userId);
        credentialRepository.deleteByUserId(userId);
    }

    @Override
    public Optional<GitHubCredential> getCredentialForUser(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return credentialRepository.findByUserId(userId);
    }

    @Override
    public List<GitHubRepository> listRepositories(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        GitHubCredential cred = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new TrystDomainException("No GitHub account connected for user " + userId));

        String token = encryptionService.decrypt(cred.getEncryptedToken());

        List<GitHubRepository> allRepos = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        while (page <= 5) { // Support up to 500 repos
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/user/repos?per_page=" + perPage + "&page=" + page + "&sort=updated&affiliation=owner,collaborator,organization_member"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "TRYST-Autonomous-Agent")
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            try {
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 401) {
                    throw new TrystDomainException("GitHub token is invalid or expired. Please re-authenticate.");
                }
                if (res.statusCode() != 200) {
                    throw new TrystInfrastructureException("Failed to list GitHub repositories: HTTP " + res.statusCode() + " " + res.body());
                }

                List<GitHubRepository> pageRepos = parseRepositoriesJson(res.body());
                if (pageRepos.isEmpty()) {
                    break;
                }
                allRepos.addAll(pageRepos);
                if (pageRepos.size() < perPage) {
                    break;
                }
                page++;
            } catch (TrystDomainException | TrystInfrastructureException e) {
                throw e;
            } catch (Exception e) {
                throw new TrystInfrastructureException("Error fetching repositories from GitHub: " + e.getMessage(), e);
            }
        }

        return allRepos;
    }

    @Override
    public List<GitHubBranch> listBranches(String userId, String owner, String repo) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(repo, "repo must not be null");

        GitHubCredential cred = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new TrystDomainException("No GitHub account connected for user " + userId));

        String token = encryptionService.decrypt(cred.getEncryptedToken());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/repos/" + owner + "/" + repo + "/branches?per_page=100"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TRYST-Autonomous-Agent")
                .timeout(requestTimeout)
                .GET()
                .build();

        try {
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) {
                throw new TrystDomainException("GitHub token is invalid or expired.");
            }
            if (res.statusCode() != 200) {
                throw new TrystInfrastructureException("Failed to list branches for " + owner + "/" + repo + ": HTTP " + res.statusCode());
            }
            return parseBranchesJson(res.body());
        } catch (TrystDomainException | TrystInfrastructureException e) {
            throw e;
        } catch (Exception e) {
            throw new TrystInfrastructureException("Error fetching branches from GitHub: " + e.getMessage(), e);
        }
    }

    @Override
    public void cloneOrFetchRepository(String userId, String cloneUrl, String branch, Path targetDir,
                                       boolean forceCleanClone, boolean pullBeforeExecution) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(cloneUrl, "cloneUrl must not be null");
        Objects.requireNonNull(targetDir, "targetDir must not be null");

        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";

        // Retrieve token if user is connected
        String token = null;
        Optional<GitHubCredential> optCred = credentialRepository.findByUserId(userId);
        if (optCred.isPresent()) {
            token = encryptionService.decrypt(optCred.get().getEncryptedToken());
        }

        if (forceCleanClone) {
            TrystLog.info(DefaultGitHubService.class, "Force clean clone requested for " + cloneUrl + " in " + targetDir);
            gitService.cleanDirectoryContents(targetDir);
        }

        TrystLog.info(DefaultGitHubService.class, "Executing clone or fetch for repository " + cloneUrl + " into sandbox " + targetDir);
        if (!forceCleanClone && repositoryPoolManager != null && !gitService.isGitRepository(targetDir)) {
            try {
                Path mirror = repositoryPoolManager.ensureMirror(cloneUrl, token);
                boolean ok = repositoryPoolManager.createWorktree(mirror, targetDir, effectiveBranch);
                if (ok) {
                    TrystLog.info(DefaultGitHubService.class, "Successfully allocated isolated workspace from bare mirror for " + cloneUrl);
                    if (pullBeforeExecution) {
                        GitService.GitCommandResult pullRes = gitService.pull(targetDir, "origin", effectiveBranch, token);
                        if (!pullRes.isSuccess()) {
                            throw new TrystInfrastructureException("Git pull before execution failed: " + pullRes.error());
                        }
                    }
                    return;
                }
            } catch (TrystInfrastructureException e) {
                throw e;
            } catch (Exception e) {
                TrystLog.warn(DefaultGitHubService.class, "Mirror-assisted checkout failed; falling back to direct clone/fetch: " + e.getMessage());
            }
        }

        GitService.GitCommandResult result = gitService.cloneOrFetch(targetDir, cloneUrl, effectiveBranch, token);
        if (!result.isSuccess()) {
            throw new TrystInfrastructureException("Git clone/fetch failed: " + result.error());
        }

        if (pullBeforeExecution) {
            TrystLog.info(DefaultGitHubService.class, "Pulling latest changes before execution for " + targetDir);
            GitService.GitCommandResult pullRes = gitService.pull(targetDir, "origin", effectiveBranch, token);
            if (!pullRes.isSuccess()) {
                throw new TrystInfrastructureException("Git pull before execution failed: " + pullRes.error());
            }
        }
    }

    @Override
    public PushResult pushAuthorized(String userId, Path repoDir, CodeReview review, String remote, String branch, boolean force) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(repoDir, "repoDir must not be null");
        Objects.requireNonNull(review, "review must not be null");

        if (review.getStatus() != CodeReview.ReviewStatus.COMMITTED && !review.isApproved()) {
            throw new TrystDomainException("Push rejected: Code review " + review.getId() + " is not committed or approved");
        }

        String effectiveRemote = (remote != null && !remote.isBlank()) ? remote : "origin";
        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : review.getBranch();

        GitHubCredential cred = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> new TrystDomainException("Cannot push: User " + userId + " does not have a connected GitHub account"));

        String token = encryptionService.decrypt(cred.getEncryptedToken());

        // Check for remote conflicts before pushing
        if (!force && gitService.hasRemoteConflicts(repoDir, effectiveRemote, effectiveBranch, token)) {
            return new PushResult(false, effectiveRemote, effectiveBranch, null,
                    "Push rejected: Remote branch has conflicting changes. Rebase or pull required.",
                    "Remote conflicts detected");
        }

        GitService.GitCommandResult pushResult = gitService.push(repoDir, effectiveRemote, effectiveBranch, token, force);
        if (!pushResult.isSuccess()) {
            return new PushResult(false, effectiveRemote, effectiveBranch, null,
                    "Git push failed: " + pushResult.error(), pushResult.error());
        }

        // Get commit hash pushed
        GitService.GitCommandResult revRes = gitService.runGit(repoDir, List.of("rev-parse", "HEAD"));
        String head = revRes.isSuccess() ? revRes.output().trim() : "UNKNOWN";

        return new PushResult(true, effectiveRemote, effectiveBranch, head,
                "Successfully pushed commit " + head + " to " + effectiveRemote + "/" + effectiveBranch, null);
    }

    private static String extractJsonField(String json, String field) {
        if (json == null) return null;
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }

    private static List<GitHubRepository> parseRepositoriesJson(String json) {
        List<GitHubRepository> repos = new ArrayList<>();
        if (json == null || json.isBlank() || json.trim().equals("[]")) {
            return repos;
        }

        // Splitting objects by scanning JSON blocks or regex
        // Each repository object has "id", "name", "full_name", "private", "clone_url", "html_url", "default_branch"
        Pattern p = Pattern.compile("\\{[^{}]+(?:\\{[^{}]*\\}[^{}]*)*\\}");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String obj = m.group();
            String id = extractJsonNumberOrString(obj, "id");
            String fullName = extractJsonField(obj, "full_name");
            String name = extractJsonField(obj, "name");
            String cloneUrl = extractJsonField(obj, "clone_url");
            String htmlUrl = extractJsonField(obj, "html_url");
            String defaultBranch = extractJsonField(obj, "default_branch");
            String description = extractJsonField(obj, "description");
            boolean isPrivate = obj.contains("\"private\":true") || obj.contains("\"private\": true");

            if (fullName != null && cloneUrl != null) {
                String owner = fullName.contains("/") ? fullName.substring(0, fullName.indexOf('/')) : "unknown";
                repos.add(new GitHubRepository(
                        id != null ? id : fullName,
                        fullName,
                        owner,
                        name != null ? name : fullName,
                        isPrivate,
                        defaultBranch != null ? defaultBranch : "main",
                        cloneUrl,
                        htmlUrl != null ? htmlUrl : cloneUrl,
                        description != null ? description : ""
                ));
            }
        }
        return repos;
    }

    private static List<GitHubBranch> parseBranchesJson(String json) {
        List<GitHubBranch> branches = new ArrayList<>();
        if (json == null || json.isBlank() || json.trim().equals("[]")) {
            return branches;
        }

        // Branch objects typically have: {"name":"main","commit":{"sha":"...","url":"..."},"protected":false}
        Pattern p = Pattern.compile("\\{[^{}]+(?:\\{[^{}]*\\}[^{}]*)*\\}");
        Matcher m = p.matcher(json);
        while (m.find()) {
            String obj = m.group();
            String name = extractJsonField(obj, "name");
            String sha = extractJsonField(obj, "sha");
            boolean isProtected = obj.contains("\"protected\":true") || obj.contains("\"protected\": true");

            if (name != null) {
                branches.add(new GitHubBranch(name, sha != null ? sha : "HEAD", isProtected));
            }
        }
        return branches;
    }

    private static String extractJsonNumberOrString(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9]+|\"[^\"]*\")");
        Matcher m = p.matcher(json);
        if (m.find()) {
            String val = m.group(1);
            if (val.startsWith("\"") && val.endsWith("\"")) {
                return val.substring(1, val.length() - 1);
            }
            return val;
        }
        return null;
    }
}
