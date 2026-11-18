package eu.kalafatic.tryst.domain.contextual;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable evaluation input for contextual actions (Darwin Family Contextual Action
 * Contract v1.0, section 2). Contract-identical to the DStudio reference implementation.
 */
public record ContextualSelection(
        String contractVersion,
        String application,
        String surface,
        String objectType,
        String objectId,
        String objectLabel,
        Map<String, String> objectState,
        boolean multiSelect,
        String projectId,
        String sessionId,
        Set<String> permissions,
        String user) {

    public static final String APPLICATION_TRYST = "TRYST";
    public static final String SURFACE_RCP = "RCP";
    public static final String SURFACE_WEB = "WEB";

    public ContextualSelection {
        contractVersion = contractVersion == null ? ContextualActionIds.CONTRACT_VERSION
                : contractVersion;
        objectState = objectState == null ? Map.of() : Map.copyOf(objectState);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public String state(String key) {
        return objectState.get(key);
    }

    public static Builder builder(String application, String surface,
            String objectType) {
        return new Builder(application, surface, objectType);
    }

    public static final class Builder {
        private final String application;
        private final String surface;
        private final String objectType;
        private String objectId = "";
        private String objectLabel = "";
        private final Map<String, String> objectState = new LinkedHashMap<>();
        private boolean multiSelect = false;
        private String projectId = "";
        private String sessionId = "";
        private final java.util.LinkedHashSet<String> permissions =
                new java.util.LinkedHashSet<>();
        private String user = "";

        public Builder(String application, String surface, String objectType) {
            this.application = application;
            this.surface = surface;
            this.objectType = objectType;
        }

        public Builder object(String id, String label) {
            this.objectId = id == null ? "" : id;
            this.objectLabel = label == null ? "" : label;
            return this;
        }

        public Builder state(String key, Object value) {
            if (value != null) {
                objectState.put(key, String.valueOf(value));
            }
            return this;
        }

        public Builder multiSelect(boolean value) {
            this.multiSelect = value;
            return this;
        }

        public Builder projectId(String value) {
            this.projectId = value == null ? "" : value;
            return this;
        }

        public Builder sessionId(String value) {
            this.sessionId = value == null ? "" : value;
            return this;
        }

        public Builder permissions(Set<String> value) {
            if (value != null) {
                permissions.addAll(value);
            }
            return this;
        }

        public Builder user(String value) {
            this.user = value == null ? "" : value;
            return this;
        }

        public ContextualSelection build() {
            return new ContextualSelection(ContextualActionIds.CONTRACT_VERSION,
                    application, surface, objectType, objectId, objectLabel,
                    objectState, multiSelect, projectId, sessionId, permissions, user);
        }
    }
}
