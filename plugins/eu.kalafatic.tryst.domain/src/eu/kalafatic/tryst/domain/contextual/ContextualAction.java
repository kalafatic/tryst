package eu.kalafatic.tryst.domain.contextual;

/**
 * Contract-shared contextual action types (v1.0): a resolved action for a selection.
 */
public record ContextualAction(
        ContextualActionDescriptor descriptor,
        boolean enabled,
        String disabledReason) {

    public static ContextualAction enabled(ContextualActionDescriptor descriptor) {
        return new ContextualAction(descriptor, true, "");
    }

    public static ContextualAction disabled(ContextualActionDescriptor descriptor,
            String reason) {
        return new ContextualAction(descriptor, false, reason == null ? "" : reason);
    }

    public String actionId() {
        return descriptor.actionId();
    }
}
