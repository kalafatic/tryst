package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.*;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.service.ProcessExecutionManager;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Native Eclipse RCP Comprehensive User Administration Detail View.
 * Provides granular, evidence-based inspection of an individual user across:
 * - User overview and metrics
 * - Associated projects and Git repositories
 * - Authentication sessions and application execution sessions
 * - Tasks and autonomous execution history
 * - OS-managed processes and AI agent assignments
 * - Code reviews and commit gating statuses
 * - Persistent chronological activity and audit history
 *
 * Enforces role-based administration boundaries and never exposes password hashes,
 * salts, or authentication tokens.
 */
public class UserDetailView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.userDetail";

    private String currentUserId;

    // Controls
    private Label statusLabel;
    private Text txtUserId;
    private Text txtUsername;
    private Text txtRole;
    private Text txtActive;
    private Text txtLocked;
    private Text txtFailedAttempts;
    private Text txtCreatedAt;
    private Text txtUpdatedAt;

    private Label lblProjectCount;
    private Label lblTaskCount;
    private Label lblSessionCount;
    private Label lblReviewCount;
    private Label lblAuditCount;

    private TableViewer projectViewer;
    private TableViewer authSessionViewer;
    private TableViewer appSessionViewer;
    private TableViewer taskViewer;
    private TableViewer processViewer;
    private TableViewer reviewViewer;
    private TableViewer auditViewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        // Header toolbar composite
        Composite headerComp = new Composite(parent, SWT.NONE);
        headerComp.setLayout(new GridLayout(3, false));
        headerComp.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblTitle = new Label(headerComp, SWT.NONE);
        lblTitle.setText("User Detail Administration:");
        lblTitle.setFont(parent.getDisplay().getSystemFont());

        statusLabel = new Label(headerComp, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("No user selected.");

        Button btnRefresh = new Button(headerComp, SWT.PUSH);
        btnRefresh.setText("Refresh Data");
        btnRefresh.addListener(SWT.Selection, e -> refreshCurrentUserData());

        // Tab folder for comprehensive user information
        CTabFolder tabFolder = new CTabFolder(parent, SWT.BORDER | SWT.FLAT);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tabFolder.setSimple(false);

        createOverviewTab(tabFolder);
        createProjectsTab(tabFolder);
        createSessionsTab(tabFolder);
        createTasksTab(tabFolder);
        createProcessesTab(tabFolder);
        createReviewsTab(tabFolder);
        createAuditTab(tabFolder);

        tabFolder.setSelection(0);

        createActions(parent);
    }

    private void createActions(Composite parent) {
        org.eclipse.jface.action.Action refreshAction = new org.eclipse.jface.action.Action("Refresh User Data") {
            @Override
            public void run() {
                refreshCurrentUserData();
            }
        };

        org.eclipse.jface.action.IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(refreshAction);

        org.eclipse.jface.action.IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(refreshAction);

        org.eclipse.jface.action.MenuManager menuMgr = new org.eclipse.jface.action.MenuManager("#UserDetailPopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(m -> {
            m.add(refreshAction);
            m.add(new org.eclipse.jface.action.Separator(org.eclipse.ui.IWorkbenchActionConstants.MB_ADDITIONS));
        });
        parent.setMenu(menuMgr.createContextMenu(parent));
    }

    private void createOverviewTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Overview && Identity");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(2, false));
        comp.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createFormRow(comp, "User ID:", txtUserId = createReadOnlyText(comp));
        createFormRow(comp, "Username:", txtUsername = createReadOnlyText(comp));
        createFormRow(comp, "Role (RBAC):", txtRole = createReadOnlyText(comp));
        createFormRow(comp, "Active Status:", txtActive = createReadOnlyText(comp));
        createFormRow(comp, "Account Locked:", txtLocked = createReadOnlyText(comp));
        createFormRow(comp, "Failed Login Attempts:", txtFailedAttempts = createReadOnlyText(comp));
        createFormRow(comp, "Account Created:", txtCreatedAt = createReadOnlyText(comp));
        createFormRow(comp, "Last Profile Update:", txtUpdatedAt = createReadOnlyText(comp));

        Group metricsGroup = new Group(comp, SWT.NONE);
        metricsGroup.setText("Authoritative Associated Resource Counts");
        GridData gdMetrics = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        metricsGroup.setLayoutData(gdMetrics);
        metricsGroup.setLayout(new GridLayout(2, false));

        lblProjectCount = createMetricLabel(metricsGroup, "Owned Projects: -");
        lblTaskCount = createMetricLabel(metricsGroup, "Associated Tasks: -");
        lblSessionCount = createMetricLabel(metricsGroup, "Active User Sessions: -");
        lblReviewCount = createMetricLabel(metricsGroup, "Code Reviews: -");
        lblAuditCount = createMetricLabel(metricsGroup, "Recorded Audit Events: -");

        item.setControl(comp);
    }

    private void createProjectsTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Projects && Repositories");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        projectViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = projectViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(projectViewer, "Project ID", 140);
        createColumn(projectViewer, "Name", 150);
        createColumn(projectViewer, "Location", 220);
        createColumn(projectViewer, "Status", 80);
        createColumn(projectViewer, "Active", 60);
        createColumn(projectViewer, "Created At", 160);

        projectViewer.setContentProvider(ArrayContentProvider.getInstance());
        projectViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof Project p) {
                    return switch (columnIndex) {
                        case 0 -> p.getId();
                        case 1 -> p.getName();
                        case 2 -> p.getLocation() != null ? p.getLocation().toString() : "";
                        case 3 -> p.getStatus() != null ? p.getStatus().name() : "";
                        case 4 -> String.valueOf(p.isActive());
                        case 5 -> p.getCreatedAt() != null ? p.getCreatedAt().toString() : "";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    private void createSessionsTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("User && Application Sessions");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        Label lblAuth = new Label(comp, SWT.NONE);
        lblAuth.setText("Authentication / Browser Sessions (Tokens Protected):");

        authSessionViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table tAuth = authSessionViewer.getTable();
        tAuth.setHeaderVisible(true);
        tAuth.setLinesVisible(true);
        GridData gdAuth = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdAuth.heightHint = 120;
        tAuth.setLayoutData(gdAuth);

        createColumn(authSessionViewer, "Session ID", 140);
        createColumn(authSessionViewer, "Client IP", 120);
        createColumn(authSessionViewer, "User Agent", 180);
        createColumn(authSessionViewer, "Revoked", 70);
        createColumn(authSessionViewer, "Expires At", 160);
        createColumn(authSessionViewer, "Last Activity", 160);

        authSessionViewer.setContentProvider(ArrayContentProvider.getInstance());
        authSessionViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof UserSession s) {
                    return switch (columnIndex) {
                        case 0 -> s.getId();
                        case 1 -> s.getClientIp() != null ? s.getClientIp() : "-";
                        case 2 -> s.getUserAgent() != null ? s.getUserAgent() : "-";
                        case 3 -> String.valueOf(s.isRevoked());
                        case 4 -> s.getExpiresAt() != null ? s.getExpiresAt().toString() : "";
                        case 5 -> s.getLastActivity() != null ? s.getLastActivity().toString() : "";
                        default -> "";
                    };
                }
                return "";
            }
        });

        Label lblApp = new Label(comp, SWT.NONE);
        lblApp.setText("Autonomous Agent Execution Sessions in User Projects:");

        appSessionViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table tApp = appSessionViewer.getTable();
        tApp.setHeaderVisible(true);
        tApp.setLinesVisible(true);
        GridData gdApp = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdApp.heightHint = 120;
        tApp.setLayoutData(gdApp);

        createColumn(appSessionViewer, "Execution Session ID", 140);
        createColumn(appSessionViewer, "Project ID", 140);
        createColumn(appSessionViewer, "Session Name", 160);
        createColumn(appSessionViewer, "Status", 90);
        createColumn(appSessionViewer, "Created At", 160);

        appSessionViewer.setContentProvider(ArrayContentProvider.getInstance());
        appSessionViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof Session s) {
                    return switch (columnIndex) {
                        case 0 -> s.getId();
                        case 1 -> s.getProjectId();
                        case 2 -> s.getName();
                        case 3 -> s.getStatus();
                        case 4 -> s.getCreatedAt() != null ? s.getCreatedAt().toString() : "";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    private void createTasksTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Tasks && Execution History");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        taskViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = taskViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(taskViewer, "Task ID", 140);
        createColumn(taskViewer, "Title", 180);
        createColumn(taskViewer, "Project ID", 120);
        createColumn(taskViewer, "Status", 100);
        createColumn(taskViewer, "Priority", 70);
        createColumn(taskViewer, "Agent Assigned", 110);
        createColumn(taskViewer, "Created At", 160);
        createColumn(taskViewer, "Completed At", 160);

        taskViewer.setContentProvider(ArrayContentProvider.getInstance());
        taskViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof AgentTask t) {
                    return switch (columnIndex) {
                        case 0 -> t.getId();
                        case 1 -> t.getTitle();
                        case 2 -> t.getProjectId() != null ? t.getProjectId() : "-";
                        case 3 -> t.getTaskStatus() != null ? t.getTaskStatus().name() : "";
                        case 4 -> t.getPriority() != null ? t.getPriority().name() : "";
                        case 5 -> t.getAgentId() != null ? t.getAgentId() : "Unassigned";
                        case 6 -> t.getCreatedAt() != null ? t.getCreatedAt().toString() : "";
                        case 7 -> t.getCompletedAt() != null ? t.getCompletedAt().toString() : "-";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    private void createProcessesTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Agent Processes");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        processViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = processViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(processViewer, "PID", 80);
        createColumn(processViewer, "Associated Task", 130);
        createColumn(processViewer, "Command Class", 180);
        createColumn(processViewer, "Working Dir", 200);
        createColumn(processViewer, "Status", 90);
        createColumn(processViewer, "Duration (ms)", 90);
        createColumn(processViewer, "Exit Code", 80);

        processViewer.setContentProvider(ArrayContentProvider.getInstance());
        processViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof ManagedProcessRecord p) {
                    return switch (columnIndex) {
                        case 0 -> String.valueOf(p.getPid());
                        case 1 -> p.getTaskId() != null ? p.getTaskId() : "-";
                        case 2 -> p.getCommandClass();
                        case 3 -> p.getWorkingDir();
                        case 4 -> p.getStatus();
                        case 5 -> String.valueOf(p.getDurationMs());
                        case 6 -> p.getExitCode() != null ? String.valueOf(p.getExitCode()) : "-";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    private void createReviewsTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Code Reviews");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        reviewViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = reviewViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(reviewViewer, "Review ID", 140);
        createColumn(reviewViewer, "Branch", 120);
        createColumn(reviewViewer, "Status", 90);
        createColumn(reviewViewer, "Approved By", 100);
        createColumn(reviewViewer, "Build Status", 90);
        createColumn(reviewViewer, "Commit Hash", 120);
        createColumn(reviewViewer, "Updated At", 160);

        reviewViewer.setContentProvider(ArrayContentProvider.getInstance());
        reviewViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof CodeReview r) {
                    return switch (columnIndex) {
                        case 0 -> r.getId();
                        case 1 -> r.getBranch();
                        case 2 -> r.getStatus() != null ? r.getStatus().name() : "";
                        case 3 -> r.getApprovedBy() != null ? r.getApprovedBy() : "-";
                        case 4 -> r.getBuildStatus() != null ? r.getBuildStatus() : "-";
                        case 5 -> r.getCommitHash() != null ? r.getCommitHash() : "-";
                        case 6 -> r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : "";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    private void createAuditTab(CTabFolder tabFolder) {
        CTabItem item = new CTabItem(tabFolder, SWT.NONE);
        item.setText("Audit && Activity Timeline");

        Composite comp = new Composite(tabFolder, SWT.NONE);
        comp.setLayout(new GridLayout(1, false));

        auditViewer = new TableViewer(comp, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = auditViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn(auditViewer, "Event Time", 160);
        createColumn(auditViewer, "Action", 140);
        createColumn(auditViewer, "Target Type", 100);
        createColumn(auditViewer, "Target ID", 120);
        createColumn(auditViewer, "Outcome", 80);
        createColumn(auditViewer, "Details", 240);

        auditViewer.setContentProvider(ArrayContentProvider.getInstance());
        auditViewer.setLabelProvider(new BaseTableLabelProvider() {
            @Override
            public String getColumnText(Object element, int columnIndex) {
                if (element instanceof AuditEvent a) {
                    return switch (columnIndex) {
                        case 0 -> a.eventTime() != null ? a.eventTime().toString() : "";
                        case 1 -> a.action();
                        case 2 -> a.targetType();
                        case 3 -> a.targetId() != null ? a.targetId() : "-";
                        case 4 -> a.outcome();
                        case 5 -> a.detailsJson() != null ? a.detailsJson() : "";
                        default -> "";
                    };
                }
                return "";
            }
        });

        item.setControl(comp);
    }

    public void displayUser(User user) {
        if (user == null) {
            clearDisplay();
            return;
        }
        this.currentUserId = user.getId();
        statusLabel.setText("Inspecting user: " + user.getUsername() + " (" + user.getRole().name() + ")");

        txtUserId.setText(user.getId());
        txtUsername.setText(user.getUsername());
        txtRole.setText(user.getRole().name());
        txtActive.setText(String.valueOf(user.isActive()));
        txtLocked.setText(String.valueOf(user.isLocked()));
        txtFailedAttempts.setText(String.valueOf(user.getFailedAttempts()));
        txtCreatedAt.setText(user.getCreatedAt().toString());
        txtUpdatedAt.setText(user.getUpdatedAt().toString());

        refreshCurrentUserData();
    }

    public void displayUserById(String userId) {
        UserRepository uRepo = TrystUiServices.getUserRepository();
        if (uRepo != null && userId != null) {
            Optional<User> uOpt = uRepo.findById(userId);
            if (uOpt.isPresent()) {
                displayUser(uOpt.get());
                return;
            }
        }
        this.currentUserId = userId;
        clearDisplay();
        statusLabel.setText("User ID " + userId + " not found.");
    }

    private void refreshCurrentUserData() {
        if (currentUserId == null || currentUserId.isBlank()) {
            return;
        }

        ProjectRepository pRepo = TrystUiServices.getProjectRepository();
        UserSessionRepository usRepo = TrystUiServices.getUserSessionRepository();
        SessionRepository sRepo = TrystUiServices.getSessionRepository();
        TaskRepository tRepo = TrystUiServices.getTaskRepository();
        CodeReviewRepository crRepo = TrystUiServices.getCodeReviewRepository();
        AuditEventRepository aRepo = TrystUiServices.getService(AuditEventRepository.class);
        ProcessExecutionManager procMgr = TrystUiServices.getProcessExecutionManager();

        // 1. Projects
        List<Project> ownedProjects = Collections.emptyList();
        if (pRepo != null) {
            ownedProjects = pRepo.findByOwnerId(currentUserId);
            projectViewer.setInput(ownedProjects);
        } else {
            projectViewer.setInput(Collections.emptyList());
        }
        lblProjectCount.setText("Owned Projects: " + ownedProjects.size());

        // 2. Auth Sessions
        List<UserSession> userSessions = Collections.emptyList();
        if (usRepo != null) {
            userSessions = usRepo.findByUserId(currentUserId);
            authSessionViewer.setInput(userSessions);
        } else {
            authSessionViewer.setInput(Collections.emptyList());
        }
        lblSessionCount.setText("User Sessions: " + userSessions.size());

        // 3. Application Execution Sessions across owned projects
        List<Session> appSessions = new ArrayList<>();
        if (sRepo != null) {
            for (Project p : ownedProjects) {
                appSessions.addAll(sRepo.findByProjectId(p.getId()));
            }
            appSessionViewer.setInput(appSessions);
        } else {
            appSessionViewer.setInput(Collections.emptyList());
        }

        // 4. Tasks across owned projects
        List<AgentTask> userTasks = new ArrayList<>();
        if (tRepo != null) {
            for (Project p : ownedProjects) {
                userTasks.addAll(tRepo.findByProjectId(p.getId()));
            }
            taskViewer.setInput(userTasks);
        } else {
            taskViewer.setInput(Collections.emptyList());
        }
        lblTaskCount.setText("Associated Tasks: " + userTasks.size());

        // 5. Managed Processes
        List<ManagedProcessRecord> userProcesses = new ArrayList<>();
        if (procMgr != null) {
            List<ManagedProcessRecord> allProcs = procMgr.getManagedProcesses();
            for (ManagedProcessRecord rec : allProcs) {
                if (rec.getTaskId() != null && userTasks.stream().anyMatch(t -> t.getId().equals(rec.getTaskId()))) {
                    userProcesses.add(rec);
                }
            }
            processViewer.setInput(userProcesses);
        } else {
            processViewer.setInput(Collections.emptyList());
        }

        // 6. Code Reviews across owned projects
        List<CodeReview> userReviews = new ArrayList<>();
        if (crRepo != null) {
            for (Project p : ownedProjects) {
                userReviews.addAll(crRepo.findByProjectId(p.getId()));
            }
            reviewViewer.setInput(userReviews);
        } else {
            reviewViewer.setInput(Collections.emptyList());
        }
        lblReviewCount.setText("Code Reviews: " + userReviews.size());

        // 7. Audit Events for this user
        List<AuditEvent> userAudits = Collections.emptyList();
        if (aRepo != null) {
            userAudits = aRepo.query(currentUserId, null, null, null, null, 100, 0);
            auditViewer.setInput(userAudits);
        } else {
            auditViewer.setInput(Collections.emptyList());
        }
        lblAuditCount.setText("Recorded Audit Events: " + userAudits.size());
    }

    private void clearDisplay() {
        this.currentUserId = null;
        statusLabel.setText("No user selected.");
        txtUserId.setText("");
        txtUsername.setText("");
        txtRole.setText("");
        txtActive.setText("");
        txtLocked.setText("");
        txtFailedAttempts.setText("");
        txtCreatedAt.setText("");
        txtUpdatedAt.setText("");

        lblProjectCount.setText("Owned Projects: -");
        lblTaskCount.setText("Associated Tasks: -");
        lblSessionCount.setText("Active User Sessions: -");
        lblReviewCount.setText("Code Reviews: -");
        lblAuditCount.setText("Recorded Audit Events: -");

        projectViewer.setInput(Collections.emptyList());
        authSessionViewer.setInput(Collections.emptyList());
        appSessionViewer.setInput(Collections.emptyList());
        taskViewer.setInput(Collections.emptyList());
        processViewer.setInput(Collections.emptyList());
        reviewViewer.setInput(Collections.emptyList());
        auditViewer.setInput(Collections.emptyList());
    }

    private void createFormRow(Composite parent, String labelText, Text field) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(labelText);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    private Text createReadOnlyText(Composite parent) {
        Text t = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return t;
    }

    private Label createMetricLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void createColumn(TableViewer viewer, String title, int width) {
        TableViewerColumn col = new TableViewerColumn(viewer, SWT.NONE);
        col.getColumn().setText(title);
        col.getColumn().setWidth(width);
        col.getColumn().setResizable(true);
        col.getColumn().setMoveable(true);
    }

    private abstract static class BaseTableLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }
    }

    @Override
    public void setFocus() {
        if (txtUsername != null && !txtUsername.isDisposed()) {
            txtUsername.setFocus();
        }
    }
}
