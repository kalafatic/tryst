package eu.kalafatic.tryst.core.config;

import eu.kalafatic.tryst.core.error.TrystApplicationException;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Baseline authoritative application configuration with validation and safe defaults.
 */
public record ApplicationConfiguration(
        String applicationName,
        String version,
        Path userHomeDirectory,
        Path configurationDirectory,
        Path defaultWorkspaceDirectory
) {
    public static final String DEFAULT_APP_NAME = "TRYST – AI Development Environment";
    public static final String DEFAULT_VERSION = "0.1.0";

    public ApplicationConfiguration {
        Objects.requireNonNull(applicationName, "applicationName must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(userHomeDirectory, "userHomeDirectory must not be null");
        Objects.requireNonNull(configurationDirectory, "configurationDirectory must not be null");
        Objects.requireNonNull(defaultWorkspaceDirectory, "defaultWorkspaceDirectory must not be null");

        if (applicationName.isBlank()) {
            throw new TrystApplicationException("applicationName must not be blank");
        }
    }

    public static ApplicationConfiguration createDefault() {
        String homeProp = System.getProperty("user.home", ".");
        Path home = Path.of(homeProp).toAbsolutePath().normalize();
        Path configDir = home.resolve(".tryst").normalize();
        Path defaultWsDir = configDir.resolve("workspace").normalize();

        return new ApplicationConfiguration(
                DEFAULT_APP_NAME,
                DEFAULT_VERSION,
                home,
                configDir,
                defaultWsDir
        );
    }
}
