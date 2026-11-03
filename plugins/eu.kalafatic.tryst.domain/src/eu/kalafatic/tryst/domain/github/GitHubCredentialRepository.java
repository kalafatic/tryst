package eu.kalafatic.tryst.domain.github;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing persistent GitHubCredential entities.
 */
public interface GitHubCredentialRepository {

    void save(GitHubCredential credential);

    Optional<GitHubCredential> findById(String id);

    Optional<GitHubCredential> findByUserId(String userId);

    Optional<GitHubCredential> findByUsername(String githubUsername);

    List<GitHubCredential> findAll();

    void deleteByUserId(String userId);

    void delete(String id);
}
