package eu.kalafatic.tryst.domain.github;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.CodeReview;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Domain service contract for interacting with GitHub:
 * account connection, repository discovery, branch listing, token revocation,
 * isolated sandbox cloning, and user-authorized pushing.
 */
public interface GitHubService {

    record GitHubUser(String login, String name, String email, String avatarUrl) {}

    record PushResult(boolean success, String remote, String branch, String commitHash, String message, String error) {}

    /**
     * Validates a GitHub token against GitHub's API.
     * Returns user profile information on success.
     */
    GitHubUser validateToken(String token);

    /**
     * Connects a GitHub account for the given TRYST user, encrypts and stores the credential.
     */
    GitHubCredential connectAccount(String userId, String token);

    /**
     * Disconnects / revokes the GitHub connection for the given user.
     */
    void disconnectAccount(String userId);

    /**
     * Returns the connected credential for the user, if any.
     */
    Optional<GitHubCredential> getCredentialForUser(String userId);

    /**
     * Discovers all repositories (public and private) accessible to the user.
     */
    List<GitHubRepository> listRepositories(String userId);

    /**
     * Discovers branches for a given repository.
     */
    List<GitHubBranch> listBranches(String userId, String owner, String repo);

    /**
     * Clones or fetches a GitHub repository into the task-assigned isolated sandbox.
     */
    void cloneOrFetchRepository(String userId, String cloneUrl, String branch, Path targetDir);

    /**
     * Performs an explicit user-authorized Git push to remote.
     * Must verify that the code review is APPROVED before pushing.
     */
    PushResult pushAuthorized(String userId, Path repoDir, CodeReview review, String remote, String branch, boolean force);
}
