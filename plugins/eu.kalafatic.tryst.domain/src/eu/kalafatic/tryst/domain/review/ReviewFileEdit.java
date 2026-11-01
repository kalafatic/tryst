package eu.kalafatic.tryst.domain.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an individual file edit performed during interactive code review.
 */
public class ReviewFileEdit {

    private final String id;
    private final String reviewId;
    private final String filePath;
    private final String originalContent;
    private String modifiedContent;
    private Instant updatedAt;

    public ReviewFileEdit(String reviewId, String filePath, String originalContent, String modifiedContent) {
        this(UUID.randomUUID().toString(), reviewId, filePath, originalContent, modifiedContent, Instant.now());
    }

    public ReviewFileEdit(String id, String reviewId, String filePath, String originalContent, String modifiedContent, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.filePath = Objects.requireNonNull(filePath, "filePath must not be null");
        this.originalContent = originalContent != null ? originalContent : "";
        this.modifiedContent = modifiedContent != null ? modifiedContent : "";
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String getId() { return id; }
    public String getReviewId() { return reviewId; }
    public String getFilePath() { return filePath; }
    public String getOriginalContent() { return originalContent; }
    public String getModifiedContent() { return modifiedContent; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateContent(String newContent) {
        this.modifiedContent = newContent != null ? newContent : "";
        this.updatedAt = Instant.now();
    }
}
