package eu.kalafatic.tryst.domain.contextual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default contextual action registry (contract-identical semantics to the DStudio
 * reference implementation): single contribution/execution authority, surface-aware
 * handlers, per-target duplicate-execution guard.
 */
public class DefaultContextualActionRegistry implements ContextualActionRegistry {

    private static final List<String> GROUP_ORDER = List.of(
            ContextualActionDescriptor.GROUP_SELECTION,
            ContextualActionDescriptor.GROUP_CONFIGURATION,
            ContextualActionDescriptor.GROUP_LIFECYCLE,
            ContextualActionDescriptor.GROUP_INSPECTION,
            ContextualActionDescriptor.GROUP_DANGER);

    private final List<ContextualActionProvider> providers = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, List<ContextualActionHandler>> handlers =
            new ConcurrentHashMap<>();
    private final Set<String> runningExecutions = ConcurrentHashMap.newKeySet();

    @Override
    public void registerProvider(ContextualActionProvider provider) {
        if (provider != null && !providers.contains(provider)) {
            providers.add(provider);
        }
    }

    @Override
    public void unregisterProvider(ContextualActionProvider provider) {
        providers.remove(provider);
    }

    @Override
    public void registerHandler(ContextualActionHandler handler) {
        if (handler == null) {
            return;
        }
        handlers.computeIfAbsent(handler.actionId(), k -> new CopyOnWriteArrayList<>())
                .add(handler);
    }

    @Override
    public void unregisterHandler(String actionId, String surface) {
        List<ContextualActionHandler> list = handlers.get(actionId);
        if (list != null) {
            list.removeIf(h -> h.surface().equals(surface));
            if (list.isEmpty()) {
                handlers.remove(actionId);
            }
        }
    }

    @Override
    public List<ContextualAction> resolve(ContextualSelection selection) {
        if (selection == null) {
            return List.of();
        }
        Map<String, ContextualAction> byId = new LinkedHashMap<>();
        for (ContextualActionProvider provider : providers) {
            if (!provider.contextTypes().contains(selection.objectType())) {
                continue;
            }
            for (ContextualAction action : provider.actionsFor(selection)) {
                byId.putIfAbsent(action.actionId(), action);
            }
        }
        List<ContextualAction> resolved = new ArrayList<>(byId.values());
        resolved.sort(Comparator
                .comparingInt((ContextualAction a) -> groupIndex(a.descriptor().group()))
                .thenComparing(a -> a.descriptor().label(), String.CASE_INSENSITIVE_ORDER));
        return resolved;
    }

    private static int groupIndex(String group) {
        int index = GROUP_ORDER.indexOf(group);
        return index < 0 ? GROUP_ORDER.size() : index;
    }

    @Override
    public Optional<ContextualAction> resolve(String actionId,
            ContextualSelection selection) {
        return resolve(selection).stream().filter(a -> a.actionId().equals(actionId))
                .findFirst();
    }

    @Override
    public ContextualExecutionResult execute(String actionId,
            ContextualSelection selection) {
        ContextualAction action = resolve(actionId, selection).orElse(null);
        if (action == null) {
            return ContextualExecutionResult.failure(actionId,
                    ContextualExecutionResult.ERROR_TARGET_NOT_FOUND,
                    "No such action for this selection.");
        }
        if (!action.enabled()) {
            return ContextualExecutionResult.failure(actionId,
                    ContextualExecutionResult.ERROR_ACTION_DISABLED,
                    action.disabledReason().isBlank() ? "Action is not enabled."
                            : action.disabledReason());
        }
        return executeResolved(action, selection);
    }

    @Override
    public ContextualExecutionResult executeResolved(ContextualAction action,
            ContextualSelection selection) {
        String actionId = action.actionId();
        ContextualActionHandler handler = findHandler(actionId, selection.surface());
        if (handler == null) {
            return ContextualExecutionResult.failure(actionId,
                    ContextualExecutionResult.ERROR_NO_HANDLER,
                    "No handler registered for surface '" + selection.surface() + "'.");
        }
        String guardKey = selection.surface() + "|" + actionId + "|"
                + selection.objectId();
        if (!runningExecutions.add(guardKey)) {
            return ContextualExecutionResult.failure(actionId,
                    ContextualExecutionResult.ERROR_EXECUTION_FAILED,
                    "The action is already executing for this target.");
        }
        try {
            FeedbackCollector feedback = new FeedbackCollector();
            handler.execute(selection, feedback);
            String message = feedback.errorMessage != null ? feedback.errorMessage
                    : (feedback.infoMessage != null ? feedback.infoMessage
                            : "Executed.");
            return feedback.errorMessage != null
                    ? ContextualExecutionResult.failure(actionId,
                            ContextualExecutionResult.ERROR_EXECUTION_FAILED, message)
                    : ContextualExecutionResult.ok(actionId, message);
        } catch (Exception e) {
            return ContextualExecutionResult.failure(actionId,
                    ContextualExecutionResult.ERROR_EXECUTION_FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage());
        } finally {
            runningExecutions.remove(guardKey);
        }
    }

    private ContextualActionHandler findHandler(String actionId, String surface) {
        List<ContextualActionHandler> list = handlers.get(actionId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        ContextualActionHandler wildcard = null;
        for (ContextualActionHandler handler : list) {
            if (handler.surface().equals(surface)) {
                return handler;
            }
            if (handler.surface().equals("*")) {
                wildcard = handler;
            }
        }
        return wildcard;
    }

    private static final class FeedbackCollector implements ActionFeedback {
        private String infoMessage;
        private String errorMessage;

        @Override
        public void progress(String message) {
        }

        @Override
        public void info(String message) {
            this.infoMessage = message;
        }

        @Override
        public void error(String message, Throwable cause) {
            this.errorMessage = message == null ? String.valueOf(cause) : message;
        }
    }
}
