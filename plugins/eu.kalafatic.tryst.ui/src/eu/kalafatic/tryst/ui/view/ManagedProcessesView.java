package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.ManagedProcessRecord;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.Collections;

/**
 * Native SWT/JFace View for Managed Operating System Processes.
 * Resolves ProcessExecutionManager directly via authoritative OSGi service infrastructure.
 */
public class ManagedProcessesView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.processes";

    private TableViewer viewer;
    private Label statusLabel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("");

        viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("PID", 80);
        createColumn("Command", 160);
        createColumn("Working Dir", 220);
        createColumn("Status", 100);
        createColumn("Duration (ms)", 100);

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof ManagedProcessRecord p) {
                    return switch (columnIndex) {
                        case 0 -> String.valueOf(p.getPid());
                        case 1 -> p.getCommandClass();
                        case 2 -> p.getWorkingDir();
                        case 3 -> p.getStatus();
                        case 4 -> String.valueOf(p.getDurationMs());
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
        btnRefresh.setText("Refresh Managed Processes");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createColumn(String title, int width) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        ProcessExecutionManager procMgr = TrystUiServices.getProcessExecutionManager();
        if (procMgr == null) {
            String msg = "ProcessExecutionManager is not currently available in OSGi registry.";
            statusLabel.setText(msg);
            TrystLog.warn(ManagedProcessesView.class, msg);
            viewer.setInput(Collections.emptyList());
            return;
        }

        statusLabel.setText("");
        viewer.setInput(procMgr.getManagedProcesses());
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
        refreshData();
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
