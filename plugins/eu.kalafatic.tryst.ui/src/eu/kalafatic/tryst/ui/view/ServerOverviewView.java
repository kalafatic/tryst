package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

/**
 * Native SWT/JFace View displaying live host and JVM metrics.
 */
public class ServerOverviewView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.serverOverview";

    private Label lblSystemCpu;
    private Label lblProcessCpu;
    private Label lblHeapUsed;
    private Label lblThreads;
    private Label lblUptime;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        createLabel(parent, "System CPU Load:");
        lblSystemCpu = createValueLabel(parent);

        createLabel(parent, "Process CPU Load:");
        lblProcessCpu = createValueLabel(parent);

        createLabel(parent, "Heap Memory (Used / Max):");
        lblHeapUsed = createValueLabel(parent);

        createLabel(parent, "Active Threads:");
        lblThreads = createValueLabel(parent);

        createLabel(parent, "JVM Uptime:");
        lblUptime = createValueLabel(parent);

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Server Metrics");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1);
        btnRefresh.setLayoutData(gd);
        btnRefresh.addListener(SWT.Selection, e -> refreshMetrics());

        refreshMetrics();
    }

    private void createLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText("-");
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void refreshMetrics() {
        ServerMonitoringService mon = TrystUiPlugin.getDefault().getServerMonitoringService();
        if (mon != null) {
            ServerMonitoringService.SystemMetrics m = mon.collectMetrics();
            lblSystemCpu.setText(String.format("%.1f %%", m.systemCpuLoad()));
            lblProcessCpu.setText(String.format("%.1f %%", m.processCpuLoad()));
            lblHeapUsed.setText(String.format("%d MB / %d MB", m.heapUsedBytes() / (1024 * 1024), m.heapMaxBytes() / (1024 * 1024)));
            lblThreads.setText(String.format("%d (daemon: %d)", m.threadCount(), m.daemonThreadCount()));
            lblUptime.setText((m.jvmUptimeMs() / 1000) + " s");
        }
    }

    @Override
    public void setFocus() {
        if (lblSystemCpu != null && !lblSystemCpu.isDisposed()) {
            lblSystemCpu.setFocus();
        }
    }
}
