package eu.kalafatic.tryst.ui.navigator;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.event.ApplicationEvents;
import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import eu.kalafatic.tryst.ui.editor.page.AiIntegrationPage;
import eu.kalafatic.tryst.ui.editor.page.BuildPage;
import eu.kalafatic.tryst.ui.editor.page.CodeReviewPage;
import eu.kalafatic.tryst.ui.editor.page.ConfigurationPage;
import eu.kalafatic.tryst.ui.editor.page.GitPage;
import eu.kalafatic.tryst.ui.editor.page.McpPage;
import eu.kalafatic.tryst.ui.editor.page.ProjectExplorerPage;
import eu.kalafatic.tryst.ui.editor.page.ServerMonitoringPage;
import eu.kalafatic.tryst.ui.editor.page.TaskExecutionPage;
import eu.kalafatic.tryst.ui.editor.page.TaskManagementPage;
import eu.kalafatic.tryst.ui.editor.page.TeipIntegrationPage;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.ViewPart;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TRYST Navigator View providing live, hierarchical tree navigation across:
 * - Servers (HTTP, TCP, WebSocket)
 * - Sessions (Active, Archived, with related Tasks & Executions)
 * - Tasks (Queued, Running, Completed, Failed, Cancelled)
 * - Projects & Git Repositories (branches, worktrees)
 * - Code Reviews (Pending, Approved, Rejected)
 * - Users & Access
 * - TEIP & EVO Integrations
 * - Database (SQLite Tables & Views)
 * - System Monitoring & Configuration
 *
 * Automatically updates when tasks are submitted or change state via EventBus.
 */
public class TrystNavigatorView extends ViewPart implements WorkspaceChangeListener {

    public static final String ID = "eu.kalafatic.tryst.ui.navigator.view";

    private TreeViewer treeViewer;
    private Action refreshAction;
    private Action openInEditorAction;
    private Action expandAllAction;
    private Action collapseAllAction;
    private final AtomicLong asyncRequestCounter = new AtomicLong(0);

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        treeViewer = new TreeViewer(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new NavigatorContentProvider());
        treeViewer.setLabelProvider(new NavigatorLabelProvider());

        treeViewer.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof NavigatorNode node) {
                handleNodeSelection(node);
            }
        });

        treeViewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof NavigatorNode node) {
                navigateNodeToEditor(node);
            }
        });

        createActions();
        createContextMenu();

        WorkspaceService ws = TrystUiServices.getWorkspaceService();
        if (ws != null) {
            ws.addChangeListener(this);
        }

        EventBus eventBus = TrystUiServices.getEventBus();
        if (eventBus != null) {
            eventBus.subscribe(ApplicationEvents.TaskStateChangedEvent.class, e -> refreshAsync());
            eventBus.subscribe(ApplicationEvents.SessionEvent.class, e -> refreshAsync());
            eventBus.subscribe(ApplicationEvents.AgentExecutionEvent.class, e -> {
                if (e.isComplete()) refreshAsync();
            });
        }

        refreshAsync();
    }

    private void handleNodeSelection(NavigatorNode node) {
        WorkbenchContext.Builder builder = WorkbenchContextManager.getInstance().getContext().toBuilder();

        switch (node.getType()) {
            case SESSION -> {
                if (node.getDomainObject() instanceof Session s) {
                    builder.session(s);
                }
            }
            case PROJECT -> {
                if (node.getDomainObject() instanceof Project p) {
                    builder.project(p);
                }
            }
            case GIT_REPO -> {
                if (node.getDomainObject() instanceof Project p && p.getLocation() != null) {
                    builder.repositoryPath(p.getLocation().toString());
                }
            }
            case GIT_BRANCH -> {
                if (node.getDomainObject() instanceof String branch) {
                    builder.branchName(branch);
                }
            }
            case TASK, TASK_RUNNING, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED -> {
                if (node.getDomainObject() instanceof AgentTask task) {
                    builder.task(task);
                } else if (node.getDomainObject() instanceof Task task) {
                    builder.task(task.toAgentTask());
                }
            }
            case WORKSPACE -> {
                if (node.getDomainObject() instanceof Workspace w) {
                    builder.workspace(w);
                }
            }
            case REVIEW_SESSION -> {
                if (node.getDomainObject() instanceof CodeReview r && r.getTaskId() != null) {
                    var taskRepo = TrystUiServices.getTaskRepository();
                    if (taskRepo != null) {
                        taskRepo.findById(r.getTaskId()).ifPresent(builder::task);
                    }
                }
            }
            default -> {}
        }

        WorkbenchContextManager.getInstance().setContext(builder.build());
    }

    private void navigateNodeToEditor(NavigatorNode node) {
        handleNodeSelection(node);
        try {
            IWorkbenchPage page = getSite().getPage();
            IEditorPart editorPart = page.getActiveEditor();
            if (editorPart instanceof TrystWorkbenchEditor editor) {
                switch (node.getType()) {
                    case SERVER -> editor.selectPage(ServerMonitoringPage.ID);
                    case SESSION -> editor.selectPage(TaskManagementPage.ID);
                    case TASK, TASK_RUNNING, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED -> {
                        if (!editor.selectPage("eu.kalafatic.tryst.ui.editor.page.taskDetails")) {
                            editor.selectPage(TaskExecutionPage.ID);
                        }
                    }
                    case PROJECT -> editor.selectPage(ProjectExplorerPage.ID);
                    case GIT_REPO, GIT_BRANCH -> editor.selectPage(GitPage.ID);
                    case REVIEW_SESSION -> editor.selectPage(CodeReviewPage.ID);
                    case BUILD_RESULT -> editor.selectPage(BuildPage.ID);
                    case AI_EXECUTION -> editor.selectPage(AiIntegrationPage.ID);
                    case MCP_RESOURCE -> editor.selectPage(McpPage.ID);
                    case TEIP_INTEGRATION -> editor.selectPage(TeipIntegrationPage.ID);
                    case WORKSPACE -> editor.selectPage(TaskExecutionPage.ID);
                    case DATABASE, TABLE -> editor.selectPage("eu.kalafatic.tryst.ui.editor.page.database");
                    case SYSTEM_ITEM -> editor.selectPage(ConfigurationPage.ID);
                    default -> {}
                }
            }
        } catch (Exception e) {
            TrystLog.warn(TrystNavigatorView.class, "Could not switch editor page on navigation: " + e.getMessage());
        }
    }

    private void createActions() {
        refreshAction = new Action("Refresh Navigator") {
            @Override
            public void run() {
                refreshAsync();
            }
        };

        openInEditorAction = new Action("Open in Editor") {
            @Override
            public void run() {
                IStructuredSelection sel = (IStructuredSelection) treeViewer.getSelection();
                if (!sel.isEmpty() && sel.getFirstElement() instanceof NavigatorNode node) {
                    navigateNodeToEditor(node);
                }
            }
        };

        expandAllAction = new Action("Expand All") {
            @Override
            public void run() {
                if (treeViewer != null && !treeViewer.getControl().isDisposed()) {
                    treeViewer.expandAll();
                }
            }
        };

        collapseAllAction = new Action("Collapse All") {
            @Override
            public void run() {
                if (treeViewer != null && !treeViewer.getControl().isDisposed()) {
                    treeViewer.collapseAll();
                }
            }
        };

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(refreshAction);
        toolbar.add(openInEditorAction);
        toolbar.add(expandAllAction);
        toolbar.add(collapseAllAction);
    }

    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager("#NavigatorPopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            IStructuredSelection sel = (IStructuredSelection) treeViewer.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof NavigatorNode node) {
                populateContextMenuForNode(manager, node);
            } else {
                manager.add(refreshAction);
                manager.add(expandAllAction);
                manager.add(collapseAllAction);
            }
        });
        Menu menu = menuMgr.createContextMenu(treeViewer.getControl());
        treeViewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuMgr, treeViewer);
    }

    private void populateContextMenuForNode(IMenuManager manager, NavigatorNode node) {
        manager.add(openInEditorAction);
        manager.add(new Action("Show Details") {
            @Override
            public void run() {
                showNodeDetails(node);
            }
        });

        manager.add(new Separator());

        switch (node.getType()) {
            case SESSION -> {
                if (node.getDomainObject() instanceof Session s) {
                    manager.add(new Action("Open Session") {
                        @Override
                        public void run() {
                            handleNodeSelection(node);
                            navigateNodeToEditor(node);
                        }
                    });
                    manager.add(new Action("Copy Session ID") {
                        @Override
                        public void run() {
                            copyToClipboard(s.getId());
                        }
                    });
                }
            }
            case PROJECT -> {
                if (node.getDomainObject() instanceof Project p) {
                    manager.add(new Action("Copy Project Path") {
                        @Override
                        public void run() {
                            if (p.getLocation() != null) copyToClipboard(p.getLocation().toString());
                        }
                    });
                    manager.add(new Action("Create Task for Project") {
                        @Override
                        public void run() {
                            handleNodeSelection(node);
                            navigateNodeToEditor(new NavigatorNode("task", "Tasks", NavigatorNode.NodeType.TASK, null, null));
                        }
                    });
                }
            }
            case GIT_REPO -> {
                if (node.getDomainObject() instanceof Project p && p.getLocation() != null) {
                    manager.add(new Action("Git Pull / Update") {
                        @Override
                        public void run() {
                            GitService git = TrystUiServices.getGitService();
                            if (git != null) {
                                try {
                                    var res = git.pull(p.getLocation(), "origin", "main", null);
                                    MessageDialog.openInformation(getSite().getShell(), "Git Pull",
                                            res.isSuccess() ? "Pulled successfully:\n" + res.output() : "Pull status:\n" + res.error());
                                    refreshAsync();
                                } catch (Exception ex) {
                                    MessageDialog.openError(getSite().getShell(), "Git Error", ex.getMessage());
                                }
                            }
                        }
                    });
                    manager.add(new Action("Copy Repository Path") {
                        @Override
                        public void run() {
                            copyToClipboard(p.getLocation().toString());
                        }
                    });
                }
            }
            case GIT_BRANCH -> {
                if (node.getDomainObject() instanceof String branch) {
                    manager.add(new Action("Copy Branch Name") {
                        @Override
                        public void run() {
                            copyToClipboard(branch);
                        }
                    });
                }
            }
            case TASK, TASK_RUNNING, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED -> {
                String taskId = null;
                boolean canCancel = false;
                if (node.getDomainObject() instanceof AgentTask task) {
                    taskId = task.getId();
                    canCancel = (task.getStatus() == ExecutionStatus.RUNNING || task.getStatus() == ExecutionStatus.CLAIMED);
                } else if (node.getDomainObject() instanceof Task task) {
                    taskId = task.getId();
                    canCancel = task.getStatus().isActive();
                }

                if (taskId != null) {
                    final String tid = taskId;
                    manager.add(new Action("Open Task Details") {
                        @Override
                        public void run() {
                            handleNodeSelection(node);
                            navigateNodeToEditor(node);
                        }
                    });
                    manager.add(new Action("Copy Task ID") {
                        @Override
                        public void run() {
                            copyToClipboard(tid);
                        }
                    });
                    if (canCancel) {
                        manager.add(new Action("Cancel Task Execution") {
                            @Override
                            public void run() {
                                boolean confirm = MessageDialog.openConfirm(getSite().getShell(), "Cancel Task", "Cancel execution of task: " + node.getName() + "?");
                                if (confirm) {
                                    var orch = TrystUiServices.getSessionTaskOrchestrator();
                                    if (orch != null) orch.cancelCurrentExecution();
                                    refreshAsync();
                                }
                            }
                        });
                    }
                }
            }
            case REVIEW_SESSION -> {
                if (node.getDomainObject() instanceof CodeReview review) {
                    manager.add(new Action("Open Review") {
                        @Override
                        public void run() {
                            handleNodeSelection(node);
                            navigateNodeToEditor(node);
                        }
                    });
                    manager.add(new Action("Copy Review ID") {
                        @Override
                        public void run() {
                            copyToClipboard(review.getId());
                        }
                    });
                }
            }
            case WORKSPACE -> {
                if (node.getDomainObject() instanceof Workspace w) {
                    manager.add(new Action("Copy Workspace ID") {
                        @Override
                        public void run() {
                            copyToClipboard(w.getId());
                        }
                    });
                }
            }
            case TABLE -> {
                if (node.getDomainObject() instanceof String tableName) {
                    manager.add(new Action("Open Table in Database Viewer") {
                        @Override
                        public void run() {
                            navigateNodeToEditor(node);
                        }
                    });
                    manager.add(new Action("Copy Table Name") {
                        @Override
                        public void run() {
                            copyToClipboard(tableName);
                        }
                    });
                }
            }
            default -> {}
        }

        manager.add(new Separator());
        manager.add(refreshAction);
        manager.add(expandAllAction);
        manager.add(collapseAllAction);
    }

    private void showNodeDetails(NavigatorNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(node.getType()).append("\n");
        sb.append("Name: ").append(node.getName()).append("\n");
        sb.append("ID: ").append(node.getId()).append("\n");
        if (node.getDomainObject() != null) {
            sb.append("Domain Object: ").append(node.getDomainObject().getClass().getSimpleName()).append("\n");
            sb.append("Details: ").append(node.getDomainObject()).append("\n");
        }
        MessageDialog.openInformation(getSite().getShell(), "Node Details - " + node.getName(), sb.toString());
    }

    private void copyToClipboard(String text) {
        if (text == null) return;
        Clipboard cb = new Clipboard(getSite().getShell().getDisplay());
        try {
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    public void refreshAsync() {
        long currentRequestId = asyncRequestCounter.incrementAndGet();
        AsyncExecutionService async = TrystUiServices.getAsyncExecutionService();

        Runnable task = () -> {
            List<NavigatorNode> roots = buildTreeModel();
            if (currentRequestId != asyncRequestCounter.get()) {
                return;
            }

            Display display = treeViewer != null && !treeViewer.getControl().isDisposed() ?
                    treeViewer.getControl().getDisplay() : null;

            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> {
                    if (currentRequestId == asyncRequestCounter.get() && treeViewer != null && !treeViewer.getControl().isDisposed()) {
                        treeViewer.setInput(roots);
                        treeViewer.expandToLevel(2);
                    }
                });
            }
        };

        if (async != null) {
            async.runAsync(task);
        } else {
            Thread.ofVirtual().start(task);
        }
    }

    public List<NavigatorNode> buildTreeModel() {
        List<NavigatorNode> rootCategories = new ArrayList<>();

        // 1. Servers (HTTP, API, TCP, WebSocket)
        NavigatorNode serverRoot = new NavigatorNode("root-servers", "Servers & Protocols",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> serverNodes = new ArrayList<>();
        var httpService = TrystUiServices.getHttpService();
        String httpStatus = (httpService != null && httpService.isRunning()) ? "Online (Port " + httpService.getPort() + ")" : "Offline";
        serverNodes.add(new NavigatorNode("srv-http", "HTTP Server - " + httpStatus, NavigatorNode.NodeType.SERVER, httpService, serverRoot));

        var tcpService = TrystUiServices.getTcpService();
        String tcpStatus = (tcpService != null && tcpService.isRunning()) ? "Online (Port " + tcpService.getPort() + ")" : "Offline";
        serverNodes.add(new NavigatorNode("srv-tcp", "TCP Server - " + tcpStatus, NavigatorNode.NodeType.SERVER, tcpService, serverRoot));
        serverNodes.add(new NavigatorNode("srv-ws", "WebSocket Server - Standby", NavigatorNode.NodeType.SERVER, null, serverRoot));
        serverRoot.setChildren(serverNodes);
        rootCategories.add(serverRoot);

        // 2. Sessions (Active & Archived)
        NavigatorNode sessionRoot = new NavigatorNode("root-sessions", "Sessions",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> sessionNodes = new ArrayList<>();
        var sessionRepo = TrystUiServices.getSessionRepository();
        var taskRepo = TrystUiServices.getTaskRepository();
        if (sessionRepo != null) {
            List<Session> allSessions = sessionRepo.findAll();
            for (Session s : allSessions) {
                NavigatorNode sNode = new NavigatorNode("sess-" + s.getId(), s.getName() + " (" + s.getStatus() + ")",
                        NavigatorNode.NodeType.SESSION, s, sessionRoot);
                List<NavigatorNode> sChildren = new ArrayList<>();
                if (taskRepo != null) {
                    List<AgentTask> sTasks = taskRepo.findBySessionId(s.getId());
                    if (!sTasks.isEmpty()) {
                        NavigatorNode sTasksNode = new NavigatorNode("sess-" + s.getId() + "-tasks", "Tasks (" + sTasks.size() + ")",
                                NavigatorNode.NodeType.TASK_QUEUE, null, sNode);
                        List<NavigatorNode> taskItemNodes = new ArrayList<>();
                        for (AgentTask t : sTasks) {
                            taskItemNodes.add(new NavigatorNode(t.getId(), "[" + t.getStatus() + "] " + t.getTitle(),
                                    NavigatorNode.NodeType.TASK, t, sTasksNode));
                        }
                        sTasksNode.setChildren(taskItemNodes);
                        sChildren.add(sTasksNode);
                    }
                }
                sNode.setChildren(sChildren);
                sessionNodes.add(sNode);
            }
        }
        sessionRoot.setChildren(sessionNodes);
        rootCategories.add(sessionRoot);

        // 3. Tasks partitioned by lifecycle status
        NavigatorNode taskPartitionRoot = new NavigatorNode("root-tasks-lifecycle", "Tasks by Status",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> taskPartitionNodes = new ArrayList<>();
        List<AgentTask> allTasks = taskRepo != null ? taskRepo.findAll() : Collections.emptyList();

        List<NavigatorNode> queuedTasks = new ArrayList<>();
        List<NavigatorNode> runningTasks = new ArrayList<>();
        List<NavigatorNode> completedTasks = new ArrayList<>();
        List<NavigatorNode> failedTasks = new ArrayList<>();
        List<NavigatorNode> cancelledTasks = new ArrayList<>();

        for (AgentTask t : allTasks) {
            String label = "[" + t.getStatus() + "] " + t.getTitle();
            ExecutionStatus st = t.getStatus();
            if (st == ExecutionStatus.COMPLETED) {
                completedTasks.add(new NavigatorNode(t.getId(), label, NavigatorNode.NodeType.TASK_COMPLETED, t, null));
            } else if (st == ExecutionStatus.FAILED) {
                failedTasks.add(new NavigatorNode(t.getId(), label, NavigatorNode.NodeType.TASK_FAILED, t, null));
            } else if (st == ExecutionStatus.CANCELLED) {
                cancelledTasks.add(new NavigatorNode(t.getId(), label, NavigatorNode.NodeType.TASK_CANCELLED, t, null));
            } else if (st == ExecutionStatus.RUNNING || st == ExecutionStatus.CLAIMED) {
                runningTasks.add(new NavigatorNode(t.getId(), label, NavigatorNode.NodeType.TASK_RUNNING, t, null));
            } else {
                queuedTasks.add(new NavigatorNode(t.getId(), label, NavigatorNode.NodeType.TASK_QUEUE, t, null));
            }
        }

        NavigatorNode queuedNode = new NavigatorNode("grp-queued", "Queued (" + queuedTasks.size() + ")", NavigatorNode.NodeType.TASK_QUEUE, null, taskPartitionRoot);
        queuedNode.setChildren(queuedTasks);
        taskPartitionNodes.add(queuedNode);

        NavigatorNode runningNode = new NavigatorNode("grp-running", "Running (" + runningTasks.size() + ")", NavigatorNode.NodeType.TASK_RUNNING, null, taskPartitionRoot);
        runningNode.setChildren(runningTasks);
        taskPartitionNodes.add(runningNode);

        NavigatorNode completedNode = new NavigatorNode("grp-completed", "Completed (" + completedTasks.size() + ")", NavigatorNode.NodeType.TASK_COMPLETED, null, taskPartitionRoot);
        completedNode.setChildren(completedTasks);
        taskPartitionNodes.add(completedNode);

        NavigatorNode failedNode = new NavigatorNode("grp-failed", "Failed (" + failedTasks.size() + ")", NavigatorNode.NodeType.TASK_FAILED, null, taskPartitionRoot);
        failedNode.setChildren(failedTasks);
        taskPartitionNodes.add(failedNode);

        NavigatorNode cancelledNode = new NavigatorNode("grp-cancelled", "Cancelled (" + cancelledTasks.size() + ")", NavigatorNode.NodeType.TASK_CANCELLED, null, taskPartitionRoot);
        cancelledNode.setChildren(cancelledTasks);
        taskPartitionNodes.add(cancelledNode);

        taskPartitionRoot.setChildren(taskPartitionNodes);
        rootCategories.add(taskPartitionRoot);

        // 4. Projects & Git Repositories
        NavigatorNode projRoot = new NavigatorNode("root-projects", "Projects & Repositories",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> projChildren = new ArrayList<>();
        var projectRepo = TrystUiServices.getProjectRepository();
        List<Project> projects = projectRepo != null ? projectRepo.findAll() : Collections.emptyList();
        GitService git = TrystUiServices.getGitService();

        for (Project p : projects) {
            NavigatorNode pNode = new NavigatorNode(p.getId(), p.getName(),
                    NavigatorNode.NodeType.PROJECT, p, projRoot);
            List<NavigatorNode> pChildren = new ArrayList<>();
            if (p.getLocation() != null) {
                NavigatorNode repoNode = new NavigatorNode(p.getId() + "-repo", "Repository (" + p.getLocation().getFileName() + ")",
                        NavigatorNode.NodeType.GIT_REPO, p, pNode);
                List<NavigatorNode> branchNodes = new ArrayList<>();
                if (git != null) {
                    try {
                        var branchRes = git.listBranches(p.getLocation());
                        if (branchRes != null && branchRes.output() != null) {
                            for (String b : branchRes.output().lines().toList()) {
                                String clean = b.replace("*", "").trim();
                                if (!clean.isBlank()) {
                                    branchNodes.add(new NavigatorNode(p.getId() + "-b-" + clean, clean,
                                            NavigatorNode.NodeType.GIT_BRANCH, clean, repoNode));
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                repoNode.setChildren(branchNodes);
                pChildren.add(repoNode);
            }
            pNode.setChildren(pChildren);
            projChildren.add(pNode);
        }
        projRoot.setChildren(projChildren);
        rootCategories.add(projRoot);

        // 5. Code Reviews
        NavigatorNode reviewRoot = new NavigatorNode("root-reviews", "Code Reviews",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        var reviewRepo = TrystUiServices.getCodeReviewRepository();
        List<NavigatorNode> reviewChildren = new ArrayList<>();
        if (reviewRepo != null) {
            for (CodeReview r : reviewRepo.findAll()) {
                reviewChildren.add(new NavigatorNode(r.getId(), "Review " + r.getId().substring(0, Math.min(8, r.getId().length())) + " (" + r.getStatus() + ")",
                        NavigatorNode.NodeType.REVIEW_SESSION, r, reviewRoot));
            }
        }
        reviewRoot.setChildren(reviewChildren);
        rootCategories.add(reviewRoot);

        // 6. Users
        NavigatorNode userRoot = new NavigatorNode("root-users", "Users & Access",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        var userRepo = TrystUiServices.getUserRepository();
        List<NavigatorNode> userChildren = new ArrayList<>();
        if (userRepo != null) {
            for (User u : userRepo.findAll()) {
                userChildren.add(new NavigatorNode("u-" + u.getId(), u.getUsername() + " (" + u.getRole() + ")",
                        NavigatorNode.NodeType.USER, u, userRoot));
            }
        }
        userRoot.setChildren(userChildren);
        rootCategories.add(userRoot);

        // 7. TEIP & AI Integrations
        NavigatorNode intRoot = new NavigatorNode("root-integrations", "TEIP & AI Integrations",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> intChildren = new ArrayList<>();
        intChildren.add(new NavigatorNode("teip-bridge", "TEIP 1.0 Evolution Bridge", NavigatorNode.NodeType.TEIP_INTEGRATION, null, intRoot));
        intChildren.add(new NavigatorNode("ai-models", "Ollama & AI Providers", NavigatorNode.NodeType.AI_EXECUTION, null, intRoot));
        intChildren.add(new NavigatorNode("mcp-tools", "MCP Scoped Tools", NavigatorNode.NodeType.MCP_RESOURCE, null, intRoot));
        intRoot.setChildren(intChildren);
        rootCategories.add(intRoot);

        // 8. Database (SQLite Schemas & Tables)
        NavigatorNode dbRoot = new NavigatorNode("root-database", "Database (SQLite)",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> tableNodes = new ArrayList<>();
        SqliteDatabaseManager dbManager = TrystUiServices.getDatabaseManager();
        if (dbManager != null) {
            try {
                Connection conn = dbManager.getConnection();
                if (conn != null && !conn.isClosed()) {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")) {
                        while (rs.next()) {
                            String tbl = rs.getString("name");
                            tableNodes.add(new NavigatorNode("tbl-" + tbl, tbl, NavigatorNode.NodeType.TABLE, tbl, dbRoot));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        dbRoot.setChildren(tableNodes);
        rootCategories.add(dbRoot);

        // 9. System
        NavigatorNode sysRoot = new NavigatorNode("root-system", "System",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> sysNodes = new ArrayList<>();
        sysNodes.add(new NavigatorNode("sys-mon", "Server Monitoring & Metrics", NavigatorNode.NodeType.SYSTEM_ITEM, null, sysRoot));
        sysNodes.add(new NavigatorNode("sys-cfg", "Configuration", NavigatorNode.NodeType.SYSTEM_ITEM, null, sysRoot));
        sysRoot.setChildren(sysNodes);
        rootCategories.add(sysRoot);

        return rootCategories;
    }

    @Override
    public void onWorkspaceChanged(Workspace workspace) {
        refreshAsync();
    }

    @Override
    public void onProjectAdded(Project project) {
        refreshAsync();
    }

    @Override
    public void onProjectSelected(Project project) {
        refreshAsync();
    }

    @Override
    public void onTaskAdded(AgentTask task) {
        refreshAsync();
    }

    @Override
    public void onTaskUpdated(AgentTask task) {
        refreshAsync();
    }

    @Override
    public void setFocus() {
        if (treeViewer != null && !treeViewer.getControl().isDisposed()) {
            treeViewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        WorkspaceService ws = TrystUiServices.getWorkspaceService();
        if (ws != null) {
            ws.removeChangeListener(this);
        }
        super.dispose();
    }
}
