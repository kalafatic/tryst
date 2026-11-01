package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AuditEvent;
import eu.kalafatic.tryst.domain.repository.AuditEventRepository;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.Collections;

/**
 * Native SWT/JFace View for Persistent Audit Events & Logs.
 * Resolves AuditEventRepository directly via authoritative OSGi service infrastructure.
 */
public class AuditAndLogsView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.auditAndLogs";

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

        createColumn("Time", 170);
        createColumn("Actor", 100);
        createColumn("Action", 140);
        createColumn("Target", 120);
        createColumn("Outcome", 90);
        createColumn("Details", 200);

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new ITableLabelProvider() {
            @Override public org.eclipse.swt.graphics.Image getColumnImage(Object element, int columnIndex) { return null; }
            @Override public String getColumnText(Object element, int columnIndex) {
                if (element instanceof AuditEvent a) {
                    return switch (columnIndex) {
                        case 0 -> a.eventTime().toString();
                        case 1 -> a.actorId();
                        case 2 -> a.action();
                        case 3 -> a.targetType() + (a.targetId() != null ? (":" + a.targetId()) : "");
                        case 4 -> a.outcome();
                        case 5 -> a.detailsJson() != null ? a.detailsJson() : "";
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
        btnRefresh.setText("Refresh Audit Logs");
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createColumn(String title, int width) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
    }

    private void refreshData() {
        AuditEventRepository audit = TrystUiServices.getAuditEventRepository();
        if (audit == null) {
            String msg = "AuditEventRepository is not currently available in OSGi registry.";
            statusLabel.setText(msg);
            TrystLog.warn(AuditAndLogsView.class, msg);
            viewer.setInput(Collections.emptyList());
            return;
        }

        statusLabel.setText("");
        viewer.setInput(audit.findRecent(50));
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
