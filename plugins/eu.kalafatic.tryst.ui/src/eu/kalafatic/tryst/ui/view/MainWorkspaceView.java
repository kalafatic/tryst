package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import java.util.Optional;

/**
 * Central workspace view presenting active project and task execution status.
 * Contains presentation logic only; delegates user actions to WorkspaceController.
 */
public class MainWorkspaceView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.mainWorkspace";

    private WorkspaceController controller;
    private Label activeProjectLabel;
    private TableViewer taskViewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        Group projectGroup = new Group(parent, SWT.NONE);
        projectGroup.setText("Active Project");
        projectGroup.setLayout(new GridLayout(1, false));
        projectGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        activeProjectLabel = new Label(projectGroup, SWT.WRAP);
        activeProjectLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        activeProjectLabel.setText("No project selected.");

        Group taskGroup = new Group(parent, SWT.NONE);
        taskGroup.setText("Development Tasks");
        taskGroup.setLayout(new GridLayout(1, false));
        taskGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        taskViewer = new TableViewer(taskGroup, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        taskViewer.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof AgentTask task) {
                    return "[" + task.getStatus() + "] " + task.getTitle() + " - " + task.getDescription();
                }
                return super.getText(element);
            }
        });

        this.controller = new WorkspaceController(TrystUiPlugin.getDefault().getWorkspaceService());
        this.controller.setViewCallback(() -> {
            Display display = activeProjectLabel.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(this::updateViewContent);
            }
        });

        createActions();
        updateViewContent();
    }

    private void updateViewContent() {
        if (activeProjectLabel.isDisposed() || taskViewer.getControl().isDisposed()) {
            return;
        }
        Optional<Project> active = controller.getActiveProject();
        if (active.isPresent()) {
            Project p = active.get();
            activeProjectLabel.setText("Project: " + p.getName() + " (" + p.getLocation() + ")");
        } else {
            activeProjectLabel.setText("No active project. Select or add a project in Project Explorer.");
        }
        taskViewer.setInput(controller.getTasks());
        taskViewer.refresh();
    }

    private void createActions() {
        Action createTaskAction = new Action("New Task...") {
            @Override
            public void run() {
                InputDialog dialog = new InputDialog(
                        getSite().getShell(),
                        "Create Development Task",
                        "Task title:",
                        "Implement Feature",
                        null
                );
                if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
                    controller.handleCreateTask(dialog.getValue().trim(), "User created autonomous task");
                }
            }
        };

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(createTaskAction);
    }

    @Override
    public void setFocus() {
        if (taskViewer != null && !taskViewer.getControl().isDisposed()) {
            taskViewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
        }
        super.dispose();
    }
}
