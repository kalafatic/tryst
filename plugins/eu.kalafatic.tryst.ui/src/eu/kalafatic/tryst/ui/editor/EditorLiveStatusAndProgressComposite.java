package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.core.lifecycle.ServerReadinessService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.service.ServerMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Reusable bottom live status and progress panel embedded in editor pages.
 * Contains two HTML-based panels:
 * 1. Current System State diagram (Server, Sessions, Project, Repo, Task, AI, Build/Test, Reviews)
 * 2. Progress Visualization graph (Task lifecycle, AI execution, build/test, and workflow progress)
 * Supports collapsing, expanding, and horizontal resizing.
 */
public class EditorLiveStatusAndProgressComposite extends Composite {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final String pageId;
    private final String pageTitle;
    private Composite headerBar;
    private SashForm sashForm;
    private Browser stateBrowser;
    private Browser progressBrowser;
    private Label fallbackStateLabel;
    private Label fallbackProgressLabel;
    private Button btnCollapseExpand;
    private boolean isCollapsed = false;
    private int[] lastWeights = new int[]{50, 50};

    private WorkbenchContext currentContext;

    public EditorLiveStatusAndProgressComposite(Composite parent, String pageId, String pageTitle) {
        super(parent, SWT.BORDER);
        this.pageId = pageId;
        this.pageTitle = pageTitle;
        initUI();
    }

    private void initUI() {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        // 1. Header Bar with Collapse/Expand and info
        headerBar = new Composite(this, SWT.NONE);
        headerBar.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 8;
        headerLayout.marginHeight = 3;
        headerBar.setLayout(headerLayout);
        headerBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        btnCollapseExpand = new Button(headerBar, SWT.PUSH | SWT.FLAT);
        btnCollapseExpand.setText("▾ Hide Status & Progress");
        btnCollapseExpand.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                toggleCollapse();
            }
        });

        Label lblTitle = new Label(headerBar, SWT.NONE);
        lblTitle.setText("Live System State & Workflow Progress  (" + pageTitle + ")");
        lblTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(headerBar, SWT.PUSH | SWT.FLAT);
        btnRefresh.setText("🔄 Refresh");
        btnRefresh.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                refresh();
            }
        });

        // 2. Horizontal SashForm with two HTML panels
        sashForm = new SashForm(this, SWT.HORIZONTAL);
        GridData sashData = new GridData(SWT.FILL, SWT.FILL, true, false);
        sashData.heightHint = 170;
        sashForm.setLayoutData(sashData);

        // Left Panel - Current System State Browser
        try {
            stateBrowser = new Browser(sashForm, SWT.NONE);
        } catch (Throwable t) {
            TrystLog.warn(EditorLiveStatusAndProgressComposite.class, "State Browser unavailable: " + t.getMessage());
            fallbackStateLabel = new Label(sashForm, SWT.WRAP | SWT.BORDER);
            fallbackStateLabel.setText("System State Panel");
        }

        // Right Panel - Progress Visualization Browser
        try {
            progressBrowser = new Browser(sashForm, SWT.NONE);
        } catch (Throwable t) {
            TrystLog.warn(EditorLiveStatusAndProgressComposite.class, "Progress Browser unavailable: " + t.getMessage());
            fallbackProgressLabel = new Label(sashForm, SWT.WRAP | SWT.BORDER);
            fallbackProgressLabel.setText("Progress Visualization Panel");
        }

        sashForm.setWeights(lastWeights);
    }

    public void toggleCollapse() {
        isCollapsed = !isCollapsed;
        GridData sashData = (GridData) sashForm.getLayoutData();
        if (isCollapsed) {
            sashForm.setVisible(false);
            sashData.exclude = true;
            btnCollapseExpand.setText("▸ Show Status & Progress");
        } else {
            sashForm.setVisible(true);
            sashData.exclude = false;
            btnCollapseExpand.setText("▾ Hide Status & Progress");
        }
        getParent().layout(true, true);
    }

    public void updateContext(WorkbenchContext context) {
        this.currentContext = context;
        refresh();
    }

    public void refresh() {
        if (isDisposed()) return;

        // Render Left Panel: Current System State
        String stateHtml = buildStateHtml();
        if (stateBrowser != null && !stateBrowser.isDisposed()) {
            stateBrowser.setText(stateHtml);
        } else if (fallbackStateLabel != null && !fallbackStateLabel.isDisposed()) {
            fallbackStateLabel.setText("System State: " + summarizeStateText());
        }

        // Render Right Panel: Progress Visualization
        String progressHtml = buildProgressHtml();
        if (progressBrowser != null && !progressBrowser.isDisposed()) {
            progressBrowser.setText(progressHtml);
        } else if (fallbackProgressLabel != null && !fallbackProgressLabel.isDisposed()) {
            fallbackProgressLabel.setText("Progress: " + summarizeProgressText());
        }
    }

    private String summarizeStateText() {
        return (currentContext != null && currentContext.projectName() != null) ?
                "Project: " + currentContext.projectName() : "Ready";
    }

    private String summarizeProgressText() {
        return (currentContext != null && currentContext.task() != null) ?
                "Task: " + currentContext.task().getStatus() : "Idle";
    }

    private String buildStateHtml() {
        String serverStatus = "RUNNING";
        String serverColor = "#16a34a"; // green
        var readiness = TrystUiServices.getServerReadinessService();
        if (readiness != null) {
            var rep = readiness.evaluateReadiness(eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage.WORKBENCH_RUNNING);
            if (!rep.ready()) {
                serverStatus = rep.degraded() ? "DEGRADED" : "INITIALIZING";
                serverColor = rep.degraded() ? "#d97706" : "#dc2626";
            }
        }

        String projectText = (currentContext != null && currentContext.projectName() != null) ?
                currentContext.projectName() : "None Selected";
        String projectColor = (currentContext != null && currentContext.project() != null) ? "#0284c7" : "#64748b";

        String repoText = (currentContext != null && currentContext.repositoryPath() != null) ?
                "Connected (" + (currentContext.branchName() != null ? currentContext.branchName() : "main") + ")" : "Not Connected";
        String repoColor = (currentContext != null && currentContext.repositoryPath() != null) ? "#16a34a" : "#64748b";

        String taskText = (currentContext != null && currentContext.task() != null) ?
                "[" + currentContext.task().getStatus() + "] " + currentContext.task().getTitle() : "No Active Task";
        String taskColor = "#64748b";
        if (currentContext != null && currentContext.task() != null) {
            taskColor = switch (currentContext.task().getStatus()) {
                case RUNNING, CLAIMED -> "#0284c7";
                case COMPLETED, APPROVED -> "#16a34a";
                case FAILED, REJECTED -> "#dc2626";
                case WAITING_FOR_REVIEW -> "#d97706";
                default -> "#64748b";
            };
        }

        String aiStatus = "Available";
        String aiColor = "#16a34a";
        var ai = TrystUiServices.getAiInferenceService();
        if (ai == null) {
            aiStatus = "Not Configured";
            aiColor = "#64748b";
        }

        String cpuLoad = "-";
        String heapUsage = "-";
        ServerMonitoringService mon = TrystUiServices.getServerMonitoringService();
        if (mon != null) {
            var m = mon.collectMetrics();
            cpuLoad = String.format("Sys: %.1f%%", m.systemCpuLoad());
            heapUsage = String.format("%d MB", m.heapUsedBytes() / (1024 * 1024));
        }

        String now = TIME_FMT.format(Instant.now());

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
              body { margin: 0; padding: 8px; font-family: "Segoe UI", Arial, sans-serif; font-size: 11px; background: #ffffff; color: #0f172a; overflow: hidden; }
              .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e2e8f0; padding-bottom: 4px; margin-bottom: 6px; font-weight: 600; color: #475569; }
              .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
              .node { border: 1px solid #e2e8f0; border-radius: 4px; padding: 5px 7px; background: #f8fafc; }
              .node-title { font-size: 10px; font-weight: 700; color: #64748b; text-transform: uppercase; margin-bottom: 2px; }
              .node-val { font-size: 11px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
              .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%%; margin-right: 4px; }
            </style>
            </head>
            <body>
              <div class="header">
                <span>SYSTEM STATE DIAGRAM</span>
                <span style="font-weight: normal; color: #94a3b8;">Updated: %s</span>
              </div>
              <div class="grid">
                <div class="node">
                  <div class="node-title">Server Runtime</div>
                  <div class="node-val"><span class="dot" style="background: %s;"></span>%s</div>
                </div>
                <div class="node">
                  <div class="node-title">Current Project</div>
                  <div class="node-val"><span class="dot" style="background: %s;"></span>%s</div>
                </div>
                <div class="node">
                  <div class="node-title">Repository / Git</div>
                  <div class="node-val"><span class="dot" style="background: %s;"></span>%s</div>
                </div>
                <div class="node">
                  <div class="node-title">Current Task</div>
                  <div class="node-val" title="%s"><span class="dot" style="background: %s;"></span>%s</div>
                </div>
                <div class="node">
                  <div class="node-title">AI Provider / Ollama</div>
                  <div class="node-val"><span class="dot" style="background: %s;"></span>%s</div>
                </div>
                <div class="node">
                  <div class="node-title">System Metrics</div>
                  <div class="node-val"><span class="dot" style="background: #0284c7;"></span>CPU: %s | Heap: %s</div>
                </div>
              </div>
            </body>
            </html>
            """.formatted(now, serverColor, serverStatus, projectColor, escapeHtml(projectText),
                repoColor, escapeHtml(repoText), escapeHtml(taskText), taskColor, escapeHtml(taskText),
                aiColor, aiStatus, cpuLoad, heapUsage);
    }

    private String buildProgressHtml() {
        AgentTask task = currentContext != null ? currentContext.task() : null;
        String now = TIME_FMT.format(Instant.now());

        if (task == null) {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                <style>
                  body { margin: 0; padding: 8px; font-family: "Segoe UI", Arial, sans-serif; font-size: 11px; background: #ffffff; color: #0f172a; overflow: hidden; }
                  .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e2e8f0; padding-bottom: 4px; margin-bottom: 10px; font-weight: 600; color: #475569; }
                  .empty-box { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 110px; color: #94a3b8; background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 4px; }
                </style>
                </head>
                <body>
                  <div class="header">
                    <span>PROGRESS VISUALIZATION</span>
                    <span style="font-weight: normal; color: #94a3b8;">Updated: %s</span>
                  </div>
                  <div class="empty-box">
                    <span style="font-size: 12px; font-weight: 600; margin-bottom: 4px;">No Active Workflow Operation</span>
                    <span>Select a task from the Navigator or execute a workflow to visualize live stage progression.</span>
                  </div>
                </body>
                </html>
                """.formatted(now);
        }

        // Ordered key stages
        String[] stageNames = new String[]{"QUEUED", "PLANNING", "IMPLEMENTING", "BUILDING", "TESTING", "REVIEW", "COMPLETED"};
        int activeIdx = getStageIndex(task.getStatus());
        int percentage = Math.min(100, Math.max(5, (activeIdx + 1) * 100 / stageNames.length));
        if (task.getStatus() == ExecutionStatus.COMPLETED) percentage = 100;

        StringBuilder stepsHtml = new StringBuilder();
        for (int i = 0; i < stageNames.length; i++) {
            String s = stageNames[i];
            String stepClass = "pending";
            if (task.getStatus() == ExecutionStatus.FAILED && i == activeIdx) {
                stepClass = "failed";
            } else if (i < activeIdx || task.getStatus() == ExecutionStatus.COMPLETED) {
                stepClass = "completed";
            } else if (i == activeIdx) {
                stepClass = "active";
            }

            stepsHtml.append("""
                <div class="step %s">
                  <div class="step-circle">%s</div>
                  <div class="step-name">%s</div>
                </div>
                """.formatted(stepClass, (i < activeIdx || task.getStatus() == ExecutionStatus.COMPLETED) ? "✓" : String.valueOf(i + 1), s));
            if (i < stageNames.length - 1) {
                String lineClass = (i < activeIdx) ? "line-completed" : "line-pending";
                stepsHtml.append("<div class=\"step-line %s\"></div>".formatted(lineClass));
            }
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
              body { margin: 0; padding: 8px; font-family: "Segoe UI", Arial, sans-serif; font-size: 11px; background: #ffffff; color: #0f172a; overflow: hidden; }
              .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #e2e8f0; padding-bottom: 4px; margin-bottom: 8px; font-weight: 600; color: #475569; }
              .progress-bar-bg { width: 100%%; height: 6px; background: #e2e8f0; border-radius: 3px; overflow: hidden; margin-bottom: 12px; }
              .progress-bar-fill { height: 100%%; background: #0284c7; width: %d%%; }
              .stepper { display: flex; align-items: center; justify-content: space-between; padding: 0 10px; }
              .step { display: flex; flex-direction: column; align-items: center; position: relative; z-index: 2; }
              .step-circle { width: 20px; height: 20px; border-radius: 50%%; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: bold; background: #ffffff; border: 2px solid #94a3b8; color: #64748b; }
              .step-name { font-size: 9px; font-weight: 600; margin-top: 4px; color: #64748b; text-transform: uppercase; }
              .step.active .step-circle { border-color: #0284c7; background: #e0f2fe; color: #0284c7; }
              .step.active .step-name { color: #0284c7; }
              .step.completed .step-circle { border-color: #16a34a; background: #16a34a; color: #ffffff; }
              .step.completed .step-name { color: #16a34a; }
              .step.failed .step-circle { border-color: #dc2626; background: #dc2626; color: #ffffff; }
              .step.failed .step-name { color: #dc2626; }
              .step-line { flex: 1; height: 2px; margin: 0 4px; margin-bottom: 14px; background: #cbd5e1; }
              .step-line.line-completed { background: #16a34a; }
            </style>
            </head>
            <body>
              <div class="header">
                <span>TASK PROGRESS: %s (%d%%)</span>
                <span style="font-weight: normal; color: #94a3b8;">Status: %s | Time: %s</span>
              </div>
              <div class="progress-bar-bg">
                <div class="progress-bar-fill"></div>
              </div>
              <div class="stepper">
                %s
              </div>
            </body>
            </html>
            """.formatted(escapeHtml(task.getTitle()), percentage, task.getStatus(), now, stepsHtml.toString());
    }

    private int getStageIndex(ExecutionStatus status) {
        if (status == null) return 0;
        return switch (status) {
            case IDLE, QUEUED, CLAIMED -> 0;
            case RUNNING -> 2;
            case WAITING_FOR_REVIEW, APPROVED, REJECTED, COMMITTING -> 5;
            case COMPLETED -> 6;
            case FAILED, CANCELLED, RECOVERY_REQUIRED -> 2;
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
