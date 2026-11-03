package eu.kalafatic.tryst.domain.github;

import java.util.Objects;

/**
 * Value record representing a discovered branch in a GitHub repository.
 */
public record GitHubBranch(
        String name,
        String commitSha,
        boolean isProtected
) {
    public GitHubBranch {
        Objects.requireNonNull(name, "branch name must not be null");
        Objects.requireNonNull(commitSha, "commitSha must not be null");
    }
}
