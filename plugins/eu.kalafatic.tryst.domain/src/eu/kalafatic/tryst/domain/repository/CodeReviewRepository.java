package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persistent storage and retrieval of CodeReview and ReviewFileEdit entities.
 */
public interface CodeReviewRepository {

    void save(CodeReview review);

    Optional<CodeReview> findById(String id);

    Optional<CodeReview> findByTaskId(String taskId);

    List<CodeReview> findByProjectId(String projectId);

    List<CodeReview> findAll();

    boolean delete(String id);

    void saveFileEdit(ReviewFileEdit edit);

    List<ReviewFileEdit> findFileEditsByReviewId(String reviewId);

    Optional<ReviewFileEdit> findFileEdit(String reviewId, String filePath);
}
