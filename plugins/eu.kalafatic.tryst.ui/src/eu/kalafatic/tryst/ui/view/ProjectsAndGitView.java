package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

/**
 * Native SWT/JFace View for Projects and Git Repositories.
 */
public class ProjectsAndGitView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.projectsAndGit";

    private TableViewer projectViewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        projectViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = projectViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Project Name", 160);
        createColumn("Owner ID", 120);
        createColumn("Status", 100);
        createColumn("Workspace Location", 240);

        projectViewer.setContentProvider(ArrayContentProvider.getInstance());
        projectViewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof Project p) {
                    return switch (columnIndex) {
                        case 0 -> p.getName();
                        case 1 -> p.getOwnerId();
                        case 2 -> p.getStatus().name();
                        case 3 -> p.getLocation().toString();
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
        btnRefresh.setText("Refresh Projects");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createColumn(String title, int width) {
        TableViewerColumn col = new TableViewerColumn(projectViewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        WorkspaceService ws = TrystUiServices.getWorkspaceService();
        if (ws != null && ws.getCurrentWorkspace() != null) {
            projectViewer.setInput(ws.getCurrentWorkspace().getProjects());
        }
    }

    @Override
    public void setFocus() {
        if (projectViewer != null) projectViewer.getControl().setFocus();
    }
}
