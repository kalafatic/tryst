package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.repository.GitCommitRepository;
import eu.kalafatic.tryst.domain.review.GitCommitRecord;
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
 * SQLite implementation of GitCommitRepository.
 */
public class SqliteGitCommitRepository implements GitCommitRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteGitCommitRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(GitCommitRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        String sql = """
            INSERT INTO git_commits (
                id, commit_hash, review_id, task_id, project_id, revision_number,
                author_id, commit_message, content_fingerprint, branch, files_changed,
                status, committed_at, pushed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                pushed_at = excluded.pushed_at;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, record.getId());
                pstmt.setString(2, record.getCommitHash());
                pstmt.setString(3, record.getReviewId());
                setOrNull(pstmt, 4, record.getTaskId());
                pstmt.setString(5, record.getProjectId());
                pstmt.setInt(6, record.getRevisionNumber());
                pstmt.setString(7, record.getAuthorId());
                pstmt.setString(8, record.getCommitMessage());
                pstmt.setString(9, record.getContentFingerprint());
                pstmt.setString(10, record.getBranch());
                pstmt.setInt(11, record.getFilesChanged());
                pstmt.setString(12, record.getStatus().name());
                pstmt.setString(13, record.getCommittedAt().toString());
                setOrNull(pstmt, 14, record.getPushedAt() != null ? record.getPushedAt().toString() : null);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save Git commit record: " + record.getId(), e);
            }
        }
    }

    @Override
    public Optional<GitCommitRecord> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT * FROM git_commits WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToRecord(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find Git commit record by id: " + id, e);
            }
        }
    }

    @Override
    public Optional<GitCommitRecord> findByCommitHash(String commitHash) {
        Objects.requireNonNull(commitHash, "commitHash must not be null");
        String sql = "SELECT * FROM git_commits WHERE commit_hash = ? LIMIT 1;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, commitHash);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToRecord(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find Git commit record by commitHash: " + commitHash, e);
            }
        }
    }

    @Override
    public List<GitCommitRecord> findByReviewId(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        String sql = "SELECT * FROM git_commits WHERE review_id = ? ORDER BY committed_at DESC;";
        List<GitCommitRecord> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, reviewId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToRecord(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find Git commit records for review: " + reviewId, e);
            }
        }
    }

    @Override
    public List<GitCommitRecord> findByProjectId(String projectId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        String sql = "SELECT * FROM git_commits WHERE project_id = ? ORDER BY committed_at DESC;";
        List<GitCommitRecord> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, projectId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToRecord(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find Git commit records for project: " + projectId, e);
            }
        }
    }

    @Override
    public List<GitCommitRecord> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "SELECT * FROM git_commits WHERE task_id = ? ORDER BY committed_at DESC;";
        List<GitCommitRecord> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToRecord(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find Git commit records for task: " + taskId, e);
            }
        }
    }

    @Override
    public List<GitCommitRecord> findAll() {
        String sql = "SELECT * FROM git_commits ORDER BY committed_at DESC;";
        List<GitCommitRecord> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToRecord(rs));
                }
                return list;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find all Git commit records", e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM git_commits WHERE id = ?;";
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete Git commit record: " + id, e);
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

    private GitCommitRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        String pushedAtStr = rs.getString("pushed_at");
        Instant pushedAt = pushedAtStr != null && !pushedAtStr.isBlank() ? Instant.parse(pushedAtStr) : null;
        String statusStr = rs.getString("status");
        GitCommitRecord.CommitStatus status = GitCommitRecord.CommitStatus.COMMITTED;
        if (statusStr != null) {
            try {
                status = GitCommitRecord.CommitStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        return new GitCommitRecord(
                rs.getString("id"),
                rs.getString("commit_hash"),
                rs.getString("review_id"),
                rs.getString("task_id"),
                rs.getString("project_id"),
                rs.getInt("revision_number"),
                rs.getString("author_id"),
                rs.getString("commit_message"),
                rs.getString("content_fingerprint"),
                rs.getString("branch"),
                rs.getInt("files_changed"),
                status,
                Instant.parse(rs.getString("committed_at")),
                pushedAt
        );
    }
}
