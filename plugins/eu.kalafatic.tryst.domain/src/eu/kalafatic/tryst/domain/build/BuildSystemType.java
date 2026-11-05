package eu.kalafatic.tryst.domain.build;

/**
 * Supported build system and technology types detected in workspace sandboxes.
 */
public enum BuildSystemType {
    MAVEN,
    GRADLE,
    NPM,
    CARGO,
    PYTHON,
    CUSTOM,
    UNKNOWN
}
