package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Editor page for Task Management, intake, prioritization, and session selection.
 */
public class TaskManagementPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.taskManagement";

    private WorkspaceController controller;
    private ComboViewer sessionCombo;
    private TableViewer taskViewer;

    public TaskManagementPage(TrystWorkbenchEditor editor) {
        super(ID, "Task Management", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Composite topBar = new Composite(parent, SWT.NONE);
        topBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        topBar.setLayout(new GridLayout(4, false));

        Label lblSession = new Label(topBar, SWT.NONE);
        lblSession.setText("Active Session:");

        sessionCombo = new ComboViewer(topBar, SWT.READ_ONLY);
        sessionCombo.setContentProvider(ArrayContentProvider.getInstance());
        sessionCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Session s) return s.getName() + " (" + s.getStatus() + ")";
                return super.getText(element);
            }
        });

        Button btnNewSession = new Button(topBar, SWT.PUSH);
        btnNewSession.setText("New Session...");
        btnNewSession.addListener(SWT.Selection, e -> onNewSession());

        Button btnNewTask = new Button(topBar, SWT.PUSH);
        btnNewTask.setText("New Task...");
        btnNewTask.addListener(SWT.Selection, e -> onNewTask());

        taskViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = taskViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("ID", 100);
        createColumn("Title", 220);
        createColumn("Status", 120);
        createColumn("Priority", 80);
        createColumn("Description", 300);

        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new TaskLabelProvider());

        taskViewer.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof AgentTask task) {
                WorkbenchContextManager.getInstance().setContext(
                        WorkbenchContextManager.getInstance().getContext().toBuilder()
                                .task(task)
                                .build()
                );
            }
        });

        sessionCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof Session session) {
                if (controller != null) {
                    controller.setActiveSession(session);
                }
                WorkbenchContextManager.getInstance().setContext(
                        WorkbenchContextManager.getInstance().getContext().toBuilder()
                                .session(session)
                                .build()
                );
            }
        });

        this.controller = new WorkspaceController(
                TrystUiServices.getWorkspaceService(),
                TrystUiServices.getSessionTaskOrchestrator(),
                TrystUiServices.getAutonomousDevelopmentService(),
                TrystUiServices.getAiInferenceService(),
                TrystUiServices.getAsyncExecutionService(),
                TrystUiServices.getEventBus()
        );

        this.controller.setViewCallback(() -> {
            Display display = taskViewer.getControl().getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(this::refresh);
            }
        });

        refresh();
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(taskViewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    private void onNewSession() {
        InputDialog dialog = new InputDialog(
                getControl().getShell(),
                "Create Session",
                "Session Name:",
                "Dev Session " + (controller != null ? (controller.getSessions().size() + 1) : 1),
                null
        );
        if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
            if (controller != null) {
                controller.createSession(dialog.getValue().trim());
            }
        }
    }

    private void onNewTask() {
        InputDialog dialog = new InputDialog(
                getControl().getShell(),
                "Create Task",
                "Task Title:",
                "Implement Feature",
                null
        );
        if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
            if (controller != null) {
                controller.handleCreateTask(dialog.getValue().trim(), "User autonomous task");
            }
        }
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        refresh();
    }

    @Override
    public void refresh() {
        if (controller == null || taskViewer == null || taskViewer.getControl().isDisposed()) return;
        sessionCombo.setInput(controller.getSessions());
        if (controller.getActiveSession() != null) {
            sessionCombo.setSelection(new StructuredSelection(controller.getActiveSession()));
        }
        taskViewer.setInput(controller.getTasks());
        taskViewer.refresh();
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
            controller = null;
        }
        super.dispose();
    }

    private static class TaskLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof AgentTask task) {
                return switch (columnIndex) {
                    case 0 -> task.getId();
                    case 1 -> task.getTitle();
                    case 2 -> task.getStatus() != null ? task.getStatus().name() : "";
                    case 3 -> String.valueOf(task.getPriority());
                    case 4 -> task.getDescription() != null ? task.getDescription() : "";
                    default -> "";
                };
            }
            return "";
        }
    }
}
