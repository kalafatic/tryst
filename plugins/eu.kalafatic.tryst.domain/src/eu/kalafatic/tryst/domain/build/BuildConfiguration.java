package eu.kalafatic.tryst.domain.build;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional project-specific build and test configuration (e.g. from .tryst-build.json).
 */
public record BuildConfiguration(
        String buildSystem,
        List<String> buildCommand,
        List<String> testCommand,
        Integer timeoutSeconds,
        Map<String, String> environmentVariables
) {
    public BuildConfiguration {
        buildCommand = buildCommand != null ? List.copyOf(buildCommand) : List.of();
        testCommand = testCommand != null ? List.copyOf(testCommand) : List.of();
        environmentVariables = environmentVariables != null ? Map.copyOf(environmentVariables) : Map.of();
    }

    public static BuildConfiguration defaults() {
        return new BuildConfiguration(null, List.of(), List.of(), 300, Map.of());
    }

    public Optional<Integer> getEffectiveTimeout(int fallback) {
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            return Optional.of(timeoutSeconds);
        }
        return Optional.of(fallback);
    }
}
