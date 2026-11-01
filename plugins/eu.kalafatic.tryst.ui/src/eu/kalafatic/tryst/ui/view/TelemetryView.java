package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.model.TelemetrySnapshot;
import eu.kalafatic.tryst.domain.service.TelemetryService;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

/**
 * Native SWT/JFace View for Historical Telemetry.
 */
public class TelemetryView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.telemetry";

    private TableViewer viewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Timestamp", 180);
        createColumn("CPU Sys %", 90);
        createColumn("CPU Proc %", 90);
        createColumn("Heap MB", 90);
        createColumn("Sessions", 70);

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof TelemetrySnapshot s) {
                    return switch (columnIndex) {
                        case 0 -> s.timestamp().toString();
                        case 1 -> String.format("%.1f", s.cpuSystem());
                        case 2 -> String.format("%.1f", s.cpuProcess());
                        case 3 -> String.valueOf(s.memoryHeapUsed() / (1024 * 1024));
                        case 4 -> String.valueOf(s.activeSessions());
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
        btnRefresh.setText("Refresh Telemetry History");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createColumn(String title, int width) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        TelemetryService telem = TrystUiPlugin.getDefault().getTelemetryService();
        if (telem != null) {
            viewer.setInput(telem.getRecentHistory(30));
        }
    }

    @Override
    public void setFocus() {
        if (viewer != null) viewer.getControl().setFocus();
    }
}
