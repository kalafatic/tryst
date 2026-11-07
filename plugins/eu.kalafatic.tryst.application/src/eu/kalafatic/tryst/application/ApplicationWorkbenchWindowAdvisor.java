package eu.kalafatic.tryst.application;

import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/**
 * Configures the main workbench window attributes (dimensions, status line, titles).
 */
public class ApplicationWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    public ApplicationWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        super(configurer);
    }

    @Override
    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer configurer) {
        return new ApplicationActionBarAdvisor(configurer);
    }

    @Override
    public void preWindowOpen() {
        IWorkbenchWindowConfigurer configurer = getWindowConfigurer();
        configurer.setInitialSize(new Point(1280, 800));
        configurer.setShowCoolBar(true);
        configurer.setShowStatusLine(true);
        configurer.setShowProgressIndicator(true);
        configurer.setShowPerspectiveBar(true);
        configurer.setTitle("TRYST – AI Development Environment");
    }
}
