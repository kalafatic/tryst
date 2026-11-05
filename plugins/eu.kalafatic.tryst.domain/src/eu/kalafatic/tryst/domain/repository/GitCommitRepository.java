package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.review.GitCommitRecord;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing persistent Git commit records and traceability metadata.
 */
public interface GitCommitRepository {

    void save(GitCommitRecord record);

    Optional<GitCommitRecord> findById(String id);

    Optional<GitCommitRecord> findByCommitHash(String commitHash);

    List<GitCommitRecord> findByReviewId(String reviewId);

    List<GitCommitRecord> findByProjectId(String projectId);

    List<GitCommitRecord> findByTaskId(String taskId);

    List<GitCommitRecord> findAll();

    boolean delete(String id);
}
