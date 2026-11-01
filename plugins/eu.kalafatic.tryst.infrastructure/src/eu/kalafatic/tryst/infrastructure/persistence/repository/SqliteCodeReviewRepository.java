package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;
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
                content_fingerprint, approved_fingerprint, commit_hash, approved_by,
                rejected_by, rejection_reason, build_status, test_summary, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                effective_diff = excluded.effective_diff,
                content_fingerprint = excluded.content_fingerprint,
                approved_fingerprint = excluded.approved_fingerprint,
                commit_hash = excluded.commit_hash,
                approved_by = excluded.approved_by,
                rejected_by = excluded.rejected_by,
                rejection_reason = excluded.rejection_reason,
                build_status = excluded.build_status,
                test_summary = excluded.test_summary,
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
                setOrNull(pstmt, 10, review.getCommitHash());
                setOrNull(pstmt, 11, review.getApprovedBy());
                setOrNull(pstmt, 12, review.getRejectedBy());
                setOrNull(pstmt, 13, review.getRejectionReason());
                pstmt.setString(14, review.getBuildStatus());
                pstmt.setString(15, review.getTestSummary());
                pstmt.setString(16, review.getCreatedAt().toString());
                pstmt.setString(17, review.getUpdatedAt().toString());
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
                rs.getString("commit_hash"),
                rs.getString("approved_by"),
                rs.getString("rejected_by"),
                rs.getString("rejection_reason"),
                rs.getString("build_status"),
                rs.getString("test_summary"),
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
}
