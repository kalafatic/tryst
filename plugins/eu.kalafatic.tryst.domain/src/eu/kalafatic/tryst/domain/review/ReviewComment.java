package eu.kalafatic.tryst.domain.review;

import java.time.Instant;
import java.util.Objects;

public class ReviewComment {

    public enum DiffSide {
        LEFT,
        RIGHT
    }

    public enum CommentType {
        LINE,
        FILE,
        GENERAL
    }

    private final String id;
    private final String reviewId;
    private final String taskId;
    private final int revisionNumber;
    private final String filePath;
    private final DiffSide diffSide;
    private final int startLine;
    private final int endLine;
    private final String codeContext;
    private final CommentType commentType;
    private String content;
    private final String authorId;
    private boolean resolved;
    private boolean includeInRework;
    private boolean blocking;
    private final Instant createdAt;
    private Instant updatedAt;

    public ReviewComment(String id, String reviewId, String authorId, String content, boolean blocking) {
        this(id, reviewId, "task-1", 1, "file.txt", DiffSide.RIGHT, 1, 1, "", CommentType.LINE, content, authorId, false, false, blocking, Instant.now(), Instant.now());
    }

    public ReviewComment(String id, String reviewId, String taskId, int revisionNumber,
                         String filePath, DiffSide diffSide, int startLine, int endLine,
                         String codeContext, CommentType commentType, String content,
                         String authorId, boolean resolved, boolean includeInRework,
                         Instant createdAt, Instant updatedAt) {
        this(id, reviewId, taskId, revisionNumber, filePath, diffSide, startLine, endLine,
                codeContext, commentType, content, authorId, resolved, includeInRework, true, createdAt, updatedAt);
    }

    public ReviewComment(String id, String reviewId, String taskId, int revisionNumber,
                         String filePath, DiffSide diffSide, int startLine, int endLine,
                         String codeContext, CommentType commentType, String content,
                         String authorId, boolean resolved, boolean includeInRework,
                         boolean blocking, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.revisionNumber = revisionNumber;
        this.filePath = filePath != null ? filePath : "";
        this.diffSide = diffSide != null ? diffSide : DiffSide.RIGHT;
        this.startLine = startLine;
        this.endLine = endLine;
        this.codeContext = codeContext != null ? codeContext : "";
        this.commentType = commentType != null ? commentType : CommentType.LINE;
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.authorId = authorId != null ? authorId : "anonymous";
        this.resolved = resolved;
        this.includeInRework = includeInRework;
        this.blocking = blocking;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public ReviewComment(String id, String reviewId, String taskId, int revisionNumber,
                         String filePath, String diffSideStr, int startLine, int endLine,
                         String codeContext, String commentTypeStr, String content,
                         String authorId, boolean resolved, boolean includeInRework,
                         Instant createdAt, Instant updatedAt) {
        this(id, reviewId, taskId, revisionNumber, filePath,
                diffSideStr != null && diffSideStr.equalsIgnoreCase("LEFT") ? DiffSide.LEFT : DiffSide.RIGHT,
                startLine, endLine, codeContext,
                parseCommentType(commentTypeStr),
                content, authorId, resolved, includeInRework, true, createdAt, updatedAt);
    }

    public ReviewComment(String id, String reviewId, String taskId, int revisionNumber,
                         String filePath, String diffSideStr, int startLine, int endLine,
                         String codeContext, String commentTypeStr, String content,
                         String authorId, boolean resolved, boolean includeInRework,
                         boolean blocking, Instant createdAt, Instant updatedAt) {
        this(id, reviewId, taskId, revisionNumber, filePath,
                diffSideStr != null && diffSideStr.equalsIgnoreCase("LEFT") ? DiffSide.LEFT : DiffSide.RIGHT,
                startLine, endLine, codeContext,
                parseCommentType(commentTypeStr),
                content, authorId, resolved, includeInRework, blocking, createdAt, updatedAt);
    }

    private static CommentType parseCommentType(String typeStr) {
        if (typeStr == null) {
            return CommentType.LINE;
        }
        try {
            return CommentType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CommentType.LINE;
        }
    }

    public String getId() {
        return id;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getFilePath() {
        return filePath;
    }

    public DiffSide getDiffSide() {
        return diffSide;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getCodeContext() {
        return codeContext;
    }

    public CommentType getCommentType() {
        return commentType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.updatedAt = Instant.now();
    }

    public String getAuthorId() {
        return authorId;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
        this.updatedAt = Instant.now();
    }

    public boolean isIncludeInRework() {
        return includeInRework;
    }

    public void setIncludeInRework(boolean includeInRework) {
        this.includeInRework = includeInRework;
        this.updatedAt = Instant.now();
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
