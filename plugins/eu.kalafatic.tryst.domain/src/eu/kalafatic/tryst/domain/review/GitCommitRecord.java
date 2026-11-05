package eu.kalafatic.tryst.domain.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain entity capturing authoritative Git commit metadata and audit traceability for reviewed changes.
 */
public class GitCommitRecord {

    public enum CommitStatus {
        COMMITTED,
        PUSHED,
        FAILED
    }

    private final String id;
    private final String commitHash;
    private final String reviewId;
    private final String taskId;
    private final String projectId;
    private final int revisionNumber;
    private final String authorId;
    private final String commitMessage;
    private final String contentFingerprint;
    private final String branch;
    private final int filesChanged;
    private CommitStatus status;
    private final Instant committedAt;
    private Instant pushedAt;

    public GitCommitRecord(String commitHash, String reviewId, String taskId, String projectId,
                           int revisionNumber, String authorId, String commitMessage,
                           String contentFingerprint, String branch, int filesChanged) {
        this(UUID.randomUUID().toString(), commitHash, reviewId, taskId, projectId,
                revisionNumber, authorId, commitMessage, contentFingerprint, branch,
                filesChanged, CommitStatus.COMMITTED, Instant.now(), null);
    }

    public GitCommitRecord(String id, String commitHash, String reviewId, String taskId, String projectId,
                           int revisionNumber, String authorId, String commitMessage,
                           String contentFingerprint, String branch, int filesChanged,
                           CommitStatus status, Instant committedAt, Instant pushedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.commitHash = Objects.requireNonNull(commitHash, "commitHash must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.taskId = taskId;
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.revisionNumber = revisionNumber;
        this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
        this.commitMessage = Objects.requireNonNull(commitMessage, "commitMessage must not be null");
        this.contentFingerprint = Objects.requireNonNull(contentFingerprint, "contentFingerprint must not be null");
        this.branch = branch != null && !branch.isBlank() ? branch : "main";
        this.filesChanged = Math.max(0, filesChanged);
        this.status = status != null ? status : CommitStatus.COMMITTED;
        this.committedAt = committedAt != null ? committedAt : Instant.now();
        this.pushedAt = pushedAt;
    }

    public String getId() {
        return id;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getProjectId() {
        return projectId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public String getBranch() {
        return branch;
    }

    public int getFilesChanged() {
        return filesChanged;
    }

    public CommitStatus getStatus() {
        return status;
    }

    public void setStatus(CommitStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public Instant getPushedAt() {
        return pushedAt;
    }

    public void markPushed(Instant pushedAt) {
        this.status = CommitStatus.PUSHED;
        this.pushedAt = pushedAt != null ? pushedAt : Instant.now();
    }
}
