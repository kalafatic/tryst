package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.controller.WorkspaceController;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

/**
 * Editor page for Task Management, intake, prioritization, and session selection.
 * Enhanced with toolbar controls, filter search, execute shortcut, cancel task, and clipboard support.
 */
public class TaskManagementPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.taskManagement";

    private WorkspaceController controller;
    private ComboViewer sessionCombo;
    private TableViewer taskViewer;
    private Text filterText;
    private Button btnRunTask;
    private Button btnCancelTask;
    private Button btnCopyTaskId;

    public TaskManagementPage(TrystWorkbenchEditor editor) {
        super(ID, "Task Management", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite topBar = new Composite(parent, SWT.NONE);
        topBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        topBar.setLayout(new GridLayout(8, false));

        Label lblSession = new Label(topBar, SWT.NONE);
        lblSession.setText("Session:");

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
        btnNewSession.setText("➕ New Session...");
        btnNewSession.addListener(SWT.Selection, e -> onNewSession());

        Button btnNewTask = new Button(topBar, SWT.PUSH);
        btnNewTask.setText("⚡ New Task...");
        btnNewTask.addListener(SWT.Selection, e -> onNewTask());

        Button btnRefresh = new Button(topBar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        btnRunTask = new Button(topBar, SWT.PUSH);
        btnRunTask.setText("▶ Execute in Agent");
        btnRunTask.setEnabled(false);
        btnRunTask.addListener(SWT.Selection, e -> {
            editor.selectPage(TaskExecutionPage.ID);
        });

        btnCancelTask = new Button(topBar, SWT.PUSH);
        btnCancelTask.setText("⏹ Cancel Task");
        btnCancelTask.setEnabled(false);
        btnCancelTask.addListener(SWT.Selection, e -> onCancelSelectedTask());

        btnCopyTaskId = new Button(topBar, SWT.PUSH);
        btnCopyTaskId.setText("📋 Copy ID");
        btnCopyTaskId.setEnabled(false);
        btnCopyTaskId.addListener(SWT.Selection, e -> copySelectedTaskId());

        // Search Filter Bar
        Composite searchBar = new Composite(parent, SWT.NONE);
        searchBar.setLayout(new GridLayout(2, false));
        searchBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblSearch = new Label(searchBar, SWT.NONE);
        lblSearch.setText("🔍 Filter Tasks:");

        filterText = new Text(searchBar, SWT.BORDER | SWT.SEARCH);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.setMessage("Filter by task title, status, or description...");
        filterText.addModifyListener(e -> applyFilter());

        taskViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = taskViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("ID", 100);
        createColumn("Title", 240);
        createColumn("Status", 130);
        createColumn("Priority", 80);
        createColumn("Description", 300);

        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new TaskLabelProvider());

        taskViewer.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            boolean hasSel = !sel.isEmpty();
            btnRunTask.setEnabled(hasSel);
            btnCopyTaskId.setEnabled(hasSel);

            if (hasSel && sel.getFirstElement() instanceof AgentTask task) {
                btnCancelTask.setEnabled(task.getStatus() == ExecutionStatus.RUNNING || task.getStatus() == ExecutionStatus.CLAIMED);
                WorkbenchContextManager.getInstance().setContext(
                        WorkbenchContextManager.getInstance().getContext().toBuilder()
                                .task(task)
                                .build()
                );
            } else {
                btnCancelTask.setEnabled(false);
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

    private void applyFilter() {
        String query = filterText.getText().trim().toLowerCase();
        if (query.isEmpty()) {
            taskViewer.setFilters(new ViewerFilter[0]);
        } else {
            taskViewer.setFilters(new ViewerFilter() {
                @Override
                public boolean select(org.eclipse.jface.viewers.Viewer v, Object parentElement, Object element) {
                    if (element instanceof AgentTask t) {
                        return (t.getTitle() != null && t.getTitle().toLowerCase().contains(query))
                                || (t.getStatus() != null && t.getStatus().name().toLowerCase().contains(query))
                                || (t.getDescription() != null && t.getDescription().toLowerCase().contains(query));
                    }
                    return true;
                }
            });
        }
    }

    private void copySelectedTaskId() {
        IStructuredSelection sel = (IStructuredSelection) taskViewer.getSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof AgentTask task) {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{task.getId()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        }
    }

    private void onCancelSelectedTask() {
        IStructuredSelection sel = (IStructuredSelection) taskViewer.getSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof AgentTask task) {
            boolean confirm = MessageDialog.openConfirm(getControl().getShell(), "Confirm Cancellation",
                    "Are you sure you want to cancel execution for task: " + task.getTitle() + "?");
            if (confirm) {
                var orch = TrystUiServices.getSessionTaskOrchestrator();
                if (orch != null) {
                    orch.cancelCurrentExecution();
                }
                refresh();
            }
        }
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
        super.onContextChanged(context);
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
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
