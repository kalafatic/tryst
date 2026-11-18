package eu.kalafatic.tryst.domain.contextual;

/** Real execution for one contextual action id (no mock handlers). */
public interface ContextualActionHandler {

    String actionId();

    default String surface() {
        return "*";
    }

    void execute(ContextualSelection selection, ActionFeedback feedback)
            throws Exception;
}
