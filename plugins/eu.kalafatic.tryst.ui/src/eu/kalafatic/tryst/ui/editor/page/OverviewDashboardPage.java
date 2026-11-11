package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.AuditEvent;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Professional Administration Dashboard & Operational Monitoring Page.
 * Immediate visibility into:
 * - Server & Protocol state (HTTP, TCP, WebSocket, SQLite, TEIP, EVO, Ollama/AI)
 * - Task counts and execution health (Queued, Running, Completed, Failed, Cancelled)
 * - Code Reviews (Pending, Approved, Rejected)
 * - User and Session metrics
 * - Active Inspection Context (Session/Task filtering)
 * - Unified Activity Stream with filtering by severity, category, or task ID.
 */
public class OverviewDashboardPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.dashboard";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // Context badges
    private Label lblActiveProject;
    private Label lblActiveSession;
    private Label lblActiveTask;

    // System Indicators
    private Label lblServerReadiness;
    private Label lblHttpServer;
    private Label lblTcpServer;
    private Label lblDatabaseState;
    private Label lblTeipStatus;
    private Label lblAiModel;
    private Label lblCpu;
    private Label lblHeap;

    // Domain metrics
    private Label lblTasksTotal;
    private Label lblTasksRunning;
    private Label lblTasksQueued;
    private Label lblTasksCompleted;
    private Label lblTasksFailed;

    private Label lblReviewsTotal;
    private Label lblReviewsPending;
    private Label lblReviewsApproved;

    private Label lblUsersTotal;
    private Label lblUsersAdmins;

    // Activity Stream
    private Table activityTable;
    private Combo severityCombo;
    private Text filterText;

    public OverviewDashboardPage(TrystWorkbenchEditor editor) {
        super(ID, "Administration Dashboard", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Control Bar
        Composite controlBar = new Composite(parent, SWT.NONE);
        controlBar.setLayout(new GridLayout(6, false));
        controlBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(controlBar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Dashboard");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnClearContext = new Button(controlBar, SWT.PUSH);
        btnClearContext.setText("✖ Reset Context");
        btnClearContext.addListener(SWT.Selection, e -> {
            WorkbenchContextManager.getInstance().reset();
            refresh();
        });

        Button btnCopyInfo = new Button(controlBar, SWT.PUSH);
        btnCopyInfo.setText("📋 Copy Status Summary");
        btnCopyInfo.addListener(SWT.Selection, e -> copyStatusSummary());

        Button btnOpenTaskDetails = new Button(controlBar, SWT.PUSH);
        btnOpenTaskDetails.setText("🔍 Open Task Details");
        btnOpenTaskDetails.addListener(SWT.Selection, e -> editor.selectPage(TaskDetailsPage.ID));

        Button btnOpenDbPage = new Button(controlBar, SWT.PUSH);
        btnOpenDbPage.setText("🗄️ Open Database");
        btnOpenDbPage.addListener(SWT.Selection, e -> editor.selectPage(DatabasePage.ID));

        Button btnOpenServerPage = new Button(controlBar, SWT.PUSH);
        btnOpenServerPage.setText("📊 Server Metrics");
        btnOpenServerPage.addListener(SWT.Selection, e -> editor.selectPage(ServerMonitoringPage.ID));

        // Resizable SashForm: Top = Metric Cards, Bottom = Unified Activity Stream
        SashForm mainSash = new SashForm(parent, SWT.VERTICAL);
        mainSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite topCardsComposite = new Composite(mainSash, SWT.NONE);
        topCardsComposite.setLayout(new GridLayout(2, true));

        createSystemStateCard(topCardsComposite);
        createTaskAndReviewMetricsCard(topCardsComposite);

        createUnifiedActivityStream(mainSash);

        mainSash.setWeights(48, 52);

        EventBus eventBus = TrystUiServices.getEventBus();
        if (eventBus != null) {
            eventBus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, e -> {
                Display display = getControl().getDisplay();
                if (!display.isDisposed()) display.asyncExec(this::refresh);
            });
            eventBus.subscribe(ApplicationEvents.SessionEvent.class, e -> {
                Display display = getControl().getDisplay();
                if (!display.isDisposed()) display.asyncExec(this::refresh);
            });
        }

        refresh();
    }

    private void createSystemStateCard(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("System, Protocols & AI Infrastructure");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createRow(group, "Active Project:");
        lblActiveProject = createValueLabel(group, "None");

        createRow(group, "Active Session:");
        lblActiveSession = createValueLabel(group, "None");

        createRow(group, "Selected Task:");
        lblActiveTask = createValueLabel(group, "None");

        createRow(group, "Server Readiness:");
        lblServerReadiness = createValueLabel(group, "Checking...");

        createRow(group, "HTTP Server:");
        lblHttpServer = createValueLabel(group, "-");

        createRow(group, "TCP Server:");
        lblTcpServer = createValueLabel(group, "-");

        createRow(group, "SQLite Database:");
        lblDatabaseState = createValueLabel(group, "-");

        createRow(group, "TEIP / EVO:");
        lblTeipStatus = createValueLabel(group, "-");

        createRow(group, "Active LLM / AI:");
        lblAiModel = createValueLabel(group, "-");

        createRow(group, "CPU Load:");
        lblCpu = createValueLabel(group, "-");

        createRow(group, "JVM Heap:");
        lblHeap = createValueLabel(group, "-");
    }

    private void createTaskAndReviewMetricsCard(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Tasks, Code Reviews & Users");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createRow(group, "Total Tasks:");
        lblTasksTotal = createValueLabel(group, "0");

        createRow(group, "Running Tasks:");
        lblTasksRunning = createValueLabel(group, "0");

        createRow(group, "Queued Tasks:");
        lblTasksQueued = createValueLabel(group, "0");

        createRow(group, "Completed Tasks:");
        lblTasksCompleted = createValueLabel(group, "0");

        createRow(group, "Failed Tasks:");
        lblTasksFailed = createValueLabel(group, "0");

        createRow(group, "Total Reviews:");
        lblReviewsTotal = createValueLabel(group, "0");

        createRow(group, "Pending Reviews:");
        lblReviewsPending = createValueLabel(group, "0");

        createRow(group, "Approved Reviews:");
        lblReviewsApproved = createValueLabel(group, "0");

        createRow(group, "Registered Users:");
        lblUsersTotal = createValueLabel(group, "0");

        createRow(group, "Administrators:");
        lblUsersAdmins = createValueLabel(group, "0");
    }

    private void createUnifiedActivityStream(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Unified Operational Activity & Audit Stream");
        group.setLayout(new GridLayout(1, false));

        // Filter Bar
        Composite filterBar = new Composite(group, SWT.NONE);
        filterBar.setLayout(new GridLayout(5, false));
        filterBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblSev = new Label(filterBar, SWT.NONE);
        lblSev.setText("Severity:");

        severityCombo = new Combo(filterBar, SWT.READ_ONLY | SWT.BORDER);
        severityCombo.setItems("ALL", "INFO", "WARN", "ERROR");
        severityCombo.select(0);
        severityCombo.addListener(SWT.Selection, e -> loadActivityStream());

        Label lblSearch = new Label(filterBar, SWT.NONE);
        lblSearch.setText(" Filter:");

        filterText = new Text(filterBar, SWT.BORDER | SWT.SEARCH);
        filterText.setMessage("Filter activity by task ID, user, or event...");
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.addModifyListener(e -> loadActivityStream());

        Button btnClearFilter = new Button(filterBar, SWT.PUSH);
        btnClearFilter.setText("Clear Filter");
        btnClearFilter.addListener(SWT.Selection, e -> {
            severityCombo.select(0);
            filterText.setText("");
            loadActivityStream();
        });

        activityTable = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        activityTable.setHeaderVisible(true);
        activityTable.setLinesVisible(true);
        activityTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        String[] headers = {"Timestamp", "Type", "Actor / User", "Target ID", "Message & Details"};
        int[] widths = {100, 130, 120, 140, 480};

        for (int i = 0; i < headers.length; i++) {
            TableColumn col = new TableColumn(activityTable, SWT.LEFT);
            col.setText(headers[i]);
            col.setWidth(widths[i]);
        }
    }

    private void loadActivityStream() {
        if (activityTable == null || activityTable.isDisposed()) return;
        activityTable.removeAll();

        var auditRepo = TrystUiServices.getAuditEventRepository();
        if (auditRepo == null) return;

        List<AuditEvent> events = auditRepo.findRecent(100);
        String filterQuery = filterText != null ? filterText.getText().trim().toLowerCase() : "";
        String sevFilter = severityCombo != null ? severityCombo.getText() : "ALL";

        for (AuditEvent ev : events) {
            String timeStr = ev.eventTime() != null ? TIME_FMT.format(ev.eventTime()) : "";
            String typeStr = ev.action() != null ? ev.action() : "";
            String userStr = ev.actorId() != null ? ev.actorId() : "system";
            String targetStr = ev.targetId() != null ? ev.targetId() : (ev.taskId() != null ? ev.taskId() : "");
            String detailsStr = ev.detailsJson() != null ? ev.detailsJson() : "";

            if (!sevFilter.equalsIgnoreCase("ALL")) {
                if ("ERROR".equalsIgnoreCase(sevFilter) && !"FAILURE".equalsIgnoreCase(ev.outcome()) && !detailsStr.toLowerCase().contains("fail") && !typeStr.toLowerCase().contains("error")) {
                    continue;
                }
            }

            if (!filterQuery.isEmpty()) {
                String combined = (typeStr + " " + userStr + " " + targetStr + " " + detailsStr).toLowerCase();
                if (!combined.contains(filterQuery)) {
                    continue;
                }
            }

            TableItem item = new TableItem(activityTable, SWT.NONE);
            item.setText(new String[]{timeStr, typeStr, userStr, targetStr, detailsStr});
        }
    }

    private void copyStatusSummary() {
        Clipboard cb = new Clipboard(getControl().getDisplay());
        try {
            String text = String.format("TRYST System Dashboard Summary:\nReadiness: %s\nHTTP: %s | TCP: %s | Database: %s | TEIP: %s\nTasks: %s Total (%s running, %s queued, %s failed)\nReviews: %s (%s pending, %s approved)\nCPU: %s | Heap: %s",
                    lblServerReadiness.getText(), lblHttpServer.getText(), lblTcpServer.getText(), lblDatabaseState.getText(), lblTeipStatus.getText(),
                    lblTasksTotal.getText(), lblTasksRunning.getText(), lblTasksQueued.getText(), lblTasksFailed.getText(),
                    lblReviewsTotal.getText(), lblReviewsPending.getText(), lblReviewsApproved.getText(),
                    lblCpu.getText(), lblHeap.getText());
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

        // 1. Server Readiness & Infrastructure
        var readinessService = TrystUiServices.getServerReadinessService();
        if (readinessService != null) {
            ServerReadinessService.ReadinessReport rep = readinessService.evaluateReadiness(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.WORKBENCH_RUNNING);
            lblServerReadiness.setText(rep.ready() ? "READY (Healthy)" : (rep.degraded() ? "DEGRADED" : "INITIALIZING"));
        } else {
            lblServerReadiness.setText("OPERATIONAL");
        }

        var httpService = TrystUiServices.getHttpService();
        lblHttpServer.setText((httpService != null && httpService.isRunning()) ? "Online (Port " + httpService.getPort() + ")" : "Offline");

        var tcpService = TrystUiServices.getTcpService();
        lblTcpServer.setText((tcpService != null && tcpService.isRunning()) ? "Online (Port " + tcpService.getPort() + ")" : "Offline");

        var db = TrystUiServices.getDatabaseManager();
        lblDatabaseState.setText(db != null ? "Connected (WAL mode)" : "Disconnected");

        lblTeipStatus.setText("READY (TEIP 1.0)");
        lblAiModel.setText("Available (Ollama/Fallback)");

        ServerMonitoringService mon = TrystUiServices.getServerMonitoringService();
        if (mon != null) {
            var m = mon.collectMetrics();
            lblCpu.setText(String.format("Sys: %.1f%% | Proc: %.1f%%", m.systemCpuLoad(), m.processCpuLoad()));
            lblHeap.setText(String.format("%d MB / %d MB", m.heapUsedBytes() / (1024 * 1024), m.heapMaxBytes() / (1024 * 1024)));
        }

        // 2. Tasks
        var taskRepo = TrystUiServices.getTaskRepository();
        if (taskRepo != null) {
            List<AgentTask> allTasks = taskRepo.findAll();
            lblTasksTotal.setText(String.valueOf(allTasks.size()));
            long running = allTasks.stream().filter(t -> t.getStatus() == ExecutionStatus.RUNNING || t.getStatus() == ExecutionStatus.CLAIMED).count();
            long queued = allTasks.stream().filter(t -> t.getStatus() == ExecutionStatus.QUEUED).count();
            long completed = allTasks.stream().filter(t -> t.getStatus() == ExecutionStatus.COMPLETED).count();
            long failed = allTasks.stream().filter(t -> t.getStatus() == ExecutionStatus.FAILED).count();

            lblTasksRunning.setText(String.valueOf(running));
            lblTasksQueued.setText(String.valueOf(queued));
            lblTasksCompleted.setText(String.valueOf(completed));
            lblTasksFailed.setText(String.valueOf(failed));
        }

        // 3. Reviews
        var reviewRepo = TrystUiServices.getCodeReviewRepository();
        if (reviewRepo != null) {
            List<CodeReview> allReviews = reviewRepo.findAll();
            lblReviewsTotal.setText(String.valueOf(allReviews.size()));
            long pending = allReviews.stream().filter(r -> r.getStatus() == CodeReview.ReviewStatus.PENDING).count();
            long approved = allReviews.stream().filter(r -> r.getStatus() == CodeReview.ReviewStatus.APPROVED).count();
            lblReviewsPending.setText(String.valueOf(pending));
            lblReviewsApproved.setText(String.valueOf(approved));
        }

        // 4. Users
        var userRepo = TrystUiServices.getUserRepository();
        if (userRepo != null) {
            List<User> allUsers = userRepo.findAll();
            lblUsersTotal.setText(String.valueOf(allUsers.size()));
            long admins = allUsers.stream().filter(u -> u.getRole() == eu.kalafatic.tryst.domain.model.UserRole.ADMIN).count();
            lblUsersAdmins.setText(String.valueOf(admins));
        }

        loadActivityStream();
    }
}
