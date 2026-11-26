package eu.kalafatic.tryst.application.env;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.env.EnvironmentEvents;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Event-driven administrator notification layer for the execution environment
 * (server RCP, workbench mode only).
 *
 * <ul>
 *   <li>On {@link EnvironmentEvents.TaskBlockedEvent}: opens the actionable
 *       "EXECUTION ENVIRONMENT NOT READY" dialog once per readiness episode.</li>
 *   <li>On {@link EnvironmentEvents.ReadinessChangedEvent} (recovery): shows a concise
 *       "Execution environment restored" information dialog once per recovery.</li>
 * </ul>
 *
 * <p>All UI updates are dispatched through the SWT display thread. Dismissing a
 * notification never cancels or removes the blocked task.</p>
 */
public class EnvironmentUiListener {

    private final IWorkbenchWindow window;
    private final EventBus eventBus;
    private Consumer<EnvironmentEvents.TaskBlockedEvent> blockedSubscription;
    private Consumer<EnvironmentEvents.ReadinessChangedEvent> readinessSubscription;
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private volatile String lastBlockedEpisode = "";

    public EnvironmentUiListener(IWorkbenchWindow window, EventBus eventBus) {
        this.window = window;
        this.eventBus = eventBus;
    }

    /** Subscribes to the shared event bus. Safe to call once per workbench window. */
    public void start() {
        if (eventBus == null) {
            TrystLog.warn(EnvironmentUiListener.class,
                    "EventBus unavailable; environment notifications disabled in RCP UI");
            return;
        }
        blockedSubscription = this::onTaskBlocked;
        readinessSubscription = this::onReadinessChanged;
        eventBus.subscribe(EnvironmentEvents.TaskBlockedEvent.class, blockedSubscription);
        eventBus.subscribe(EnvironmentEvents.ReadinessChangedEvent.class, readinessSubscription);
    }

    /** Unsubscribes from the shared event bus. */
    public void stop() {
        if (eventBus != null) {
            if (blockedSubscription != null) {
                eventBus.unsubscribe(EnvironmentEvents.TaskBlockedEvent.class, blockedSubscription);
            }
            if (readinessSubscription != null) {
                eventBus.unsubscribe(EnvironmentEvents.ReadinessChangedEvent.class, readinessSubscription);
            }
        }
        blockedSubscription = null;
        readinessSubscription = null;
    }

    private void onTaskBlocked(EnvironmentEvents.TaskBlockedEvent event) {
        String episode = event.episodeKey();
        if (episode == null || episode.equals(lastBlockedEpisode)) {
            // Same readiness episode: the persistent Dashboard warning and status menu
            // already reflect the problem; do not spam identical dialogs.
            return;
        }
        lastBlockedEpisode = episode;
        Display display = resolveDisplay();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            if (display.isDisposed()) {
                return;
            }
            var shell = window != null && window.getShell() != null ? window.getShell() : display.getActiveShell();
            if (shell == null || shell.isDisposed()) {
                return;
            }
            new EnvironmentNotReadyDialog(shell, event.taskId(), event.title(),
                    event.reason(), event.problems(), window).open();
        });
    }

    private void onReadinessChanged(EnvironmentEvents.ReadinessChangedEvent event) {
        boolean previous = ready.getAndSet(event.ready());
        if (!event.ready() || previous) {
            return;
        }
        Display display = resolveDisplay();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            if (display.isDisposed()) {
                return;
            }
            var shell = window != null && window.getShell() != null ? window.getShell() : display.getActiveShell();
            if (shell == null || shell.isDisposed()) {
                return;
            }
            MessageDialog.openInformation(shell, "Execution Environment Restored",
                    "The TRYST execution environment has been restored. "
                            + "Eligible blocked tasks have been released and execution is starting.");
        });
    }

    private Display resolveDisplay() {
        if (window != null && window.getShell() != null) {
            return window.getShell().getDisplay();
        }
        Display current = Display.getCurrent();
        if (current != null) {
            return current;
        }
        try {
            return Display.getDefault();
        } catch (Exception e) {
            return null;
        }
    }
}
