package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/**
 * Overview & Dashboard page displaying system health, active project, and high-level platform status.
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

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Dashboard");
        btnRefresh.addListener(SWT.Selection, e -> refresh());
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
