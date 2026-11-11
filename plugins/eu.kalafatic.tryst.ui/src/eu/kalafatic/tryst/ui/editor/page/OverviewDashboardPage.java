package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;

/**
 * Overview & Dashboard page displaying system health, active project, and high-level platform status.
 * Equipped with standardized action toolbar controls.
 */
public class OverviewDashboardPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.dashboard";

    private Label lblActiveProject;
    private Label lblActiveSession;
    private Label lblActiveTask;
    private Label lblServerReadiness;
    private Label lblCpu;
    private Label lblHeap;

    public OverviewDashboardPage(TrystWorkbenchEditor editor) {
        super(ID, "Overview", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar Control Bar
        Composite controlBar = new Composite(parent, SWT.NONE);
        controlBar.setLayout(new GridLayout(4, false));
        controlBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(controlBar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Dashboard");
        btnRefresh.setToolTipText("Refresh all dashboard metrics and system status");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnClearContext = new Button(controlBar, SWT.PUSH);
        btnClearContext.setText("✖ Reset Context");
        btnClearContext.setToolTipText("Clear active project, task, and session selections");
        btnClearContext.addListener(SWT.Selection, e -> {
            WorkbenchContextManager.getInstance().reset();
            refresh();
        });

        Button btnCopyInfo = new Button(controlBar, SWT.PUSH);
        btnCopyInfo.setText("📋 Copy Status Summary");
        btnCopyInfo.setToolTipText("Copy current dashboard system state to system clipboard");
        btnCopyInfo.addListener(SWT.Selection, e -> copyStatusSummary());

        Button btnOpenServerPage = new Button(controlBar, SWT.PUSH);
        btnOpenServerPage.setText("📊 Server Metrics");
        btnOpenServerPage.setToolTipText("Switch to detailed Server Monitoring page");
        btnOpenServerPage.addListener(SWT.Selection, e -> editor.selectPage(ServerMonitoringPage.ID));

        Group statusGroup = new Group(parent, SWT.NONE);
        statusGroup.setText("Workspace Status");
        statusGroup.setLayout(new GridLayout(2, false));
        statusGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(statusGroup, "Selected Project:");
        lblActiveProject = createValueLabel(statusGroup, "None");

        createRow(statusGroup, "Active Session:");
        lblActiveSession = createValueLabel(statusGroup, "None");

        createRow(statusGroup, "Selected Task:");
        lblActiveTask = createValueLabel(statusGroup, "None");

        Group metricsGroup = new Group(parent, SWT.NONE);
        metricsGroup.setText("Runtime & Environment Metrics");
        metricsGroup.setLayout(new GridLayout(2, false));
        metricsGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(metricsGroup, "Server Readiness:");
        lblServerReadiness = createValueLabel(metricsGroup, "Checking...");

        createRow(metricsGroup, "CPU Load:");
        lblCpu = createValueLabel(metricsGroup, "-");

        createRow(metricsGroup, "JVM Heap (Used / Max):");
        lblHeap = createValueLabel(metricsGroup, "-");
    }

    private void copyStatusSummary() {
        Clipboard cb = new Clipboard(getControl().getDisplay());
        try {
            String text = String.format("TRYST System Status:\nReadiness: %s\nProject: %s\nSession: %s\nTask: %s\nCPU: %s\nHeap: %s",
                    lblServerReadiness.getText(), lblActiveProject.getText(), lblActiveSession.getText(),
                    lblActiveTask.getText(), lblCpu.getText(), lblHeap.getText());
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    private void createRow(Composite parent, String labelText) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent, String defaultVal) {
        Label val = new Label(parent, SWT.BORDER);
        val.setText(defaultVal);
        val.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return val;
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        super.onContextChanged(context);
        if (lblActiveProject != null && !lblActiveProject.isDisposed()) {
            lblActiveProject.setText(context.projectName() != null ? context.projectName() : "None");
        }
        if (lblActiveSession != null && !lblActiveSession.isDisposed()) {
            lblActiveSession.setText(context.sessionId() != null ? context.sessionId() : "None");
        }
        if (lblActiveTask != null && !lblActiveTask.isDisposed()) {
            lblActiveTask.setText(context.taskTitle() != null ? context.taskTitle() : "None");
        }
    }

    @Override
    public void refresh() {
        super.refresh();
        if (lblServerReadiness == null || lblServerReadiness.isDisposed()) return;

        var readinessService = TrystUiServices.getServerReadinessService();
        if (readinessService != null) {
            ServerReadinessService.ReadinessReport rep = readinessService.evaluateReadiness(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.WORKBENCH_RUNNING);
            lblServerReadiness.setText(rep.ready() ? "READY (Healthy)" : (rep.degraded() ? "DEGRADED" : "INITIALIZING"));
        } else {
            lblServerReadiness.setText("OPERATIONAL");
        }

        ServerMonitoringService mon = TrystUiServices.getServerMonitoringService();
        if (mon != null) {
            var m = mon.collectMetrics();
            lblCpu.setText(String.format("Sys: %.1f%% | Proc: %.1f%%", m.systemCpuLoad(), m.processCpuLoad()));
            lblHeap.setText(String.format("%d MB / %d MB", m.heapUsedBytes() / (1024 * 1024), m.heapMaxBytes() / (1024 * 1024)));
        }
    }
}
