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
        assertEquals("127.0.0.1", config.serverHost());
        assertEquals(8080, config.httpPort());
        assertEquals(9090, config.tcpPort());
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

        ApplicationConfiguration valid = ApplicationConfiguration.createDefault();
        assertThrows(TrystApplicationException.class, () -> valid.withServerSettings("", 8080, 9090));
        assertThrows(TrystApplicationException.class, () -> valid.withServerSettings("127.0.0.1", -1, 9090));
        assertThrows(TrystApplicationException.class, () -> valid.withServerSettings("127.0.0.1", 8080, 70000));
    }
}
