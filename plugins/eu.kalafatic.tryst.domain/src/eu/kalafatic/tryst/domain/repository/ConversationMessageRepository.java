package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.ConversationMessage;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing ConversationMessage domain entities.
 */
public interface ConversationMessageRepository {

    void save(ConversationMessage message);

    Optional<ConversationMessage> findById(String id);

    List<ConversationMessage> findBySessionId(String sessionId);

    List<ConversationMessage> findBySessionIdPaged(String sessionId, int limit, int offset);

    boolean delete(String id);
}
