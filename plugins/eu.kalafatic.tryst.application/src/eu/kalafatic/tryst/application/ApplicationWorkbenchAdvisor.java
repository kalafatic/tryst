package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/**
 * Workbench advisor managing application lifecycle, window advisors, and default perspective.
 * Orchestrates deterministic lifecycle transitions in sync with Workbench events.
 */
public class ApplicationWorkbenchAdvisor extends WorkbenchAdvisor {

    public static final String PERSPECTIVE_ID = "eu.kalafatic.tryst.ui.perspective.main";

    private final ApplicationLifecycleCoordinator coordinator;

    public ApplicationWorkbenchAdvisor() {
        this(resolveCoordinator());
    }

    public ApplicationWorkbenchAdvisor(ApplicationLifecycleCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    private static ApplicationLifecycleCoordinator resolveCoordinator() {
        TrystApplicationPlugin plugin = TrystApplicationPlugin.getDefault();
        if (plugin != null) {
            return plugin.getLifecycleCoordinator();
        }
        return null;
    }

    @Override
    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        return new ApplicationWorkbenchWindowAdvisor(configurer);
    }

    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
        configurer.setSaveAndRestore(true);

        if (coordinator != null) {
            coordinator.transitionTo(ApplicationLifecycleStage.CORE_SERVICE_INITIALIZATION);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_READY);
        }
    }

    @Override
    public void postStartup() {
        super.postStartup();
        setupPerspectiveListener();
        if (coordinator != null) {
            coordinator.transitionTo(ApplicationLifecycleStage.WORKBENCH_RUNNING);
        }
    }

    private void setupPerspectiveListener() {
        try {
            Class<?> listenerClass = Class.forName("eu.kalafatic.tryst.ui.perspective.TrystPerspectiveLifecycleListener");
            org.eclipse.ui.IPerspectiveListener listener = (org.eclipse.ui.IPerspectiveListener) listenerClass.getDeclaredConstructor().newInstance();
            org.eclipse.ui.IWorkbenchWindow activeWindow = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (activeWindow != null) {
                activeWindow.addPerspectiveListener(listener);
                org.eclipse.ui.IWorkbenchPage activePage = activeWindow.getActivePage();
                if (activePage != null && activePage.getPerspective() != null
                        && PERSPECTIVE_ID.equals(activePage.getPerspective().getId())) {
                    Class<?> mgrClass = Class.forName("eu.kalafatic.tryst.ui.editor.TrystEditorManager");
                    var method = mgrClass.getMethod("ensureEditorOpen", org.eclipse.ui.IWorkbenchPage.class);
                    method.invoke(null, activePage);
                }
            }
        } catch (Exception e) {
            TrystLog.warn(ApplicationWorkbenchAdvisor.class, "Could not attach perspective lifecycle listener: " + e.getMessage());
        }
    }

    @Override
    public boolean preShutdown() {
        if (coordinator != null) {
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_SHUTDOWN);
        }
        return super.preShutdown();
    }

    @Override
    public void postShutdown() {
        if (coordinator != null) {
            coordinator.transitionTo(ApplicationLifecycleStage.SERVICE_DISPOSAL);
            coordinator.transitionTo(ApplicationLifecycleStage.APPLICATION_TERMINATED);
        }
        super.postShutdown();
    }

    @Override
    public void eventLoopException(Throwable exception) {
        TrystLog.error(ApplicationWorkbenchAdvisor.class, "Unhandled Workbench UI exception: " + exception.getMessage(), exception);
        super.eventLoopException(exception);
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return PERSPECTIVE_ID;
    }
}
