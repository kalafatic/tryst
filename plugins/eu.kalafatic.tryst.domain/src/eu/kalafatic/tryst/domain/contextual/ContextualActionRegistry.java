package eu.kalafatic.tryst.domain.contextual;

import java.util.List;
import java.util.Optional;

/** The one contextual action registry of the application (contract v1.0, §1 rule 4). */
public interface ContextualActionRegistry {

    void registerProvider(ContextualActionProvider provider);

    void unregisterProvider(ContextualActionProvider provider);

    void registerHandler(ContextualActionHandler handler);

    void unregisterHandler(String actionId, String surface);

    List<ContextualAction> resolve(ContextualSelection selection);

    Optional<ContextualAction> resolve(String actionId, ContextualSelection selection);

    ContextualExecutionResult execute(String actionId, ContextualSelection selection);

    ContextualExecutionResult executeResolved(ContextualAction action,
            ContextualSelection selection);
}
