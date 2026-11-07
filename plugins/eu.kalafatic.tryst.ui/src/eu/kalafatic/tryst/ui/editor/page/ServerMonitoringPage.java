package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.domain.model.ManagedProcessRecord;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import java.util.Collections;

/**
 * Editor page for Server Overview, Runtime Monitoring, and Managed OS Subprocesses.
 */
public class ServerMonitoringPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.serverMonitoring";

    private Label lblReadiness;
    private Label lblHttpStatus;
    private Label lblHttpPort;
    private Label lblCanonicalUrl;
    private Label lblSystemCpu;
    private Label lblHeap;
    private Label lblThreads;
    private TableViewer processViewer;

    public ServerMonitoringPage(TrystWorkbenchEditor editor) {
        super(ID, "Server Monitoring", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group serverGroup = new Group(parent, SWT.NONE);
        serverGroup.setText("Embedded HTTP Server & Network");
        serverGroup.setLayout(new GridLayout(2, false));
        serverGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(serverGroup, "HTTP Server Lifecycle:");
        lblHttpStatus = createValueLabel(serverGroup, "STOPPED");

        createRow(serverGroup, "Listening Address & Port:");
        lblHttpPort = createValueLabel(serverGroup, "-");

        createRow(serverGroup, "Canonical Application URL:");
        lblCanonicalUrl = createValueLabel(serverGroup, "http://localhost/tryst");

        Composite serverBtnBar = new Composite(serverGroup, SWT.NONE);
        serverBtnBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        serverBtnBar.setLayout(new GridLayout(4, false));

        Button btnStartServer = new Button(serverBtnBar, SWT.PUSH);
        btnStartServer.setText("▶ Start Server");
        btnStartServer.addListener(SWT.Selection, e -> {
            try {
                var s = TrystUiServices.startHttpServer();
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                        parent.getShell(),
                        "Server Started",
                        "Embedded HTTP server is active and listening on " + s.getHost() + ":" + s.getPort() + "."
                );
            } catch (Throwable ex) {
                Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                        ? ite.getCause() : ex;
                org.eclipse.jface.dialogs.MessageDialog.openError(
                        parent.getShell(),
                        "Server Startup Failure",
                        "Failed to start embedded HTTP server:\n\n" + cause.getMessage()
                );
            }
            refresh();
        });

        Button btnStopServer = new Button(serverBtnBar, SWT.PUSH);
        btnStopServer.setText("⏹ Stop Server");
        btnStopServer.addListener(SWT.Selection, e -> {
            boolean confirm = org.eclipse.jface.dialogs.MessageDialog.openConfirm(
                    parent.getShell(), "Stop Server", "Are you sure you want to stop the embedded HTTP server?");
            if (confirm) {
                try {
                    TrystUiServices.stopHttpServer();
                } catch (Throwable ex) {
                    Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                            ? ite.getCause() : ex;
                    org.eclipse.jface.dialogs.MessageDialog.openError(
                            parent.getShell(), "Server Error", "Failed to stop HTTP server: " + cause.getMessage());
                }
            }
            refresh();
        });

        Button btnRestartServer = new Button(serverBtnBar, SWT.PUSH);
        btnRestartServer.setText("🔄 Restart Server");
        btnRestartServer.addListener(SWT.Selection, e -> {
            try {
                var s = TrystUiServices.restartHttpServer();
                org.eclipse.jface.dialogs.MessageDialog.openInformation(
                        parent.getShell(),
                        "Server Restarted",
                        "Embedded HTTP server restarted and listening on " + s.getHost() + ":" + s.getPort() + "."
                );
            } catch (Throwable ex) {
                Throwable cause = (ex instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                        ? ite.getCause() : ex;
                org.eclipse.jface.dialogs.MessageDialog.openError(
                        parent.getShell(),
                        "Server Restart Failure",
                        "Failed to restart embedded HTTP server:\n\n" + cause.getMessage()
                );
            }
            refresh();
        });

        Button btnRefreshServer = new Button(serverBtnBar, SWT.PUSH);
        btnRefreshServer.setText("Refresh Status");
        btnRefreshServer.addListener(SWT.Selection, e -> refresh());

        Group healthGroup = new Group(parent, SWT.NONE);
        healthGroup.setText("Platform Health & Resources");
        healthGroup.setLayout(new GridLayout(2, false));
        healthGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(healthGroup, "Platform Readiness:");
        lblReadiness = createValueLabel(healthGroup, "Checking...");

        createRow(healthGroup, "System CPU Load:");
        lblSystemCpu = createValueLabel(healthGroup, "-");

        createRow(healthGroup, "Heap Memory (Used / Max):");
        lblHeap = createValueLabel(healthGroup, "-");

        createRow(healthGroup, "Active JVM Threads:");
        lblThreads = createValueLabel(healthGroup, "-");

        Group procGroup = new Group(parent, SWT.NONE);
        procGroup.setText("Managed OS Subprocesses");
        procGroup.setLayout(new GridLayout(1, false));
        procGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        processViewer = new TableViewer(procGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = processViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("PID", 80);
        createColumn("Command Class", 160);
        createColumn("Working Directory", 250);
        createColumn("Status", 100);
        createColumn("Duration (ms)", 100);

        processViewer.setContentProvider(ArrayContentProvider.getInstance());
        processViewer.setLabelProvider(new ProcessLabelProvider());

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Runtime Metrics");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
    }

    private void createRow(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(processViewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void refresh() {
        if (lblReadiness == null || lblReadiness.isDisposed()) return;

        var httpService = TrystUiServices.getHttpService();
        if (httpService != null) {
            boolean running = httpService.isRunning();
            lblHttpStatus.setText(running ? "RUNNING" : "STOPPED");
            if (running) {
                lblHttpPort.setText(httpService.getHost() + ":" + httpService.getPort());
            } else {
                lblHttpPort.setText("Not listening");
            }
        } else {
            lblHttpStatus.setText("NOT_REGISTERED");
            lblHttpPort.setText("-");
        }

        var cfgService = TrystUiServices.getConfigurationService();
        if (cfgService != null && cfgService.getConfiguration() != null) {
            var cfg = cfgService.getConfiguration();
            if (httpService != null && httpService.isRunning()) {
                lblCanonicalUrl.setText(cfg.resolveEffectiveExternalUrl(httpService.getPort()) + " (Canonical: " + cfg.canonicalUrl() + ")");
            } else {
                lblCanonicalUrl.setText(cfg.canonicalUrl());
            }
        } else {
            lblCanonicalUrl.setText("http://localhost/tryst");
        }

        var readiness = TrystUiServices.getServerReadinessService();
        if (readiness != null) {
            ServerReadinessService.ReadinessReport rep = readiness.evaluateReadiness(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.WORKBENCH_RUNNING);
            lblReadiness.setText(rep.ready() ? "READY (Healthy)" : (rep.degraded() ? "DEGRADED" : "INITIALIZING"));
        } else {
            lblReadiness.setText(httpService != null && httpService.isRunning() ? "READY (Healthy)" : "INITIALIZING");
        }

        ServerMonitoringService mon = TrystUiServices.getServerMonitoringService();
        if (mon != null) {
            var m = mon.collectMetrics();
            lblSystemCpu.setText(String.format("%.1f %% (proc: %.1f %%)", m.systemCpuLoad(), m.processCpuLoad()));
            lblHeap.setText(String.format("%d MB / %d MB", m.heapUsedBytes() / (1024 * 1024), m.heapMaxBytes() / (1024 * 1024)));
            lblThreads.setText(String.format("%d (daemon: %d)", m.threadCount(), m.daemonThreadCount()));
        }

        ProcessExecutionManager procMgr = TrystUiServices.getProcessExecutionManager();
        if (procMgr != null) {
            processViewer.setInput(procMgr.getManagedProcesses());
        } else {
            processViewer.setInput(Collections.emptyList());
        }
    }

    private static class ProcessLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
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
    }
}
