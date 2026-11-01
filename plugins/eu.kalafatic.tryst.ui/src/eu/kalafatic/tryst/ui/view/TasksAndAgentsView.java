package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

/**
 * Native SWT/JFace View for Task and Agent Monitoring.
 */
public class TasksAndAgentsView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.tasksAndAgents";

    private TableViewer taskViewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        taskViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = taskViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Task ID", 100);
        createColumn("Title", 180);
        createColumn("Status", 120);
        createColumn("Assigned Agent", 120);

        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof AgentTask t) {
                    return switch (columnIndex) {
                        case 0 -> t.getId().substring(0, Math.min(8, t.getId().length()));
                        case 1 -> t.getTitle();
                        case 2 -> t.getTaskStatus().name();
                        case 3 -> t.getAgentId() != null ? t.getAgentId() : "none";
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
        WorkspaceService ws = TrystUiPlugin.getDefault().getWorkspaceService();
        if (ws != null && ws.getCurrentWorkspace() != null) {
            taskViewer.setInput(ws.getCurrentWorkspace().getTasks());
        }
    }

    @Override
    public void setFocus() {
        if (taskViewer != null) taskViewer.getControl().setFocus();
    }
}
