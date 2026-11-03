package eu.kalafatic.tryst.domain.review;

import java.time.Instant;
import java.util.Objects;

public class ReviewRevision {

    private final String id;
    private final String reviewId;
    private final int revisionNumber;
    private final String diff;
    private final String explanation;
    private final String buildStatus;
    private final String testSummary;
    private final Instant createdAt;

    public ReviewRevision(String id, String reviewId, int revisionNumber, String diff,
                          String explanation, String buildStatus, String testSummary, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.revisionNumber = revisionNumber;
        this.diff = diff != null ? diff : "";
        this.explanation = explanation != null ? explanation : "";
        this.buildStatus = buildStatus != null ? buildStatus : "UNKNOWN";
        this.testSummary = testSummary != null ? testSummary : "";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getReviewId() {
        return reviewId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getDiff() {
        return diff;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getBuildStatus() {
        return buildStatus;
    }

    public String getTestSummary() {
        return testSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
