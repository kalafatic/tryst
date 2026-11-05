package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.repository.TaskInstructionRepository;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite implementation of TaskInstructionRepository persisting task instructions,
 * original user requests, expected outcomes, acceptance criteria, constraints, and supplementary instructions.
 */
public class SqliteTaskInstructionRepository implements TaskInstructionRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteTaskInstructionRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(TaskInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction must not be null");
        String sql = """
            INSERT INTO task_instructions (
                id, task_id, instruction_type, content, author_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                task_id = excluded.task_id,
                instruction_type = excluded.instruction_type,
                content = excluded.content,
                author_id = excluded.author_id;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, instruction.getId());
                pstmt.setString(2, instruction.getTaskId());
                pstmt.setString(3, instruction.getInstructionType().name());
                pstmt.setString(4, instruction.getContent());
                pstmt.setString(5, instruction.getAuthorId());
                pstmt.setString(6, instruction.getCreatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save task instruction: " + instruction.getId(), e);
            }
        }
    }

    @Override
    public Optional<TaskInstruction> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = """
            SELECT id, task_id, instruction_type, content, author_id, created_at
            FROM task_instructions WHERE id = ?;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToInstruction(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query task instruction by id: " + id, e);
            }
        }
    }

    @Override
    public List<TaskInstruction> findByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = """
            SELECT id, task_id, instruction_type, content, author_id, created_at
            FROM task_instructions WHERE task_id = ?
            ORDER BY created_at ASC;
        """;

        List<TaskInstruction> list = new ArrayList<>();
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToInstruction(rs));
                    }
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to query task instructions for task: " + taskId, e);
            }
        }
        return list;
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM task_instructions WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                int rows = pstmt.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete task instruction: " + id, e);
            }
        }
    }

    @Override
    public void deleteByTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        String sql = "DELETE FROM task_instructions WHERE task_id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, taskId);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete task instructions for task: " + taskId, e);
            }
        }
    }

    private TaskInstruction mapResultSetToInstruction(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String taskId = rs.getString("task_id");
        String typeStr = rs.getString("instruction_type");
        String content = rs.getString("content");
        String authorId = rs.getString("author_id");
        String createdAtStr = rs.getString("created_at");

        TaskInstruction.InstructionType type = TaskInstruction.InstructionType.valueOf(typeStr);
        Instant createdAt = Instant.parse(createdAtStr);

        return new TaskInstruction(id, taskId, type, content, authorId, createdAt);
    }
}
