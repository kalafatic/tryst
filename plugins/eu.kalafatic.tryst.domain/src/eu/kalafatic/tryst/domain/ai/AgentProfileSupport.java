package eu.kalafatic.tryst.domain.ai;

import eu.kalafatic.tryst.domain.model.AgentConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-profile field access for {@link AgentConfiguration}. Profile metadata (role,
 * description, tool permissions, restrictions, preferred harness/provider/model and
 * execution parameters) is stored inside the existing {@code configurationJson} field of
 * the V1 agents table — a forward-compatible evolution that keeps the local-agent
 * semantics of AgentConfiguration unchanged. Parsing and serialization live here so the
 * UI, web API and execution pipeline share one representation.
 */
public final class AgentProfileSupport {

    public static final String KEY_ROLE = "profile.role";
    public static final String KEY_DESCRIPTION = "profile.description";
    public static final String KEY_TOOL_PERMISSIONS = "profile.toolPermissions";
    public static final String KEY_RESTRICTIONS = "profile.restrictions";
    public static final String KEY_PREFERRED_HARNESS = "profile.preferredHarness";
    public static final String KEY_PREFERRED_PROVIDER = "profile.preferredProvider";
    public static final String KEY_PREFERRED_MODEL = "profile.preferredModel";
    public static final String KEY_EXECUTION_PARAMETERS = "profile.executionParameters";

    private AgentProfileSupport() {
    }

    public record AgentProfileView(
            String id,
            String name,
            String modelName,
            String systemPrompt,
            String role,
            String description,
            List<String> toolPermissions,
            List<String> restrictions,
            String preferredHarness,
            String preferredProvider,
            String preferredModel,
            Map<String, String> executionParameters
    ) {
    }

    public static AgentProfileView view(AgentConfiguration configuration) {
        Map<String, String> flat = read(configuration);
        return new AgentProfileView(
                configuration.getId(),
                configuration.getName(),
                configuration.getModelName(),
                configuration.getSystemPrompt(),
                flat.getOrDefault(KEY_ROLE, ""),
                flat.getOrDefault(KEY_DESCRIPTION, ""),
                eu.kalafatic.tryst.core.json.TrystJson
                        .readStringList(flat.getOrDefault(KEY_TOOL_PERMISSIONS, "[]")),
                eu.kalafatic.tryst.core.json.TrystJson
                        .readStringList(flat.getOrDefault(KEY_RESTRICTIONS, "[]")),
                flat.getOrDefault(KEY_PREFERRED_HARNESS, ""),
                flat.getOrDefault(KEY_PREFERRED_PROVIDER, ""),
                flat.getOrDefault(KEY_PREFERRED_MODEL, ""),
                eu.kalafatic.tryst.core.json.TrystJson
                        .readStringMap(flat.getOrDefault(KEY_EXECUTION_PARAMETERS, "{}")));
    }

    /**
     * Returns the configuration JSON containing the given profile fields, preserving any
     * unknown pre-existing entries.
     */
    public static String withProfileFields(AgentConfiguration existing, String role,
            String description, List<String> toolPermissions, List<String> restrictions,
            String preferredHarness, String preferredProvider, String preferredModel,
            Map<String, String> executionParameters) {
        Map<String, String> flat = read(existing);
        putIfNotBlank(flat, KEY_ROLE, role);
        putIfNotBlank(flat, KEY_DESCRIPTION, description);
        if (toolPermissions != null) {
            flat.put(KEY_TOOL_PERMISSIONS,
                    eu.kalafatic.tryst.core.json.TrystJson
                            .writeStringList(toolPermissions));
        }
        if (restrictions != null) {
            flat.put(KEY_RESTRICTIONS,
                    eu.kalafatic.tryst.core.json.TrystJson
                            .writeStringList(restrictions));
        }
        putIfNotBlank(flat, KEY_PREFERRED_HARNESS, preferredHarness);
        putIfNotBlank(flat, KEY_PREFERRED_PROVIDER, preferredProvider);
        putIfNotBlank(flat, KEY_PREFERRED_MODEL, preferredModel);
        if (executionParameters != null) {
            flat.put(KEY_EXECUTION_PARAMETERS,
                    eu.kalafatic.tryst.core.json.TrystJson
                            .writeStringMap(executionParameters));
        }
        return eu.kalafatic.tryst.core.json.TrystJson.writeStringMap(flat);
    }

    private static void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static Map<String, String> read(AgentConfiguration configuration) {
        if (configuration == null || configuration.getConfigurationJson() == null
                || configuration.getConfigurationJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(eu.kalafatic.tryst.core.json.TrystJson
                .readStringMap(configuration.getConfigurationJson()));
    }
}
