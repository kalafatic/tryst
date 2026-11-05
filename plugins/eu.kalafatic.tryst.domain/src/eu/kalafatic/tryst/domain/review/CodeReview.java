package eu.kalafatic.tryst.domain.review;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity representing an interactive Code Review package produced by the autonomous lifecycle.
 * Holds initial diff, effective diff after user edits, content fingerprints, and explicit approval decisions.
 */
public class CodeReview {

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED,
        COMMITTED
    }

    private final String id;
    private final String projectId;
    private final String taskId;
    private final String branch;
    private ReviewStatus status;
    private String initialDiff;
    private String effectiveDiff;
    private String contentFingerprint;
    private String approvedFingerprint;
    private Integer approvedRevision;
    private String commitHash;
    private String approvedBy;
    private String rejectedBy;
    private String rejectionReason;
    private String buildStatus;
    private String testSummary;
    private String aiExplanation;
    private int revisionCount;
    private final Instant createdAt;
    private Instant updatedAt;

    public CodeReview(String projectId, String taskId, String branch, String initialDiff, String buildStatus, String testSummary) {
        this(UUID.randomUUID().toString(), projectId, taskId, branch, ReviewStatus.PENDING,
                initialDiff, initialDiff, calculateFingerprint(initialDiff), null, null, null, null, null, null,
                buildStatus, testSummary, "", 1, Instant.now(), Instant.now());
    }

    public CodeReview(String id, String projectId, String taskId, String branch, ReviewStatus status,
                      String initialDiff, String effectiveDiff, String contentFingerprint, String approvedFingerprint,
                      String commitHash, String approvedBy, String rejectedBy, String rejectionReason,
                      String buildStatus, String testSummary, Instant createdAt, Instant updatedAt) {
        this(id, projectId, taskId, branch, status, initialDiff, effectiveDiff, contentFingerprint, approvedFingerprint,
                null, commitHash, approvedBy, rejectedBy, rejectionReason, buildStatus, testSummary, "", 1, createdAt, updatedAt);
    }

    public CodeReview(String id, String projectId, String taskId, String branch, ReviewStatus status,
                      String initialDiff, String effectiveDiff, String contentFingerprint, String approvedFingerprint,
                      String commitHash, String approvedBy, String rejectedBy, String rejectionReason,
                      String buildStatus, String testSummary, String aiExplanation, int revisionCount,
                      Instant createdAt, Instant updatedAt) {
        this(id, projectId, taskId, branch, status, initialDiff, effectiveDiff, contentFingerprint, approvedFingerprint,
                null, commitHash, approvedBy, rejectedBy, rejectionReason, buildStatus, testSummary, aiExplanation,
                revisionCount, createdAt, updatedAt);
    }

    public CodeReview(String id, String projectId, String taskId, String branch, ReviewStatus status,
                      String initialDiff, String effectiveDiff, String contentFingerprint, String approvedFingerprint,
                      Integer approvedRevision, String commitHash, String approvedBy, String rejectedBy,
                      String rejectionReason, String buildStatus, String testSummary, String aiExplanation,
                      int revisionCount, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.initialDiff = initialDiff != null ? initialDiff : "";
        this.effectiveDiff = effectiveDiff != null ? effectiveDiff : this.initialDiff;
        this.contentFingerprint = contentFingerprint != null ? contentFingerprint : calculateFingerprint(this.effectiveDiff);
        this.approvedFingerprint = approvedFingerprint;
        this.approvedRevision = approvedRevision;
        this.commitHash = commitHash;
        this.approvedBy = approvedBy;
        this.rejectedBy = rejectedBy;
        this.rejectionReason = rejectionReason;
        this.buildStatus = buildStatus != null ? buildStatus : "SUCCESS";
        this.testSummary = testSummary != null ? testSummary : "";
        this.aiExplanation = aiExplanation != null ? aiExplanation : "";
        this.revisionCount = Math.max(1, revisionCount);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static boolean isAiAgent(String actorId) {
        if (actorId == null || actorId.isBlank()) return false;
        String id = actorId.toLowerCase().trim();
        return id.startsWith("agent") || id.startsWith("ai-") || id.startsWith("bot") || id.contains("agent") || id.contains("worker");
    }

    public static String calculateFingerprint(String content) {
        if (content == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getTaskId() { return taskId; }
    public String getBranch() { return branch; }
    public ReviewStatus getStatus() { return status; }
    public String getInitialDiff() { return initialDiff; }
    public String getEffectiveDiff() { return effectiveDiff; }
    public String getContentFingerprint() { return contentFingerprint; }
    public String getApprovedFingerprint() { return approvedFingerprint; }
    public Integer getApprovedRevision() { return approvedRevision; }
    public String getCommitHash() { return commitHash; }
    public String getApprovedBy() { return approvedBy; }
    public String getRejectedBy() { return rejectedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public String getBuildStatus() { return buildStatus; }
    public String getTestSummary() { return testSummary; }
    public String getAiExplanation() { return aiExplanation; }
    public int getRevisionCount() { return revisionCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public synchronized void setAiExplanation(String aiExplanation) {
        this.aiExplanation = aiExplanation != null ? aiExplanation : "";
        this.updatedAt = Instant.now();
    }

    public synchronized void setRevisionCount(int revisionCount) {
        this.revisionCount = revisionCount;
        this.updatedAt = Instant.now();
    }

    public synchronized void incrementRevisionCount() {
        this.revisionCount++;
        if (this.status == ReviewStatus.APPROVED) {
            invalidateApproval("New revision created: previous approval invalidated");
        }
        this.updatedAt = Instant.now();
    }

    public synchronized void setApprovedRevision(Integer approvedRevision) {
        this.approvedRevision = approvedRevision;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the effective diff when a user or tool modifies files in review.
     * Invalidates any previous approval if effective content changes.
     */
    public synchronized void updateEffectiveDiff(String newDiff) {
        this.effectiveDiff = newDiff != null ? newDiff : "";
        String newFingerprint = calculateFingerprint(this.effectiveDiff);
        if (!newFingerprint.equals(this.contentFingerprint)) {
            this.contentFingerprint = newFingerprint;
            if (this.status == ReviewStatus.APPROVED) {
                invalidateApproval("Content modified: fingerprint changed to " + newFingerprint);
            }
        }
        this.updatedAt = Instant.now();
    }

    public synchronized void invalidateApproval(String reason) {
        if (this.status == ReviewStatus.APPROVED) {
            this.status = ReviewStatus.PENDING;
            this.approvedBy = null;
            this.approvedRevision = null;
            this.approvedFingerprint = null;
            this.updatedAt = Instant.now();
        }
    }

    public synchronized void updateBuildAndTestStatus(String buildStatus, String testSummary) {
        this.buildStatus = buildStatus;
        this.testSummary = testSummary;
        this.updatedAt = Instant.now();
    }

    public synchronized void approve(String userId, int revisionNumber, java.util.List<ReviewComment> comments) {
        if (comments != null) {
            for (ReviewComment comment : comments) {
                if (comment.isBlocking() && !comment.isResolved()) {
                    throw new TrystDomainException("Cannot approve review with unresolved blocking comments. Comment ID: " + comment.getId());
                }
            }
        }
        approve(userId, revisionNumber);
    }

    public synchronized void approve(String userId, int revisionNumber) {
        if (userId == null || userId.isBlank() || isAiAgent(userId)) {
            throw new TrystDomainException("AI agents are strictly forbidden from approving reviews or committing changes.");
        }
        if (revisionNumber != this.revisionCount) {
            throw new TrystDomainException("Stale approval rejected: Cannot approve revision " + revisionNumber +
                    " because current revision is " + this.revisionCount + ".");
        }
        this.status = ReviewStatus.APPROVED;
        this.approvedBy = userId.trim();
        this.approvedRevision = revisionNumber;
        this.approvedFingerprint = this.contentFingerprint;
        this.updatedAt = Instant.now();
    }

    public synchronized void approve(String userId) {
        approve(userId, this.revisionCount);
    }

    public synchronized void reject(String userId, String reason) {
        this.status = ReviewStatus.REJECTED;
        this.rejectedBy = userId != null ? userId : "user";
        this.rejectionReason = reason != null ? reason : "Changes rejected by reviewer.";
        this.approvedBy = null;
        this.approvedRevision = null;
        this.approvedFingerprint = null;
        this.updatedAt = Instant.now();
    }

    public synchronized void recordCommit(String commitHash) {
        this.status = ReviewStatus.COMMITTED;
        this.commitHash = commitHash;
        this.updatedAt = Instant.now();
    }

    public boolean isApproved() {
        return this.status == ReviewStatus.APPROVED
                && this.approvedRevision != null
                && this.approvedRevision == this.revisionCount
                && this.contentFingerprint != null
                && this.contentFingerprint.equals(this.approvedFingerprint);
    }
}
