package eu.kalafatic.tryst.domain.contextual;

import java.util.List;

/** Contributes actions (descriptors + availability) for context types. */
public interface ContextualActionProvider {

    List<String> contextTypes();

    List<ContextualAction> actionsFor(ContextualSelection selection);
}
