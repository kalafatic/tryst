package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.Collections;

/**
 * Native SWT/JFace View for Tasks and Autonomous Agents.
 * Resolves WorkspaceService directly via authoritative OSGi service infrastructure.
 */
public class TasksAndAgentsView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.tasksAndAgents";

    private TableViewer taskViewer;
    private Label statusLabel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("");

        taskViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = taskViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Task ID", 100);
        createColumn("Title", 200);
        createColumn("Status", 120);
        createColumn("Priority", 80);

        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof AgentTask t) {
                    return switch (columnIndex) {
                        case 0 -> t.getId().substring(0, Math.min(8, t.getId().length()));
                        case 1 -> t.getTitle();
                        case 2 -> t.getStatus().name();
                        case 3 -> t.getPriority().name();
                        default -> "";
                    };
                }
                return "";
            }
            @Override public void addListener(ILabelProviderListener listener) {}
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object element, String property) { return false; }
            @Override public void removeListener(ILabelProviderListener listener) {}
        });

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Tasks");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createColumn(String title, int width) {
        TableViewerColumn col = new TableViewerColumn(taskViewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        WorkspaceService ws = TrystUiServices.getWorkspaceService();
        if (ws == null || ws.getCurrentWorkspace() == null) {
            String msg = (ws == null) ? "WorkspaceService is not currently available in OSGi registry."
                    : "No active workspace loaded.";
            statusLabel.setText(msg);
            if (ws == null) TrystLog.warn(TasksAndAgentsView.class, msg);
            taskViewer.setInput(Collections.emptyList());
            return;
        }

        statusLabel.setText("");
        taskViewer.setInput(ws.getCurrentWorkspace().getTasks());
    }

    @Override
    public void setFocus() {
        if (taskViewer != null && !taskViewer.getControl().isDisposed()) {
            taskViewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
