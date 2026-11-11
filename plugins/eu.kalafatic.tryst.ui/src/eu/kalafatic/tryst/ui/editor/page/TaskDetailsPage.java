package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskEvent;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Graphical Task Details, Inspection and Monitoring Page in the TRYST Multipage Editor.
 * Combines:
 * - High-level Task Overview with status badges and metadata.
 * - Graphical Execution Lifecycle timeline showing stage transitions, duration, and status.
 * - Comprehensive Tabbed Sections: Overview, Timeline, Execution Logs, Code Reviews, Events, and Diagnostics.
 * Automatically synchronizes with WorkbenchContext when a task or session is selected.
 */
public class TaskDetailsPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.taskDetails";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ComboViewer taskSelectorCombo;
    private Task activeTask;
    private AgentTask activeAgentTask;

    // Overview Labels
    private Label lblTaskId;
    private Label lblTaskTitle;
    private Label lblStatusBadge;
    private Label lblPriority;
    private Label lblProjectRepo;
    private Label lblSession;
    private Label lblWorker;
    private Label lblCreatedTime;
    private Label lblDuration;
    private Text txtPromptDescription;

    // Lifecycle Timeline Widgets
    private Composite timelineContainer;
    private final List<Label> stageBadges = new ArrayList<>();

    // Tabbed Detail Areas
    private CTabFolder detailTabFolder;
    private Table eventsTable;
    private StyledText logOutputText;
    private Table reviewsTable;
    private StyledText diagnosticText;

    public TaskDetailsPage(TrystWorkbenchEditor editor) {
        super(ID, "Task Details", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar Control Bar
        Composite controlBar = new Composite(parent, SWT.NONE);
        controlBar.setLayout(new GridLayout(6, false));
        controlBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblSelect = new Label(controlBar, SWT.NONE);
        lblSelect.setText("Target Task:");

        taskSelectorCombo = new ComboViewer(controlBar, SWT.READ_ONLY);
        taskSelectorCombo.setContentProvider(ArrayContentProvider.getInstance());
        taskSelectorCombo.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Task t) {
                    return "[" + t.getStatus() + "] " + t.getTitle();
                } else if (element instanceof AgentTask at) {
                    return "[" + at.getStatus() + "] " + at.getTitle();
                }
                return super.getText(element);
            }
        });
        taskSelectorCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        taskSelectorCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty()) {
                Object obj = sel.getFirstElement();
                if (obj instanceof Task t) {
                    setActiveTask(t);
                } else if (obj instanceof AgentTask at) {
                    setActiveAgentTask(at);
                }
            }
        });

        Button btnRefresh = new Button(controlBar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnRunAgent = new Button(controlBar, SWT.PUSH);
        btnRunAgent.setText("▶ Execute in Agent");
        btnRunAgent.addListener(SWT.Selection, e -> {
            if (activeAgentTask != null) {
                WorkbenchContextManager.getInstance().setContext(
                        WorkbenchContextManager.getInstance().getContext().toBuilder()
                                .task(activeAgentTask)
                                .build()
                );
            }
            editor.selectPage(TaskExecutionPage.ID);
        });

        Button btnOpenReview = new Button(controlBar, SWT.PUSH);
        btnOpenReview.setText("🔍 Open Review");
        btnOpenReview.addListener(SWT.Selection, e -> {
            editor.selectPage(CodeReviewPage.ID);
        });

        Button btnCopyDetails = new Button(controlBar, SWT.PUSH);
        btnCopyDetails.setText("📋 Copy Details");
        btnCopyDetails.addListener(SWT.Selection, e -> copyTaskDetailsToClipboard());

        // Vertical Sash: Top = Overview & Timeline (40%), Bottom = Tabs for Logs/Events/Reviews (60%)
        SashForm mainSash = new SashForm(parent, SWT.VERTICAL);
        mainSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite topOverviewSection = new Composite(mainSash, SWT.NONE);
        topOverviewSection.setLayout(new GridLayout(2, false));

        createTaskOverviewCard(topOverviewSection);
        createLifecycleTimelineCard(topOverviewSection);

        createDetailTabs(mainSash);

        mainSash.setWeights(42, 58);

        EventBus eventBus = TrystUiServices.getEventBus();
        if (eventBus != null) {
            eventBus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, e -> {
                if (activeTask != null && activeTask.getId().equals(e.taskId())) {
                    Display display = getControl().getDisplay();
                    if (!display.isDisposed()) {
                        display.asyncExec(this::refresh);
                    }
                }
            });
        }

        refresh();
    }

    private void createTaskOverviewCard(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Task Overview & Metadata");
        group.setLayout(new GridLayout(4, false));
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        group.setLayoutData(gd);

        createRowLabel(group, "Task ID:");
        lblTaskId = createValueLabel(group, "None");

        createRowLabel(group, "Status:");
        lblStatusBadge = createValueLabel(group, "-");

        createRowLabel(group, "Title:");
        lblTaskTitle = createValueLabel(group, "-");

        createRowLabel(group, "Priority:");
        lblPriority = createValueLabel(group, "-");

        createRowLabel(group, "Project / Repo:");
        lblProjectRepo = createValueLabel(group, "-");

        createRowLabel(group, "Session:");
        lblSession = createValueLabel(group, "-");

        createRowLabel(group, "Assigned Worker:");
        lblWorker = createValueLabel(group, "-");

        createRowLabel(group, "Created / Duration:");
        lblDuration = createValueLabel(group, "-");

        Label descLbl = new Label(group, SWT.NONE);
        descLbl.setText("Task Prompt / Goal:");
        GridData descGd = new GridData(SWT.FILL, SWT.TOP, false, false, 4, 1);
        descLbl.setLayoutData(descGd);

        txtPromptDescription = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData txtGd = new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1);
        txtGd.heightHint = 70;
        txtPromptDescription.setLayoutData(txtGd);
    }

    private void createLifecycleTimelineCard(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Execution Lifecycle Timeline");
        group.setLayout(new GridLayout(1, false));
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        group.setLayoutData(gd);

        timelineContainer = new Composite(group, SWT.NONE);
        timelineContainer.setLayout(new GridLayout(10, false));
        timelineContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TaskStatus[] stages = {
                TaskStatus.CREATED,
                TaskStatus.QUEUED,
                TaskStatus.CLONING,
                TaskStatus.ANALYZING,
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.BUILDING,
                TaskStatus.TESTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.COMPLETED
        };

        stageBadges.clear();
        for (int i = 0; i < stages.length; i++) {
            Composite stageComp = new Composite(timelineContainer, SWT.BORDER);
            stageComp.setLayout(new GridLayout(1, false));
            stageComp.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Label stepLbl = new Label(stageComp, SWT.CENTER);
            stepLbl.setText((i + 1) + ". " + stages[i].name());
            stepLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

            Label statusLbl = new Label(stageComp, SWT.CENTER);
            statusLbl.setText("WAITING");
            statusLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));

            stageBadges.add(statusLbl);
        }
    }

    private void createDetailTabs(Composite parent) {
        detailTabFolder = new CTabFolder(parent, SWT.BORDER | SWT.FLAT);
        detailTabFolder.setSimple(false);

        // Tab 1: Lifecycle Events
        CTabItem itemEvents = new CTabItem(detailTabFolder, SWT.NONE);
        itemEvents.setText("📋 Lifecycle Events");
        eventsTable = new Table(detailTabFolder, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        eventsTable.setHeaderVisible(true);
        eventsTable.setLinesVisible(true);
        createTableColumn(eventsTable, "Timestamp", 140);
        createTableColumn(eventsTable, "Stage / Transition", 160);
        createTableColumn(eventsTable, "Message / Details", 400);
        itemEvents.setControl(eventsTable);

        // Tab 2: Execution Logs & Reasoning
        CTabItem itemLogs = new CTabItem(detailTabFolder, SWT.NONE);
        itemLogs.setText("📜 Execution Logs");
        logOutputText = new StyledText(detailTabFolder, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        logOutputText.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        itemLogs.setControl(logOutputText);

        // Tab 3: Code Reviews
        CTabItem itemReviews = new CTabItem(detailTabFolder, SWT.NONE);
        itemReviews.setText("🔍 Code Reviews");
        reviewsTable = new Table(detailTabFolder, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        reviewsTable.setHeaderVisible(true);
        reviewsTable.setLinesVisible(true);
        createTableColumn(reviewsTable, "Review ID", 120);
        createTableColumn(reviewsTable, "Status", 120);
        createTableColumn(reviewsTable, "Author", 120);
        createTableColumn(reviewsTable, "Created", 160);
        createTableColumn(reviewsTable, "Summary", 350);
        itemReviews.setControl(reviewsTable);

        // Tab 4: Diagnostics
        CTabItem itemDiag = new CTabItem(detailTabFolder, SWT.NONE);
        itemDiag.setText("🩺 Diagnostics & Failures");
        diagnosticText = new StyledText(detailTabFolder, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        diagnosticText.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        itemDiag.setControl(diagnosticText);

        detailTabFolder.setSelection(0);
    }

    private void createTableColumn(Table table, String text, int width) {
        TableColumn col = new TableColumn(table, SWT.LEFT);
        col.setText(text);
        col.setWidth(width);
    }

    private void createRowLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent, String def) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText(def);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    public void setActiveTask(Task task) {
        this.activeTask = task;
        this.activeAgentTask = task != null ? task.toAgentTask() : null;
        updateView();
    }

    public void setActiveAgentTask(AgentTask agentTask) {
        this.activeAgentTask = agentTask;
        if (agentTask != null) {
            this.activeTask = Task.fromAgentTask(agentTask);
        } else {
            this.activeTask = null;
        }
        updateView();
    }

    private void updateView() {
        if (lblTaskId == null || lblTaskId.isDisposed()) return;
        if (activeTask == null && activeAgentTask == null) {
            lblTaskId.setText("None");
            lblTaskTitle.setText("No task selected");
            lblStatusBadge.setText("-");
            lblPriority.setText("-");
            lblProjectRepo.setText("-");
            lblSession.setText("-");
            lblWorker.setText("-");
            lblDuration.setText("-");
            txtPromptDescription.setText("");
            eventsTable.removeAll();
            logOutputText.setText("");
            reviewsTable.removeAll();
            diagnosticText.setText("No task selected for inspection.");
            resetTimeline();
            return;
        }

        String id = activeTask != null ? activeTask.getId() : activeAgentTask.getId();
        String title = activeTask != null ? activeTask.getTitle() : activeAgentTask.getTitle();
        String status = activeTask != null ? activeTask.getStatus().name() : activeAgentTask.getStatus().name();
        String desc = activeTask != null ? activeTask.getDescription() : activeAgentTask.getDescription();
        String priority = activeTask != null ? activeTask.getPriority().name() : String.valueOf(activeAgentTask.getPriority());
        String worker = activeTask != null ? (activeTask.getWorkerId() != null ? activeTask.getWorkerId() : "Unassigned") : "Worker";
        String projId = activeTask != null ? activeTask.getProjectId() : activeAgentTask.getProjectId();
        String sessId = activeTask != null ? activeTask.getSessionId() : activeAgentTask.getSessionId();
        Instant created = activeTask != null ? activeTask.getCreatedAt() : activeAgentTask.getCreatedAt();
        Instant completed = activeTask != null ? activeTask.getCompletedAt() : activeAgentTask.getCompletedAt();

        lblTaskId.setText(id);
        lblTaskTitle.setText(title != null ? title : "");
        lblStatusBadge.setText(status);
        lblPriority.setText(priority);
        lblProjectRepo.setText(projId != null ? projId : "Default Project");
        lblSession.setText(sessId != null ? sessId : "Default Session");
        lblWorker.setText(worker);

        String timeStr = created != null ? TIME_FMT.format(created) : "N/A";
        if (completed != null && created != null) {
            Duration dur = Duration.between(created, completed);
            timeStr += " (" + dur.toSeconds() + "s duration)";
        }
        lblDuration.setText(timeStr);
        txtPromptDescription.setText(desc != null ? desc : "");

        updateTimeline(status);
        loadTaskEvents(id);
        loadTaskReviews(id);
        loadTaskDiagnostics();
    }

    private void resetTimeline() {
        for (Label lbl : stageBadges) {
            if (!lbl.isDisposed()) {
                lbl.setText("WAITING");
            }
        }
    }

    private void updateTimeline(String currentStatus) {
        resetTimeline();
        TaskStatus[] stages = {
                TaskStatus.CREATED,
                TaskStatus.QUEUED,
                TaskStatus.CLONING,
                TaskStatus.ANALYZING,
                TaskStatus.PLANNING,
                TaskStatus.IMPLEMENTING,
                TaskStatus.BUILDING,
                TaskStatus.TESTING,
                TaskStatus.WAITING_FOR_REVIEW,
                TaskStatus.COMPLETED
        };

        boolean activeEncountered = false;
        boolean isFailed = "FAILED".equalsIgnoreCase(currentStatus);
        boolean isCompleted = "COMPLETED".equalsIgnoreCase(currentStatus);

        for (int i = 0; i < stages.length; i++) {
            Label lbl = stageBadges.get(i);
            if (lbl.isDisposed()) continue;

            if (stages[i].name().equalsIgnoreCase(currentStatus)) {
                lbl.setText("⚙️ ACTIVE");
                activeEncountered = true;
            } else if (!activeEncountered && !isCompleted && !isFailed) {
                lbl.setText("✅ PASSED");
            } else if (isCompleted) {
                lbl.setText("✅ PASSED");
            } else if (isFailed) {
                if (i == stages.length - 2) {
                    lbl.setText("❌ FAILED");
                    break;
                } else {
                    lbl.setText("✅ PASSED");
                }
            } else {
                lbl.setText("PENDING");
            }
        }
    }

    private void loadTaskEvents(String taskId) {
        eventsTable.removeAll();
        var taskRepo = TrystUiServices.getTaskRepository();
        if (taskRepo != null) {
            List<TaskEvent> events = taskRepo.findEventsByTaskId(taskId);
            for (TaskEvent ev : events) {
                TableItem item = new TableItem(eventsTable, SWT.NONE);
                item.setText(new String[]{
                        ev.getTimestamp() != null ? TIME_FMT.format(ev.getTimestamp()) : "",
                        ev.getStatus() != null ? ev.getStatus().name() : "EVENT",
                        ev.getMessage() != null ? ev.getMessage() : ""
                });
            }
        }
    }

    private void loadTaskReviews(String taskId) {
        reviewsTable.removeAll();
        var reviewRepo = TrystUiServices.getCodeReviewRepository();
        if (reviewRepo != null) {
            for (CodeReview r : reviewRepo.findAll()) {
                if (taskId.equals(r.getTaskId())) {
                    TableItem item = new TableItem(reviewsTable, SWT.NONE);
                    item.setText(new String[]{
                            r.getId(),
                            r.getStatus() != null ? r.getStatus().name() : "",
                            r.getApprovedBy() != null ? r.getApprovedBy() : (r.getRejectedBy() != null ? r.getRejectedBy() : "Pending"),
                            r.getCreatedAt() != null ? TIME_FMT.format(r.getCreatedAt()) : "",
                            r.getBuildStatus() != null ? "Build: " + r.getBuildStatus() : "No build data"
                    });
                }
            }
        }
    }

    private void loadTaskDiagnostics() {
        StringBuilder sb = new StringBuilder();
        if (activeTask != null) {
            sb.append("--- Task Operational State ---\n");
            sb.append("Task ID: ").append(activeTask.getId()).append("\n");
            sb.append("Status: ").append(activeTask.getStatus()).append("\n");
            sb.append("Rework Count: ").append(activeTask.getReworkCount()).append("\n");
            sb.append("Failure Reason: ").append(activeTask.getFailureReason() != null ? activeTask.getFailureReason() : "None").append("\n");
            sb.append("Execution Result ID: ").append(activeTask.getExecutionResultId() != null ? activeTask.getExecutionResultId() : "N/A").append("\n");
            sb.append("Review ID: ").append(activeTask.getReviewId() != null ? activeTask.getReviewId() : "N/A").append("\n");
        }
        diagnosticText.setText(sb.toString());
    }

    private void copyTaskDetailsToClipboard() {
        if (activeTask == null && activeAgentTask == null) return;
        Clipboard cb = new Clipboard(getControl().getDisplay());
        try {
            String text = String.format("Task ID: %s\nTitle: %s\nStatus: %s\nPriority: %s\nWorker: %s\nCreated: %s\nDescription:\n%s",
                    lblTaskId.getText(), lblTaskTitle.getText(), lblStatusBadge.getText(), lblPriority.getText(),
                    lblWorker.getText(), lblDuration.getText(), txtPromptDescription.getText());
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        super.onContextChanged(context);
        if (context.task() != null) {
            setActiveAgentTask(context.task());
            if (taskSelectorCombo != null && !taskSelectorCombo.getControl().isDisposed()) {
                taskSelectorCombo.setSelection(new StructuredSelection(context.task()));
            }
        }
    }

    @Override
    public void refresh() {
        super.refresh();
        if (taskSelectorCombo == null || taskSelectorCombo.getControl().isDisposed()) return;
        var taskRepo = TrystUiServices.getTaskRepository();
        if (taskRepo != null) {
            List<Task> tasks = taskRepo.findAllTasks();
            taskSelectorCombo.setInput(tasks);
            if (activeTask != null) {
                taskSelectorCombo.setSelection(new StructuredSelection(activeTask));
            } else if (!tasks.isEmpty()) {
                setActiveTask(tasks.get(0));
                taskSelectorCombo.setSelection(new StructuredSelection(tasks.get(0)));
            }
        }
        updateView();
    }
}
