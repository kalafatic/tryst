package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import java.util.Optional;

/**
 * Enhanced central workspace view presenting active project, sessions,
 * autonomous task runner, and real-time streaming output.
 */
public class MainWorkspaceView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.mainWorkspace";

    private WorkspaceController controller;
    private Label activeProjectLabel;
    private ComboViewer sessionCombo;
    private TableViewer taskViewer;
    private StyledText agentStreamText;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        // Header / Active Project & Session
        Group topGroup = new Group(parent, SWT.NONE);
        topGroup.setText("Workspace Context");
        topGroup.setLayout(new GridLayout(3, false));
        topGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        activeProjectLabel = new Label(topGroup, SWT.WRAP);
        activeProjectLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        activeProjectLabel.setText("No project selected.");

        Label sessionLbl = new Label(topGroup, SWT.NONE);
        sessionLbl.setText("Active Session:");

        sessionCombo = new ComboViewer(topGroup, SWT.READ_ONLY);
        sessionCombo.setContentProvider(ArrayContentProvider.getInstance());
        sessionCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Session s) return s.getName() + " (" + s.getStatus() + ")";
                return super.getText(element);
            }
        });
        sessionCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof Session s) {
                controller.setActiveSession(s);
            }
        });

        // Split center area: Tasks on left, Streaming Agent log on right
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group taskGroup = new Group(sash, SWT.NONE);
        taskGroup.setText("Development Tasks");
        taskGroup.setLayout(new GridLayout(1, false));

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

        Group agentGroup = new Group(sash, SWT.NONE);
        agentGroup.setText("Autonomous Agent Execution & Streaming");
        agentGroup.setLayout(new GridLayout(1, false));

        agentStreamText = new StyledText(agentGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        agentStreamText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        sash.setWeights(45, 55);

        // Controller initialization
        TrystUiPlugin plugin = TrystUiPlugin.getDefault();
        this.controller = new WorkspaceController(
                plugin.getWorkspaceService(),
                plugin.getSessionTaskOrchestrator(),
                plugin.getAutonomousDevelopmentService(),
                plugin.getAiInferenceService(),
                plugin.getAsyncExecutionService(),
                plugin.getEventBus()
        );

        this.controller.setViewCallback(() -> {
            Display display = activeProjectLabel.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(this::updateViewContent);
            }
        });

        this.controller.setStreamCallback(chunk -> {
            Display display = agentStreamText.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!agentStreamText.isDisposed()) {
                        agentStreamText.append(chunk);
                        agentStreamText.setTopIndex(agentStreamText.getLineCount() - 1);
                    }
                });
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

        sessionCombo.setInput(controller.getSessions());
        if (controller.getActiveSession() != null) {
            sessionCombo.setSelection(new StructuredSelection(controller.getActiveSession()));
        }

        taskViewer.setInput(controller.getTasks());
        taskViewer.refresh();
    }

    private void createActions() {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();

        Action newSessionAction = new Action("New Session...") {
            @Override
            public void run() {
                InputDialog dialog = new InputDialog(
                        getSite().getShell(),
                        "Create Session",
                        "Session Name:",
                        "Dev Session " + (controller.getSessions().size() + 1),
                        null
                );
                if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
                    controller.createSession(dialog.getValue().trim());
                }
            }
        };

        Action createTaskAction = new Action("New Task...") {
            @Override
            public void run() {
                InputDialog dialog = new InputDialog(
                        getSite().getShell(),
                        "Create Development Task",
                        "Task title:",
                        "Implement Autonomous Change",
                        null
                );
                if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
                    controller.handleCreateTask(dialog.getValue().trim(), "User autonomous task");
                }
            }
        };

        Action runTaskAction = new Action("Run Task with Agent") {
            @Override
            public void run() {
                IStructuredSelection sel = (IStructuredSelection) taskViewer.getSelection();
                if (!sel.isEmpty() && sel.getFirstElement() instanceof AgentTask task) {
                    agentStreamText.setText("Starting Autonomous Development for: " + task.getTitle() + "\n");
                    controller.runTaskWithAgent(task);
                }
            }
        };

        Action cancelAction = new Action("Cancel Execution") {
            @Override
            public void run() {
                controller.cancelCurrentExecution();
            }
        };

        toolbar.add(newSessionAction);
        toolbar.add(createTaskAction);
        toolbar.add(runTaskAction);
        toolbar.add(cancelAction);
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
