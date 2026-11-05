package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;
import eu.kalafatic.tryst.domain.review.ReviewRevision;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of CodeReviewRepository.
 */
public class SqliteCodeReviewRepository implements CodeReviewRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteCodeReviewRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(CodeReview review) {
        Objects.requireNonNull(review, "review must not be null");
        String sql = """
            INSERT INTO code_reviews (
                id, project_id, task_id, branch, status, initial_diff, effective_diff,
                content_fingerprint, approved_fingerprint, approved_revision, commit_hash, approved_by,
                rejected_by, rejection_reason, build_status, test_summary, ai_explanation,
                revision_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                effective_diff = excluded.effective_diff,
                content_fingerprint = excluded.content_fingerprint,
                approved_fingerprint = excluded.approved_fingerprint,
                approved_revision = excluded.approved_revision,
                commit_hash = excluded.commit_hash,
                approved_by = excluded.approved_by,
                rejected_by = excluded.rejected_by,
                rejection_reason = excluded.rejection_reason,
                build_status = excluded.build_status,
                test_summary = excluded.test_summary,
                ai_explanation = excluded.ai_explanation,
                revision_count = excluded.revision_count,
                updated_at = excluded.updated_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, review.getId());
                pstmt.setString(2, review.getProjectId());
                pstmt.setString(3, review.getTaskId());
                pstmt.setString(4, review.getBranch());
                pstmt.setString(5, review.getStatus().name());
                pstmt.setString(6, review.getInitialDiff());
                pstmt.setString(7, review.getEffectiveDiff());
                pstmt.setString(8, review.getContentFingerprint());
                setOrNull(pstmt, 9, review.getApprovedFingerprint());
                if (review.getApprovedRevision() != null) {
                    pstmt.setInt(10, review.getApprovedRevision());
                } else {
                    pstmt.setNull(10, Types.INTEGER);
                }
                setOrNull(pstmt, 11, review.getCommitHash());
                setOrNull(pstmt, 12, review.getApprovedBy());
                setOrNull(pstmt, 13, review.getRejectedBy());
                setOrNull(pstmt, 14, review.getRejectionReason());
                pstmt.setString(15, review.getBuildStatus());
                pstmt.setString(16, review.getTestSummary());
                setOrNull(pstmt, 17, review.getAiExplanation());
                pstmt.setInt(18, review.getRevisionCount());
                pstmt.setString(19, review.getCreatedAt().toString());
                pstmt.setString(20, review.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save code review: " + review.getId(), e);
            }
        }
    }

    private static void setOrNull(PreparedStatement pstmt, int index, String value) throws SQLException {
        if (value != null) {
            pstmt.setString(index, value);
        } else {
            pstmt.setNull(index, Types.VARCHAR);
        }
    }

    @Override
    public Optional<CodeReview> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT * FROM code_reviews WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToCodeReview(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find code review by id: " + id, e);
            }
        }
    }

    @Override
    public Optional<CodeReview> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT * FROM code_reviews WHERE task_id = ? ORDER BY created_at DESC LIMIT 1;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToCodeReview(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find code review by taskId: " + taskId, e);
            }
        }
    }

    @Override
    public List<CodeReview> findByProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        String sql = "SELECT * FROM code_reviews WHERE project_id = ? ORDER BY created_at DESC;";
        List<CodeReview> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToCodeReview(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find code reviews for project: " + projectId, e);
            }
        }
    }

    @Override
    public List<CodeReview> findAll() {
        String sql = "SELECT * FROM code_reviews ORDER BY created_at DESC;";
        List<CodeReview> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToCodeReview(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all code reviews", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM code_reviews WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete code review: " + id, e);
            }
        }
    }

    @Override
    public void saveFileEdit(ReviewFileEdit edit) {
        Objects.requireNonNull(edit, "edit must not be null");
        String sql = """
            INSERT INTO review_file_edits (id, review_id, file_path, original_content, modified_content, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                modified_content = excluded.modified_content,
                updated_at = excluded.updated_at;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, edit.getId());
                pstmt.setString(2, edit.getReviewId());
                pstmt.setString(3, edit.getFilePath());
                pstmt.setString(4, edit.getOriginalContent());
                pstmt.setString(5, edit.getModifiedContent());
                pstmt.setString(6, edit.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save review file edit: " + edit.getId(), e);
            }
        }
    }

    @Override
    public List<ReviewFileEdit> findFileEditsByReviewId(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String sql = "SELECT * FROM review_file_edits WHERE review_id = ? ORDER BY file_path ASC;";
        List<ReviewFileEdit> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToReviewFileEdit(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find file edits for review: " + reviewId, e);
            }
        }
    }

    @Override
    public Optional<ReviewFileEdit> findFileEdit(String reviewId, String filePath) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(filePath, "filePath must not be null");
        String sql = "SELECT * FROM review_file_edits WHERE review_id = ? AND file_path = ? LIMIT 1;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                pstmt.setString(2, filePath);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToReviewFileEdit(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find file edit for review: " + reviewId + ", path: " + filePath, e);
            }
        }
    }

    private CodeReview mapResultSetToCodeReview(ResultSet rs) throws SQLException {
        String aiExp = null;
        int revCount = 1;
        Integer approvedRev = null;
        try {
            aiExp = rs.getString("ai_explanation");
            revCount = rs.getInt("revision_count");
        } catch (SQLException ignored) {}
        try {
            int r = rs.getInt("approved_revision");
            if (!rs.wasNull()) {
                approvedRev = r;
            }
        } catch (SQLException ignored) {}

        return new CodeReview(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("task_id"),
                rs.getString("branch"),
                CodeReview.ReviewStatus.valueOf(rs.getString("status")),
                rs.getString("initial_diff"),
                rs.getString("effective_diff"),
                rs.getString("content_fingerprint"),
                rs.getString("approved_fingerprint"),
                approvedRev,
                rs.getString("commit_hash"),
                rs.getString("approved_by"),
                rs.getString("rejected_by"),
                rs.getString("rejection_reason"),
                rs.getString("build_status"),
                rs.getString("test_summary"),
                aiExp != null ? aiExp : "",
                revCount,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private ReviewFileEdit mapResultSetToReviewFileEdit(ResultSet rs) throws SQLException {
        return new ReviewFileEdit(
                rs.getString("id"),
                rs.getString("review_id"),
                rs.getString("file_path"),
                rs.getString("original_content"),
                rs.getString("modified_content"),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    @Override
    public void saveRevision(ReviewRevision revision) {
        Objects.requireNonNull(revision, "revision must not be null");
        String sql = """
            INSERT INTO review_revisions (id, review_id, revision_number, diff, explanation, build_status, test_summary, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(review_id, revision_number) DO UPDATE SET
                diff = excluded.diff,
                explanation = excluded.explanation,
                build_status = excluded.build_status,
                test_summary = excluded.test_summary;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, revision.getId());
                pstmt.setString(2, revision.getReviewId());
                pstmt.setInt(3, revision.getRevisionNumber());
                pstmt.setString(4, revision.getDiff());
                pstmt.setString(5, revision.getExplanation());
                pstmt.setString(6, revision.getBuildStatus());
                pstmt.setString(7, revision.getTestSummary());
                pstmt.setString(8, revision.getCreatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save review revision: " + revision.getId(), e);
            }
        }
    }

    @Override
    public List<ReviewRevision> findRevisionsByReviewId(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String sql = "SELECT * FROM review_revisions WHERE review_id = ? ORDER BY revision_number ASC;";
        List<ReviewRevision> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(new ReviewRevision(
                                rs.getString("id"),
                                rs.getString("review_id"),
                                rs.getInt("revision_number"),
                                rs.getString("diff"),
                                rs.getString("explanation"),
                                rs.getString("build_status"),
                                rs.getString("test_summary"),
                                Instant.parse(rs.getString("created_at"))
                        ));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find revisions for review: " + reviewId, e);
            }
        }
    }

    @Override
    public Optional<ReviewRevision> findRevisionByNumber(String reviewId, int revisionNumber) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String sql = "SELECT * FROM review_revisions WHERE review_id = ? AND revision_number = ? LIMIT 1;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                pstmt.setInt(2, revisionNumber);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new ReviewRevision(
                                rs.getString("id"),
                                rs.getString("review_id"),
                                rs.getInt("revision_number"),
                                rs.getString("diff"),
                                rs.getString("explanation"),
                                rs.getString("build_status"),
                                rs.getString("test_summary"),
                                Instant.parse(rs.getString("created_at"))
                        ));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find revision " + revisionNumber + " for review: " + reviewId, e);
            }
        }
    }

    @Override
    public void saveComment(ReviewComment comment) {
        Objects.requireNonNull(comment, "comment must not be null");
        String sql = """
            INSERT INTO review_comments (
                id, review_id, task_id, revision_number, file_path, diff_side, start_line, end_line,
                code_context, comment_type, content, author_id, resolved, include_in_rework, blocking, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                content = excluded.content,
                resolved = excluded.resolved,
                include_in_rework = excluded.include_in_rework,
                blocking = excluded.blocking,
                updated_at = excluded.updated_at;
        """;
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, comment.getId());
                pstmt.setString(2, comment.getReviewId());
                pstmt.setString(3, comment.getTaskId());
                pstmt.setInt(4, comment.getRevisionNumber());
                pstmt.setString(5, comment.getFilePath());
                pstmt.setString(6, comment.getDiffSide().name());
                pstmt.setInt(7, comment.getStartLine());
                pstmt.setInt(8, comment.getEndLine());
                pstmt.setString(9, comment.getCodeContext());
                pstmt.setString(10, comment.getCommentType().name());
                pstmt.setString(11, comment.getContent());
                pstmt.setString(12, comment.getAuthorId());
                pstmt.setInt(13, comment.isResolved() ? 1 : 0);
                pstmt.setInt(14, comment.isIncludeInRework() ? 1 : 0);
                pstmt.setInt(15, comment.isBlocking() ? 1 : 0);
                pstmt.setString(16, comment.getCreatedAt().toString());
                pstmt.setString(17, comment.getUpdatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save review comment: " + comment.getId(), e);
            }
        }
    }

    @Override
    public Optional<ReviewComment> findCommentById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT * FROM review_comments WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToReviewComment(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find review comment by id: " + id, e);
            }
        }
    }

    @Override
    public List<ReviewComment> findCommentsByReviewId(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String sql = "SELECT * FROM review_comments WHERE review_id = ? ORDER BY created_at ASC;";
        List<ReviewComment> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToReviewComment(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find review comments for review: " + reviewId, e);
            }
        }
    }

    @Override
    public List<ReviewComment> findCommentsByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT * FROM review_comments WHERE task_id = ? ORDER BY created_at ASC;";
        List<ReviewComment> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToReviewComment(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find review comments for task: " + taskId, e);
            }
        }
    }

    @Override
    public boolean deleteComment(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM review_comments WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete review comment: " + id, e);
            }
        }
    }

    private ReviewComment mapResultSetToReviewComment(ResultSet rs) throws SQLException {
        boolean blocking = true;
        try {
            int b = rs.getInt("blocking");
            if (!rs.wasNull()) {
                blocking = (b == 1);
            }
        } catch (SQLException ignored) {}

        return new ReviewComment(
                rs.getString("id"),
                rs.getString("review_id"),
                rs.getString("task_id"),
                rs.getInt("revision_number"),
                rs.getString("file_path"),
                rs.getString("diff_side"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getString("code_context"),
                rs.getString("comment_type"),
                rs.getString("content"),
                rs.getString("author_id"),
                rs.getInt("resolved") == 1,
                rs.getInt("include_in_rework") == 1,
                blocking,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }
}
