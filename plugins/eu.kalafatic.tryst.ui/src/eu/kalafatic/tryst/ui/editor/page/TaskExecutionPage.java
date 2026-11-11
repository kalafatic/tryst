package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
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
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Editor page for Task Execution Details and Autonomous Agent Streaming.
 * Enhanced with output clear, copy log, export log, and execution controls.
 */
public class TaskExecutionPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.taskExecution";

    private WorkspaceController controller;
    private ComboViewer taskCombo;
    private StyledText streamOutput;
    private AgentTask selectedTask;
    private Button btnRun;
    private Button btnCancel;

    public TaskExecutionPage(TrystWorkbenchEditor editor) {
        super(ID, "Task Execution", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group execControlGroup = new Group(parent, SWT.NONE);
        execControlGroup.setText("Execution Controls");
        execControlGroup.setLayout(new GridLayout(6, false));
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

        btnRun = new Button(execControlGroup, SWT.PUSH);
        btnRun.setText("▶ Run Task with Agent");
        btnRun.addListener(SWT.Selection, e -> onRunTask());

        btnCancel = new Button(execControlGroup, SWT.PUSH);
        btnCancel.setText("⏹ Cancel Execution");
        btnCancel.addListener(SWT.Selection, e -> onCancelTask());

        Button btnRefresh = new Button(execControlGroup, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnReview = new Button(execControlGroup, SWT.PUSH);
        btnReview.setText("🔍 Open Review");
        btnReview.addListener(SWT.Selection, e -> editor.selectPage(CodeReviewPage.ID));

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

        // Stream Action Bar (Clear, Copy, Export)
        Composite streamActions = new Composite(streamGroup, SWT.NONE);
        streamActions.setLayout(new GridLayout(3, false));
        streamActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnClearLog = new Button(streamActions, SWT.PUSH);
        btnClearLog.setText("🗑 Clear Log");
        btnClearLog.addListener(SWT.Selection, e -> streamOutput.setText(""));

        Button btnCopyLog = new Button(streamActions, SWT.PUSH);
        btnCopyLog.setText("📋 Copy Output");
        btnCopyLog.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{streamOutput.getText()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        });

        Button btnExportLog = new Button(streamActions, SWT.PUSH);
        btnExportLog.setText("💾 Export Log...");
        btnExportLog.addListener(SWT.Selection, e -> exportExecutionLog());

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

    private void exportExecutionLog() {
        FileDialog dialog = new FileDialog(getControl().getShell(), SWT.SAVE);
        dialog.setFilterNames(new String[]{"Text Files (*.txt)", "Log Files (*.log)", "All Files (*.*)"});
        dialog.setFilterExtensions(new String[]{"*.txt", "*.log", "*.*"});
        dialog.setFileName("tryst-execution-" + (selectedTask != null ? selectedTask.getId() : "log") + ".txt");
        String filePath = dialog.open();
        if (filePath != null) {
            try {
                Files.writeString(Path.of(filePath), streamOutput.getText());
            } catch (IOException ignored) {
            }
        }
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
        super.onContextChanged(context);
        if (context.task() != null) {
            this.selectedTask = context.task();
            if (taskCombo != null && !taskCombo.getControl().isDisposed()) {
                taskCombo.setSelection(new StructuredSelection(selectedTask));
            }
        }
    }

    @Override
    public void refresh() {
        super.refresh();
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
