package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/**
 * Configures the main workbench window attributes (dimensions, status line, titles).
 */
public class ApplicationWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    private final ApplicationLifecycleCoordinator coordinator;

    public ApplicationWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        this(configurer, ApplicationWorkbenchAdvisor.resolveCoordinator());
    }

    public ApplicationWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer, ApplicationLifecycleCoordinator coordinator) {
        super(configurer);
        this.coordinator = (coordinator != null) ? coordinator : ApplicationWorkbenchAdvisor.resolveCoordinator();
    }

    @Override
    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer configurer) {
        return new ApplicationActionBarAdvisor(configurer, coordinator);
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
