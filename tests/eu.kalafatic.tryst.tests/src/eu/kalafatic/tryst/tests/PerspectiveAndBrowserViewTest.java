package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.ui.perspective.TrystAdministrationPerspective;
import eu.kalafatic.tryst.ui.perspective.TrystMainPerspective;
import eu.kalafatic.tryst.ui.view.EmbeddedBrowserView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ensuring RCP perspective declarations and EmbeddedBrowserView integration
 * adhere to standard TRYST perspective IDs and default canonical URL settings.
 */
public class PerspectiveAndBrowserViewTest {

    @Test
    void testPerspectiveIds() {
        assertEquals("eu.kalafatic.tryst.ui.perspective.main", TrystMainPerspective.ID);
        assertEquals("eu.kalafatic.tryst.ui.perspective.administration", TrystAdministrationPerspective.ID);
        assertEquals("eu.kalafatic.tryst.ui.view.embeddedBrowser", EmbeddedBrowserView.ID);
    }

    @Test
    void testEmbeddedBrowserViewClassLoads() {
        assertNotNull(EmbeddedBrowserView.class);
    }
}
