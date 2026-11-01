package eu.kalafatic.tryst.infrastructure.persistence.repository;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.repository.ConversationMessageRepository;
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
 * SQLite implementation of ConversationMessageRepository.
 */
public class SqliteConversationMessageRepository implements ConversationMessageRepository {

    private final SqliteDatabaseManager databaseManager;

    public SqliteConversationMessageRepository(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    @Override
    public void save(ConversationMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        String sql = """
            INSERT INTO conversation_messages (id, session_id, role, content, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                session_id = excluded.session_id,
                role = excluded.role,
                content = excluded.content;
        """;

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, message.getId());
                pstmt.setString(2, message.getSessionId());
                pstmt.setString(3, message.getRole());
                pstmt.setString(4, message.getContent());
                pstmt.setString(5, message.getCreatedAt().toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to save conversation message: " + message.getId(), e);
            }
        }
    }

    @Override
    public Optional<ConversationMessage> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "SELECT id, session_id, role, content, created_at FROM conversation_messages WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapResultSetToMessage(rs));
                    }
                    return Optional.empty();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find conversation message by id: " + id, e);
            }
        }
    }

    @Override
    public List<ConversationMessage> findBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = "SELECT id, session_id, role, content, created_at FROM conversation_messages WHERE session_id = ? ORDER BY created_at ASC;";
        List<ConversationMessage> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToMessage(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find conversation messages for session: " + sessionId, e);
            }
        }
    }

    @Override
    public List<ConversationMessage> findBySessionIdPaged(String sessionId, int limit, int offset) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = "SELECT id, session_id, role, content, created_at FROM conversation_messages WHERE session_id = ? ORDER BY created_at ASC LIMIT ? OFFSET ?;";
        List<ConversationMessage> list = new ArrayList<>();

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sessionId);
                pstmt.setInt(2, limit);
                pstmt.setInt(3, offset);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapResultSetToMessage(rs));
                    }
                    return list;
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to find paged conversation messages for session: " + sessionId, e);
            }
        }
    }

    @Override
    public boolean delete(String id) {
        Objects.requireNonNull(id, "id must not be null");
        String sql = "DELETE FROM conversation_messages WHERE id = ?;";

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, id);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to delete conversation message: " + id, e);
            }
        }
    }

    private ConversationMessage mapResultSetToMessage(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String sessionId = rs.getString("session_id");
        String role = rs.getString("role");
        String content = rs.getString("content");
        Instant createdAt = Instant.parse(rs.getString("created_at"));
        return new ConversationMessage(id, sessionId, role, content, createdAt);
    }
}
