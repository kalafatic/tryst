package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.LogEntry;
import eu.kalafatic.tryst.core.log.LogLevel;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.controller.ConsoleOutputController;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Professional TRYST Central Logging Console view.
 * Features:
 * - Live log streaming with batched, thread-safe UI updates.
 * - Severity level filtering (ALL, TRACE, DEBUG, INFO, WARN, ERROR).
 * - Real-time substring search and module filtering.
 * - Pause and resume live streaming.
 * - Clear console without losing persistent logs.
 * - Copy selected entry or full exception details to clipboard.
 * - Detailed inspector pane showing metadata, correlation ID, task ID, thread, and stack trace.
 * - Contextual task navigation.
 */
public class ConsoleOutputView extends ViewPart implements eu.kalafatic.tryst.ui.context.IWorkbenchContextListener {

    public static final String ID = "eu.kalafatic.tryst.ui.view.consoleOutput";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private ConsoleOutputController controller;

    private Table logTable;
    private StyledText detailText;
    private Text searchBox;
    private Combo levelCombo;
    private Label contextLabel;
    private Label statusLabel;

    private Action pauseResumeAction;
    private Action clearAction;
    private Action copyAction;
    private Action copyDetailsAction;
    private Action navigateTaskAction;
    private Action followContextAction;
    private Action resetFilterAction;

    private Color errorColor;
    private Color warnColor;
    private Color infoColor;
    private Color debugColor;
    private Color traceColor;

    private final ConcurrentLinkedQueue<LogEntry> incomingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean renderScheduled = new AtomicBoolean(false);
    private boolean autoScroll = true;

    @Override
    public void createPartControl(Composite parent) {
        Display display = parent.getDisplay();
        initColors(display);

        parent.setLayout(new GridLayout(1, false));

        createFilterBar(parent);

        eu.kalafatic.tryst.ui.context.WorkbenchContextManager.getInstance().addContextListener(this);

        SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createLogTable(sashForm);
        createDetailPane(sashForm);

        sashForm.setWeights(70, 30);

        this.controller = new ConsoleOutputController(TrystUiServices.getWorkspaceService());
        this.controller.setLogEntryCallback(this::enqueueLogEntry);
        this.controller.setViewCallback(this::handleLegacyMessage);

        createActions();
        refreshAllLogs();
    }

    private void initColors(Display display) {
        errorColor = new Color(display, new RGB(220, 38, 38));   // Red
        warnColor = new Color(display, new RGB(217, 119, 6));    // Amber/Orange
        infoColor = new Color(display, new RGB(37, 99, 235));    // Blue
        debugColor = new Color(display, new RGB(100, 116, 139)); // Slate
        traceColor = new Color(display, new RGB(148, 163, 184)); // Muted
    }

    private void createFilterBar(Composite parent) {
        Composite filterBar = new Composite(parent, SWT.NONE);
        filterBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(7, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        filterBar.setLayout(layout);

        Label levelLbl = new Label(filterBar, SWT.NONE);
        levelLbl.setText("Severity:");

        levelCombo = new Combo(filterBar, SWT.READ_ONLY | SWT.BORDER);
        levelCombo.setItems("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        levelCombo.select(0); // ALL
        levelCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String selected = levelCombo.getText();
                if ("ALL".equalsIgnoreCase(selected)) {
                    controller.setFilterLevel(null);
                } else {
                    controller.setFilterLevel(LogLevel.fromString(selected));
                }
                refreshAllLogs();
            }
        });

        Label searchLbl = new Label(filterBar, SWT.NONE);
        searchLbl.setText(" Filter/Search:");

        searchBox = new Text(filterBar, SWT.BORDER | SWT.SEARCH);
        searchBox.setMessage("Search message, task ID, correlation ID...");
        searchBox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchBox.addModifyListener(e -> {
            controller.setSearchText(searchBox.getText());
            refreshAllLogs();
        });

        contextLabel = new Label(filterBar, SWT.BORDER);
        contextLabel.setText(" [Global Console] ");
        contextLabel.setToolTipText("Current Console Target Context");

        statusLabel = new Label(filterBar, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        statusLabel.setText("Streaming: Active | Entries: 0");
    }

    private void createLogTable(Composite parent) {
        logTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        logTable.setHeaderVisible(true);
        logTable.setLinesVisible(true);

        String[] headers = {"Time", "Level", "Category", "Task ID", "Message"};
        int[] widths = {100, 65, 120, 100, 500};

        for (int i = 0; i < headers.length; i++) {
            TableColumn col = new TableColumn(logTable, SWT.LEFT);
            col.setText(headers[i]);
            col.setWidth(widths[i]);
        }

        logTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateDetailPane();
            }
        });
    }

    private void createDetailPane(Composite parent) {
        detailText = new StyledText(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        detailText.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
    }

    private void enqueueLogEntry(LogEntry entry) {
        incomingQueue.add(entry);
        scheduleBatchRender();
    }

    private void handleLegacyMessage(ConsoleMessage msg) {
        LogLevel lvl = switch (msg.level().toUpperCase()) {
            case "WARN" -> LogLevel.WARN;
            case "ERROR" -> LogLevel.ERROR;
            default -> LogLevel.INFO;
        };
        LogEntry entry = LogEntry.create(
                System.currentTimeMillis(),
                msg.timestamp(),
                lvl,
                msg.source(),
                "eu.kalafatic.tryst.ui",
                Thread.currentThread().getName(),
                msg.message(),
                null,
                null,
                null
        );
        enqueueLogEntry(entry);
    }

    private void scheduleBatchRender() {
        if (logTable == null || logTable.isDisposed()) return;
        if (renderScheduled.compareAndSet(false, true)) {
            Display display = logTable.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    renderScheduled.set(false);
                    if (!logTable.isDisposed()) {
                        processIncomingLogs();
                    }
                });
            } else {
                renderScheduled.set(false);
            }
        }
    }

    private void processIncomingLogs() {
        if (logTable.isDisposed()) return;
        List<LogEntry> batch = new ArrayList<>();
        LogEntry item;
        while ((item = incomingQueue.poll()) != null) {
            if (controller.matchesFilter(item)) {
                batch.add(item);
            }
        }

        if (!batch.isEmpty()) {
            logTable.setRedraw(false);
            try {
                for (LogEntry entry : batch) {
                    addTableItem(entry);
                }
                while (logTable.getItemCount() > 2000) {
                    logTable.remove(0);
                }
            } finally {
                logTable.setRedraw(true);
            }

            if (autoScroll && logTable.getItemCount() > 0) {
                logTable.setTopIndex(logTable.getItemCount() - 1);
            }
        }

        updateStatusText();
    }

    private void addTableItem(LogEntry entry) {
        TableItem item = new TableItem(logTable, SWT.NONE);
        String timeStr = entry.timestamp() != null ? TIME_FORMAT.format(entry.timestamp()) : "";
        String levelStr = entry.level() != null ? entry.level().name() : "INFO";
        String catStr = entry.category() != null ? entry.category() : "";
        String taskStr = entry.taskId() != null ? entry.taskId() : "";
        String msgStr = entry.message() != null ? entry.message().replace("\n", " ") : "";

        item.setText(new String[]{timeStr, levelStr, catStr, taskStr, msgStr});
        item.setData(entry);

        if (entry.level() == LogLevel.ERROR) {
            item.setForeground(1, errorColor);
        } else if (entry.level() == LogLevel.WARN) {
            item.setForeground(1, warnColor);
        } else if (entry.level() == LogLevel.INFO) {
            item.setForeground(1, infoColor);
        } else if (entry.level() == LogLevel.DEBUG) {
            item.setForeground(1, debugColor);
        } else {
            item.setForeground(1, traceColor);
        }
    }

    private void refreshAllLogs() {
        if (logTable == null || logTable.isDisposed()) return;
        logTable.setRedraw(false);
        try {
            logTable.removeAll();
            List<LogEntry> entries = controller.getFilteredEntries();
            for (LogEntry entry : entries) {
                addTableItem(entry);
            }
        } finally {
            logTable.setRedraw(true);
        }
        if (autoScroll && logTable.getItemCount() > 0) {
            logTable.setTopIndex(logTable.getItemCount() - 1);
        }
        updateStatusText();
        updateDetailPane();
    }

    private void updateStatusText() {
        if (statusLabel != null && !statusLabel.isDisposed()) {
            String streamingState = controller.isPaused() ? "Paused" : "Streaming";
            statusLabel.setText(String.format("Status: %s | Displayed: %d | Total: %d",
                    streamingState, logTable.getItemCount(), controller.getTotalCapturedCount()));
        }
        if (contextLabel != null && !contextLabel.isDisposed()) {
            if (!controller.isFollowContext() || (controller.getContextTaskId() == null && controller.getContextSessionId() == null)) {
                contextLabel.setText(" [Global Console] ");
            } else if (controller.getContextTaskId() != null) {
                contextLabel.setText(" [Task: " + controller.getContextTaskId() + "] ");
            } else {
                contextLabel.setText(" [Session: " + controller.getContextSessionId() + "] ");
            }
        }
    }

    @Override
    public void onContextChanged(eu.kalafatic.tryst.ui.context.WorkbenchContext context) {
        if (controller == null) return;
        String tid = context.task() != null ? context.task().getId() : null;
        String sid = context.session() != null ? context.session().getId() : null;
        controller.setContextFilter(sid, tid);

        Display display = getSite().getShell().getDisplay();
        if (!display.isDisposed()) {
            display.asyncExec(this::refreshAllLogs);
        }
    }

    private void updateDetailPane() {
        if (detailText == null || detailText.isDisposed()) return;
        TableItem[] selection = logTable.getSelection();
        if (selection.length == 0 || !(selection[0].getData() instanceof LogEntry entry)) {
            detailText.setText("Select a log entry above to inspect full structured details and stack trace.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp      : ").append(entry.timestamp()).append("\n");
        sb.append("Severity       : ").append(entry.level()).append("\n");
        sb.append("Logger/Category: ").append(entry.category()).append("\n");
        sb.append("Bundle ID      : ").append(entry.bundleId()).append("\n");
        sb.append("Thread         : ").append(entry.threadName()).append("\n");
        sb.append("Correlation ID : ").append(entry.correlationId() != null ? entry.correlationId() : "N/A").append("\n");
        sb.append("Task ID        : ").append(entry.taskId() != null ? entry.taskId() : "N/A").append("\n");
        sb.append("Message        : ").append(entry.message()).append("\n");

        if (entry.formattedStackTrace() != null) {
            sb.append("\n--- Stack Trace ---\n").append(entry.formattedStackTrace());
        }

        detailText.setText(sb.toString());

        // Apply error highlight to detail text if ERROR level
        if (entry.level() == LogLevel.ERROR) {
            StyleRange sr = new StyleRange();
            sr.start = 0;
            sr.length = sb.indexOf("\n");
            sr.foreground = errorColor;
            sr.fontStyle = SWT.BOLD;
            detailText.setStyleRange(sr);
        }
    }

    private void createActions() {
        pauseResumeAction = new Action("Pause / Resume Streaming") {
            @Override
            public void run() {
                controller.togglePause();
                updateStatusText();
            }
        };

        clearAction = new Action("Clear Console Display") {
            @Override
            public void run() {
                controller.clearVisible();
                refreshAllLogs();
            }
        };

        copyAction = new Action("Copy Selected Message") {
            @Override
            public void run() {
                TableItem[] sel = logTable.getSelection();
                if (sel.length > 0 && sel[0].getData() instanceof LogEntry e) {
                    copyToClipboard(e.message());
                }
            }
        };

        copyDetailsAction = new Action("Copy Full Entry Details") {
            @Override
            public void run() {
                if (detailText != null && !detailText.isDisposed()) {
                    copyToClipboard(detailText.getText());
                }
            }
        };

        navigateTaskAction = new Action("Navigate to Related Task") {
            @Override
            public void run() {
                TableItem[] sel = logTable.getSelection();
                if (sel.length > 0 && sel[0].getData() instanceof LogEntry e && e.taskId() != null) {
                    navigateToTask(e.taskId());
                } else {
                    MessageDialog.openInformation(getSite().getShell(), "Task Navigation",
                            "The selected log entry is not associated with a specific Task ID.");
                }
            }
        };

        followContextAction = new Action("Follow Active Selection Context", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                controller.setFollowContext(isChecked());
                refreshAllLogs();
            }
        };
        followContextAction.setChecked(true);

        resetFilterAction = new Action("Show Global Logs") {
            @Override
            public void run() {
                controller.setFollowContext(false);
                followContextAction.setChecked(false);
                refreshAllLogs();
            }
        };

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(pauseResumeAction);
        toolbar.add(clearAction);
        toolbar.add(new Separator());
        toolbar.add(followContextAction);
        toolbar.add(resetFilterAction);
        toolbar.add(new Separator());
        toolbar.add(copyAction);
        toolbar.add(copyDetailsAction);
        toolbar.add(new Separator());
        toolbar.add(navigateTaskAction);

        org.eclipse.jface.action.MenuManager menuMgr = new org.eclipse.jface.action.MenuManager("#ConsolePopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(m -> {
            m.add(copyAction);
            m.add(copyDetailsAction);
            m.add(new Separator());
            m.add(navigateTaskAction);
            m.add(new Separator());
            m.add(pauseResumeAction);
            m.add(clearAction);
        });
        logTable.setMenu(menuMgr.createContextMenu(logTable));
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        Clipboard cb = new Clipboard(Display.getCurrent());
        try {
            TextTransfer textTransfer = TextTransfer.getInstance();
            cb.setContents(new Object[]{text}, new Transfer[]{textTransfer});
        } finally {
            cb.dispose();
        }
    }

    private void navigateToTask(String taskId) {
        try {
            org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
            org.eclipse.ui.IEditorPart editorPart = eu.kalafatic.tryst.ui.editor.TrystEditorManager.ensureEditorOpen(page);
            if (editorPart instanceof eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor editor) {
                editor.selectPage(eu.kalafatic.tryst.ui.editor.page.TaskExecutionPage.ID);
            }
        } catch (Exception e) {
            MessageDialog.openError(getSite().getShell(), "Navigation Error",
                    "Could not switch to task workspace editor: " + e.getMessage());
        }
    }

    @Override
    public void setFocus() {
        if (logTable != null && !logTable.isDisposed()) {
            logTable.setFocus();
        }
    }

    @Override
    public void dispose() {
        eu.kalafatic.tryst.ui.context.WorkbenchContextManager.getInstance().removeContextListener(this);
        if (controller != null) {
            controller.dispose();
        }
        if (errorColor != null) errorColor.dispose();
        if (warnColor != null) warnColor.dispose();
        if (infoColor != null) infoColor.dispose();
        if (debugColor != null) debugColor.dispose();
        if (traceColor != null) traceColor.dispose();
        super.dispose();
    }
}
