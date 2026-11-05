package eu.kalafatic.tryst.domain.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy contract for detecting project technologies and build systems.
 */
public interface BuildTechnologyDetector {

    /**
     * Detects the build system type for the given repository path.
     */
    BuildSystemType detectBuildSystem(Path repositoryRoot);

    /**
     * Resolves the primary compilation/build command for the detected build system.
     */
    List<String> resolveBuildCommand(Path repositoryRoot);

    /**
     * Resolves the primary automated test command for the detected build system.
     */
    List<String> resolveTestCommand(Path repositoryRoot);

    /**
     * Loads optional project-specific configuration (e.g. .tryst-build.json).
     */
    BuildConfiguration loadConfiguration(Path repositoryRoot);
}
