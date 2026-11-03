package eu.kalafatic.tryst.domain.github;

import java.util.Objects;

/**
 * Value record representing a discovered GitHub repository.
 */
public record GitHubRepository(
        String id,
        String fullName,
        String owner,
        String name,
        boolean isPrivate,
        String defaultBranch,
        String cloneUrl,
        String htmlUrl,
        String description
) {
    public GitHubRepository {
        Objects.requireNonNull(fullName, "fullName must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(cloneUrl, "cloneUrl must not be null");
    }
}
