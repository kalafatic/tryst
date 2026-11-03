package eu.kalafatic.tryst.domain.review;

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
                initialDiff, initialDiff, calculateFingerprint(initialDiff), null, null, null, null, null,
                buildStatus, testSummary, Instant.now(), Instant.now());
    }

    public CodeReview(String id, String projectId, String taskId, String branch, ReviewStatus status,
                      String initialDiff, String effectiveDiff, String contentFingerprint, String approvedFingerprint,
                      String commitHash, String approvedBy, String rejectedBy, String rejectionReason,
                      String buildStatus, String testSummary, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.initialDiff = initialDiff != null ? initialDiff : "";
        this.effectiveDiff = effectiveDiff != null ? effectiveDiff : this.initialDiff;
        this.contentFingerprint = contentFingerprint != null ? contentFingerprint : calculateFingerprint(this.effectiveDiff);
        this.approvedFingerprint = approvedFingerprint;
        this.commitHash = commitHash;
        this.approvedBy = approvedBy;
        this.rejectedBy = rejectedBy;
        this.rejectionReason = rejectionReason;
        this.buildStatus = buildStatus != null ? buildStatus : "SUCCESS";
        this.testSummary = testSummary != null ? testSummary : "";
        this.aiExplanation = "";
        this.revisionCount = 1;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public CodeReview(String id, String projectId, String taskId, String branch, ReviewStatus status,
                      String initialDiff, String effectiveDiff, String contentFingerprint, String approvedFingerprint,
                      String commitHash, String approvedBy, String rejectedBy, String rejectionReason,
                      String buildStatus, String testSummary, String aiExplanation, int revisionCount,
                      Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.initialDiff = initialDiff != null ? initialDiff : "";
        this.effectiveDiff = effectiveDiff != null ? effectiveDiff : this.initialDiff;
        this.contentFingerprint = contentFingerprint != null ? contentFingerprint : calculateFingerprint(this.effectiveDiff);
        this.approvedFingerprint = approvedFingerprint;
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
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the effective diff when a user or tool modifies files in review.
     * Invalidate any previous approval if effective content changes.
     */
    public synchronized void updateEffectiveDiff(String newDiff) {
        this.effectiveDiff = newDiff != null ? newDiff : "";
        String newFingerprint = calculateFingerprint(this.effectiveDiff);
        if (!newFingerprint.equals(this.contentFingerprint)) {
            this.contentFingerprint = newFingerprint;
            if (this.status == ReviewStatus.APPROVED) {
                // Invalidate approval due to content modification
                this.status = ReviewStatus.PENDING;
                this.approvedBy = null;
                this.approvedFingerprint = null;
            }
        }
        this.updatedAt = Instant.now();
    }

    public synchronized void updateBuildAndTestStatus(String buildStatus, String testSummary) {
        this.buildStatus = buildStatus;
        this.testSummary = testSummary;
        this.updatedAt = Instant.now();
    }

    public synchronized void approve(String userId) {
        this.status = ReviewStatus.APPROVED;
        this.approvedBy = userId != null ? userId : "user";
        this.approvedFingerprint = this.contentFingerprint;
        this.updatedAt = Instant.now();
    }

    public synchronized void reject(String userId, String reason) {
        this.status = ReviewStatus.REJECTED;
        this.rejectedBy = userId != null ? userId : "user";
        this.rejectionReason = reason != null ? reason : "Changes rejected by reviewer.";
        this.approvedBy = null;
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
                && this.contentFingerprint != null
                && this.contentFingerprint.equals(this.approvedFingerprint);
    }
}
