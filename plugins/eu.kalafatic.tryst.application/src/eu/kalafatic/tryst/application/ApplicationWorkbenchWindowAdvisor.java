package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.env.EnvironmentUiListener;
import eu.kalafatic.tryst.application.help.HelpLauncher;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.env.EnvironmentEvents;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

import java.util.function.Consumer;

/**
 * Configures the main workbench window attributes (dimensions, status line, titles) and
 * installs the global F1 context-help key handler (Family UX standard v1.0): a plain F1
 * key press opens the web help page at the anchor mapped from the active part id,
 * falling back to the generic context section.
 *
 * <p>Additionally installs the permanent execution environment status line indicator and
 * the event-driven administrator notification listener (execution environment not ready /
 * restored).</p>
 */
public class ApplicationWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    private final ApplicationLifecycleCoordinator coordinator;

    private Listener f1HelpListener;
    private EnvironmentUiListener environmentUiListener;
    private Consumer<EnvironmentEvents.StatusChangedEvent> statusLineListener;

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

    @Override
    public void postWindowCreate() {
        // The first workbench window is being created: report the final startup milestone
        // to the splash screen before the workbench is declared running.
        if (coordinator != null
                && coordinator.getCurrentStage().ordinal() < ApplicationLifecycleStage.WORKBENCH_RUNNING.ordinal()) {
            coordinator.notifyStartupProgress("Initializing workbench...");
        }
    }

    @Override
    public void postWindowOpen() {
        installF1HelpFilter();
        installEnvironmentStatusLine();
        installEnvironmentNotifications();
    }

    @Override
    public void postWindowClose() {
        removeF1HelpFilter();
        removeEnvironmentStatusLine();
        if (environmentUiListener != null) {
            environmentUiListener.stop();
            environmentUiListener = null;
        }
    }

    /**
     * Permanent execution environment status line indicator: always visible regardless of
     * the active page or editor, updated from the authoritative ExecutionEnvironmentMonitor
     * via EventBus events (never a UI-side health computation).
     */
    private void installEnvironmentStatusLine() {
        IWorkbenchWindow window = getWindowConfigurer().getWindow();
        if (window == null || window.getShell() == null || window.getShell().isDisposed()) {
            return;
        }
        EventBus eventBus = coordinator != null ? coordinator.getEventBus() : null;
        if (eventBus == null) {
            return;
        }
        Consumer<EnvironmentEvents.StatusChangedEvent> listener = event -> {
            Display display = window.getShell().getDisplay();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (window.getShell() == null || window.getShell().isDisposed()) {
                    return;
                }
                var snapshot = event.snapshot();
                var statusLine = getWindowConfigurer().getActionBarConfigurer().getStatusLineManager();
                if (statusLine == null) {
                    return;
                }
                if (snapshot.ready()) {
                    statusLine.setErrorMessage(null);
                    statusLine.setMessage("\u25CF Execution Environment: READY");
                } else {
                    String first = snapshot.problems().isEmpty() ? "environment not ready" : snapshot.problems().get(0);
                    statusLine.setErrorMessage("\u25CF Execution Environment: NOT READY — " + first);
                }
            });
        };
        eventBus.subscribe(EnvironmentEvents.StatusChangedEvent.class, listener);
        statusLineListener = listener;

        // Initial state (snapshot may already exist before the window opened)
        if (coordinator != null && coordinator.getExecutionEnvironmentMonitor() != null) {
            var snapshot = coordinator.getExecutionEnvironmentMonitor().getSnapshot();
            var statusLine = getWindowConfigurer().getActionBarConfigurer().getStatusLineManager();
            if (statusLine != null) {
                if (snapshot.ready()) {
                    statusLine.setMessage("\u25CF Execution Environment: READY");
                } else {
                    String first = snapshot.problems().isEmpty() ? "environment not ready" : snapshot.problems().get(0);
                    statusLine.setErrorMessage("\u25CF Execution Environment: NOT READY — " + first);
                }
            }
        }
    }

    private void removeEnvironmentStatusLine() {
        if (statusLineListener != null && coordinator != null && coordinator.getEventBus() != null) {
            coordinator.getEventBus().unsubscribe(EnvironmentEvents.StatusChangedEvent.class, statusLineListener);
        }
        statusLineListener = null;
    }

    /**
     * Event-driven administrator notifications for incoming tasks blocked by an
     * unavailable execution environment, plus the recovery notification.
     */
    private void installEnvironmentNotifications() {
        IWorkbenchWindow window = getWindowConfigurer().getWindow();
        if (window == null || coordinator == null) {
            return;
        }
        EventBus eventBus = coordinator.getEventBus();
        if (eventBus == null) {
            return;
        }
        environmentUiListener = new EnvironmentUiListener(window, eventBus);
        environmentUiListener.start();
    }

    /**
     * Installs a display-level key filter for plain F1 (no modifiers): opens the web help
     * page at the anchor mapped from the active part id; unknown parts fall back to the
     * generic context section. The key press is consumed so the default Eclipse help
     * handler (which has no TRYST content) never runs.
     */
    private void installF1HelpFilter() {
        IWorkbenchWindow window = getWindowConfigurer().getWindow();
        if (window == null || window.getShell() == null || window.getShell().isDisposed()) {
            return;
        }
        Display display = window.getShell().getDisplay();
        if (display.isDisposed()) {
            return;
        }
        removeF1HelpFilter();
        f1HelpListener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (event.type != SWT.KeyDown || event.keyCode != SWT.F1) {
                    return;
                }
                if (event.stateMask != 0) {
                    return; // only plain F1 triggers web context help
                }
                event.doit = false;
                final IWorkbenchWindow win = window;
                try {
                    String anchor = HelpLauncher.ANCHOR_CONTEXT;
                    IWorkbenchPart part = (win.getActivePage() != null) ? win.getActivePage().getActivePart() : null;
                    if (part != null && part.getSite() != null) {
                        anchor = HelpLauncher.contextHelpFor(part.getSite().getId());
                    }
                    HelpLauncher.openTopic(win, anchor);
                } catch (Throwable t) {
                    TrystLog.warn(ApplicationWorkbenchWindowAdvisor.class, "F1 context help failed: " + t.getMessage());
                }
            }
        };
        display.addFilter(SWT.KeyDown, f1HelpListener);
    }

    private void removeF1HelpFilter() {
        if (f1HelpListener == null) {
            return;
        }
        try {
            IWorkbenchWindow window = getWindowConfigurer().getWindow();
            if (window != null && window.getShell() != null && !window.getShell().isDisposed()) {
                Display display = window.getShell().getDisplay();
                if (display != null && !display.isDisposed()) {
                    display.removeFilter(SWT.KeyDown, f1HelpListener);
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(ApplicationWorkbenchWindowAdvisor.class, "Could not remove F1 help filter: " + t.getMessage());
        } finally {
            f1HelpListener = null;
        }
    }
}
