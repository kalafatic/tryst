package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/**
 * Editor page for Task Execution Details and Autonomous Agent Streaming.
 */
public class TaskExecutionPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.taskExecution";

    private WorkspaceController controller;
    private ComboViewer taskCombo;
    private StyledText streamOutput;
    private AgentTask selectedTask;

    public TaskExecutionPage(TrystWorkbenchEditor editor) {
        super(ID, "Task Execution", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group execControlGroup = new Group(parent, SWT.NONE);
        execControlGroup.setText("Execution Controls");
        execControlGroup.setLayout(new GridLayout(4, false));
        execControlGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label lblTask = new Label(execControlGroup, SWT.NONE);
        lblTask.setText("Target Task:");

        taskCombo = new ComboViewer(execControlGroup, SWT.READ_ONLY);
        taskCombo.setContentProvider(ArrayContentProvider.getInstance());
        taskCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof AgentTask t) {
                    return "[" + t.getStatus() + "] " + t.getTitle();
                }
                return super.getText(element);
            }
        });
        taskCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRun = new Button(execControlGroup, SWT.PUSH);
        btnRun.setText("Run Task with Agent");
        btnRun.addListener(SWT.Selection, e -> onRunTask());

        Button btnCancel = new Button(execControlGroup, SWT.PUSH);
        btnCancel.setText("Cancel Execution");
        btnCancel.addListener(SWT.Selection, e -> onCancelTask());

        taskCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof AgentTask t) {
                this.selectedTask = t;
            }
        });

        Group streamGroup = new Group(parent, SWT.NONE);
        streamGroup.setText("Agent Execution & Reasoning Stream");
        streamGroup.setLayout(new GridLayout(1, false));
        streamGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        streamOutput = new StyledText(streamGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        streamOutput.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        streamOutput.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());

        this.controller = new WorkspaceController(
                TrystUiServices.getWorkspaceService(),
                TrystUiServices.getSessionTaskOrchestrator(),
                TrystUiServices.getAutonomousDevelopmentService(),
                TrystUiServices.getAiInferenceService(),
                TrystUiServices.getAsyncExecutionService(),
                TrystUiServices.getEventBus()
        );

        this.controller.setStreamCallback(chunk -> {
            Display display = streamOutput.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!streamOutput.isDisposed()) {
                        streamOutput.append(chunk);
                        streamOutput.setTopIndex(streamOutput.getLineCount() - 1);
                    }
                });
            }
        });

        refresh();
    }

    private void onRunTask() {
        if (selectedTask != null && controller != null) {
            streamOutput.setText("=== Starting Execution for Task: " + selectedTask.getTitle() + " ===\n");
            controller.runTaskWithAgent(selectedTask);
        }
    }

    private void onCancelTask() {
        if (controller != null) {
            controller.cancelCurrentExecution();
            streamOutput.append("\n[Execution cancelled by user]\n");
        }
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        if (context.task() != null) {
            this.selectedTask = context.task();
            if (taskCombo != null && !taskCombo.getControl().isDisposed()) {
                taskCombo.setSelection(new StructuredSelection(selectedTask));
            }
        }
    }

    @Override
    public void refresh() {
        if (controller == null || taskCombo == null || taskCombo.getControl().isDisposed()) return;
        var tasks = controller.getTasks();
        taskCombo.setInput(tasks);
        if (selectedTask != null) {
            taskCombo.setSelection(new StructuredSelection(selectedTask));
        } else if (!tasks.isEmpty()) {
            selectedTask = tasks.get(0);
            taskCombo.setSelection(new StructuredSelection(selectedTask));
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
            controller = null;
        }
        super.dispose();
    }
}
