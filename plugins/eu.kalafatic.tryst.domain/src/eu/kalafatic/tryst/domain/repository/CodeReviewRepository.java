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

    void saveRevision(eu.kalafatic.tryst.domain.review.ReviewRevision revision);

    List<eu.kalafatic.tryst.domain.review.ReviewRevision> findRevisionsByReviewId(String reviewId);

    Optional<eu.kalafatic.tryst.domain.review.ReviewRevision> findRevisionByNumber(String reviewId, int revisionNumber);

    void saveComment(eu.kalafatic.tryst.domain.review.ReviewComment comment);

    Optional<eu.kalafatic.tryst.domain.review.ReviewComment> findCommentById(String id);

    List<eu.kalafatic.tryst.domain.review.ReviewComment> findCommentsByReviewId(String reviewId);

    List<eu.kalafatic.tryst.domain.review.ReviewComment> findCommentsByTaskId(String taskId);

    boolean deleteComment(String id);
}
