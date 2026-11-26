package eu.kalafatic.tryst.application.env;

import eu.kalafatic.tryst.application.TrystApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.env.ExecutionEnvironmentMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actionable administrator notification shown when a new task arrived while the
 * execution environment is unavailable (server RCP).
 *
 * <p>Dismissing or "keeping the task queued" never cancels or removes the task — the
 * task stays persisted in {@code BLOCKED} state and is released automatically when the
 * environment is restored. A persistent Dashboard warning and the static status menu
 * remain available afterwards; this dialog is opened at most once per readiness episode
 * to avoid notification spam.</p>
 */
public class EnvironmentNotReadyDialog extends Dialog {

    /** Guards against stacking identical modal dialogs. */
    private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean(false);

    private final String taskId;
    private final String taskTitle;
    private final String reason;
    private final List<String> problems;
    private final IWorkbenchWindow window;

    public EnvironmentNotReadyDialog(Shell parent, String taskId, String taskTitle,
                                     String reason, List<String> problems,
                                     IWorkbenchWindow window) {
        super(parent);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        this.taskId = taskId != null ? taskId : "";
        this.taskTitle = taskTitle != null ? taskTitle : "Unknown task";
        this.reason = reason != null ? reason : "Execution environment is not ready.";
        this.problems = problems != null ? problems : List.of();
        this.window = window;
    }

    @Override
    public int open() {
        if (!DIALOG_OPEN.compareAndSet(false, true)) {
            return CANCEL;
        }
        try {
            return super.open();
        } finally {
            DIALOG_OPEN.set(false);
        }
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 12;
        container.setLayout(layout);

        Label title = new Label(container, SWT.NONE);
        title.setText("EXECUTION ENVIRONMENT NOT READY");
        title.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label intro = new Label(container, SWT.WRAP);
        intro.setText("A new task has arrived, but execution cannot start.");
        intro.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label taskLabel = new Label(container, SWT.WRAP);
        taskLabel.setText("Task:\n  \"" + taskTitle + "\"  (id " + taskId + ")");
        taskLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label missing = new Label(container, SWT.WRAP);
        String problemText = problems.isEmpty()
                ? "  - Execution environment is not ready."
                : String.join("\n", problems.stream().map(p -> "  - " + p).toList());
        missing.setText("Missing dependency:\n" + problemText);
        missing.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label note = new Label(container, SWT.WRAP);
        note.setText("The task remains safely queued. It will start automatically once the environment is restored.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return container;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, 100, "Start Missing Components", false);
        createButton(parent, 101, "Open Environment Status", false);
        createButton(parent, 102, "View Task", false);
        createButton(parent, 103, "Keep Task Queued", false);
        createButton(parent, Dialog.CANCEL, "Dismiss", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        switch (buttonId) {
            case 100 -> startMissingComponents();
            case 101 -> openEnvironmentStatus();
            case 102 -> viewTask();
            case 103 -> close();
            default -> super.buttonPressed(buttonId);
        }
    }

    /**
     * Attempts the supported recovery actions: starts the embedded web server when it is
     * stopped (TRYST-controlled), then re-runs the full environment assessment. External
     * services (harness, inference engine) are not started artificially — the result
     * dialog directs the operator to the precise remaining problems.
     */
    private void startMissingComponents() {
        ApplicationLifecycleCoordinator coord = TrystApplication.getCoordinator();
        if (coord == null) {
            MessageDialog.openError(getShell(), "Recovery", "Application lifecycle coordinator unavailable.");
            return;
        }
        try {
            if (coord.webServerState() != null && "STOPPED".equals(coord.webServerState())) {
                coord.startHttpServer();
            }
        } catch (Exception e) {
            TrystLog.warn(EnvironmentNotReadyDialog.class,
                    "Web server start during environment recovery failed: " + e.getMessage());
        }
        Thread.ofVirtual().start(() -> {
            ExecutionEnvironmentMonitor.EnvironmentSnapshot snapshot = null;
            try {
                snapshot = coord.getExecutionEnvironmentMonitor() != null
                        ? coord.getExecutionEnvironmentMonitor().refreshWithModelDiscovery()
                        : null;
            } catch (Exception e) {
                TrystLog.warn(EnvironmentNotReadyDialog.class,
                        "Environment re-check during recovery failed: " + e.getMessage());
            }
            final ExecutionEnvironmentMonitor.EnvironmentSnapshot finalSnapshot = snapshot;
            getShell().getDisplay().asyncExec(() -> {
                if (getShell() == null || getShell().isDisposed()) {
                    return;
                }
                if (finalSnapshot != null && finalSnapshot.ready()) {
                    MessageDialog.openInformation(getShell(), "Environment Restored",
                            "All health checks passed. The execution environment is ready — "
                                    + "eligible blocked tasks have been released automatically.");
                    close();
                } else {
                    MessageDialog.openWarning(getShell(), "Environment Still Not Ready",
                            "The execution environment is still not ready. "
                                    + "Review the component status and recovery guidance in the environment status dialog.");
                    openEnvironmentStatus();
                }
            });
        });
    }

    private void openEnvironmentStatus() {
        new EnvironmentStatusDialog(getShell()).open();
    }

    private void viewTask() {
        // Open the central editor on the task details page and publish the task into the
        // shared workbench context. The UI bundle is reached reflectively so the
        // application bundle keeps its existing dependency boundaries.
        try {
            Class<?> managerClass = Class.forName("eu.kalafatic.tryst.ui.context.WorkbenchContextManager");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Class<?> contextClass = Class.forName("eu.kalafatic.tryst.ui.context.WorkbenchContext");
            Object builder = contextClass.getMethod("builder").invoke(null);
            Class<?> builderClass = builder.getClass();
            builder = builderClass.getMethod("taskId", String.class).invoke(builder, taskId);
            builder = builderClass.getMethod("taskTitle", String.class).invoke(builder, taskTitle);
            Object context = builderClass.getMethod("build").invoke(builder);
            Method setContext = managerClass.getMethod("setContext", contextClass);
            setContext.invoke(manager, context);
        } catch (Exception e) {
            TrystLog.warn(EnvironmentNotReadyDialog.class,
                    "Could not publish workbench context for task " + taskId + ": " + e.getMessage());
        }
        try {
            if (window != null && window.getActivePage() != null && window.getActivePage().getActiveEditor() != null) {
                Method selectPage = window.getActivePage().getActiveEditor().getClass()
                        .getMethod("selectPage", String.class);
                selectPage.invoke(window.getActivePage().getActiveEditor(), "eu.kalafatic.tryst.ui.editor.page.taskDetails");
            }
        } catch (Exception e) {
            TrystLog.warn(EnvironmentNotReadyDialog.class,
                    "Could not open task details page: " + e.getMessage());
        }
        close();
    }
}
