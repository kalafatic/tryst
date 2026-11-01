package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.error.TrystApplicationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

    @Test
    void testDefaultConfigurationValues() {
        ApplicationConfiguration config = ApplicationConfiguration.createDefault();

        assertEquals(ApplicationConfiguration.DEFAULT_APP_NAME, config.applicationName());
        assertEquals(ApplicationConfiguration.DEFAULT_VERSION, config.version());
        assertNotNull(config.userHomeDirectory());
        assertNotNull(config.configurationDirectory());
        assertNotNull(config.defaultWorkspaceDirectory());

        assertTrue(config.configurationDirectory().endsWith(".tryst"));
        assertTrue(config.defaultWorkspaceDirectory().endsWith("workspace"));
    }

    @Test
    void testConfigurationValidation() {
        assertThrows(TrystApplicationException.class, () -> new ApplicationConfiguration(
                "   ",
                "0.1.0",
                Path.of("/tmp"),
                Path.of("/tmp/.tryst"),
                Path.of("/tmp/.tryst/workspace")
        ));

        assertThrows(NullPointerException.class, () -> new ApplicationConfiguration(
                null,
                "0.1.0",
                Path.of("/tmp"),
                Path.of("/tmp/.tryst"),
                Path.of("/tmp/.tryst/workspace")
        ));
    }
}
