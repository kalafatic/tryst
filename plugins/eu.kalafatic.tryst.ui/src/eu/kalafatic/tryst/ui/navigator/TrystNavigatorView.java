package eu.kalafatic.tryst.ui.navigator;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.service.WorkspaceChangeListener;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import eu.kalafatic.tryst.ui.editor.page.AiIntegrationPage;
import eu.kalafatic.tryst.ui.editor.page.BuildPage;
import eu.kalafatic.tryst.ui.editor.page.CodeReviewPage;
import eu.kalafatic.tryst.ui.editor.page.GitPage;
import eu.kalafatic.tryst.ui.editor.page.McpPage;
import eu.kalafatic.tryst.ui.editor.page.ProjectExplorerPage;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TRYST Navigator View (upper-left dock) providing hierarchical navigation across:
 * Projects, Git Repositories, Branches, Tasks, Workspaces, Review Sessions,
 * Builds/Tests, AI Executions, MCP Resources, and TEIP Integrations.
 * Enhanced with rich context menus for each node type.
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

        var bus = TrystUiServices.getEventBus();
        if (bus != null) {
            bus.subscribe(eu.kalafatic.tryst.core.event.ApplicationEvents.TaskStateChangedEvent.class, evt -> refreshAsync());
            bus.subscribe(eu.kalafatic.tryst.core.event.ApplicationEvents.SessionEvent.class, evt -> refreshAsync());
        }

        refreshAsync();
    }

    private void handleNodeSelection(NavigatorNode node) {
        WorkbenchContext.Builder builder = WorkbenchContextManager.getInstance().getContext().toBuilder();

        switch (node.getType()) {
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
            case TASK -> {
                if (node.getDomainObject() instanceof AgentTask task) {
                    builder.task(task);
                }
            }
            case WORKSPACE -> {
                if (node.getDomainObject() instanceof Workspace w) {
                    builder.workspace(w);
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
                    case PROJECT -> editor.selectPage(ProjectExplorerPage.ID);
                    case TASK -> editor.selectPage(TaskManagementPage.ID);
                    case GIT_REPO, GIT_BRANCH -> editor.selectPage(GitPage.ID);
                    case REVIEW_SESSION -> editor.selectPage(CodeReviewPage.ID);
                    case BUILD_RESULT -> editor.selectPage(BuildPage.ID);
                    case AI_EXECUTION -> editor.selectPage(AiIntegrationPage.ID);
                    case MCP_RESOURCE -> editor.selectPage(McpPage.ID);
                    case TEIP_INTEGRATION -> editor.selectPage(TeipIntegrationPage.ID);
                    case WORKSPACE -> editor.selectPage(TaskExecutionPage.ID);
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
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                IStructuredSelection sel = (IStructuredSelection) treeViewer.getSelection();
                if (!sel.isEmpty() && sel.getFirstElement() instanceof NavigatorNode node) {
                    populateContextMenuForNode(manager, node);
                } else {
                    manager.add(refreshAction);
                    manager.add(expandAllAction);
                    manager.add(collapseAllAction);
                }
            }
        });
        Menu menu = menuMgr.createContextMenu(treeViewer.getControl());
        treeViewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuMgr, treeViewer);
    }

    private void populateContextMenuForNode(IMenuManager manager, NavigatorNode node) {
        // Standard Actions
        manager.add(openInEditorAction);
        manager.add(new Action("Show Details") {
            @Override
            public void run() {
                showNodeDetails(node);
            }
        });

        manager.add(new Separator());

        // Context-sensitive actions based on Node Type
        switch (node.getType()) {
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
            case TASK -> {
                if (node.getDomainObject() instanceof AgentTask task) {
                    manager.add(new Action("Copy Task ID") {
                        @Override
                        public void run() {
                            copyToClipboard(task.getId());
                        }
                    });
                    if (task.getStatus() == ExecutionStatus.RUNNING || task.getStatus() == ExecutionStatus.CLAIMED) {
                        manager.add(new Action("Cancel Task Execution") {
                            @Override
                            public void run() {
                                boolean confirm = MessageDialog.openConfirm(getSite().getShell(), "Cancel Task", "Cancel execution of task: " + task.getTitle() + "?");
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

        WorkspaceService ws = TrystUiServices.getWorkspaceService();
        Workspace currentWs = ws != null ? ws.getCurrentWorkspace() : null;
        List<Project> projects = currentWs != null ? currentWs.getProjects() : Collections.emptyList();
        List<AgentTask> tasks = currentWs != null ? currentWs.getTasks() : Collections.emptyList();

        // 1. Projects & Git Root
        NavigatorNode projRoot = new NavigatorNode("root-projects", "Projects & Repositories",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> projChildren = new ArrayList<>();

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

        // 2. Tasks & Execution Workspaces
        NavigatorNode taskRoot = new NavigatorNode("root-tasks", "Tasks & Workspaces",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> taskChildren = new ArrayList<>();
        for (AgentTask t : tasks) {
            NavigatorNode tNode = new NavigatorNode(t.getId(), "[" + t.getStatus() + "] " + t.getTitle(),
                    NavigatorNode.NodeType.TASK, t, taskRoot);
            taskChildren.add(tNode);
        }
        taskRoot.setChildren(taskChildren);
        rootCategories.add(taskRoot);

        // 3. Review Sessions
        NavigatorNode reviewRoot = new NavigatorNode("root-reviews", "Review Sessions",
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

        // 4. Integrations: AI, MCP, TEIP
        NavigatorNode intRoot = new NavigatorNode("root-integrations", "Integrations & Extensions",
                NavigatorNode.NodeType.ROOT_CATEGORY, null, null);
        List<NavigatorNode> intChildren = new ArrayList<>();
        intChildren.add(new NavigatorNode("ai-models", "AI Provider / Ollama", NavigatorNode.NodeType.AI_EXECUTION, null, intRoot));
        intChildren.add(new NavigatorNode("mcp-tools", "MCP Scoped Tools", NavigatorNode.NodeType.MCP_RESOURCE, null, intRoot));
        intChildren.add(new NavigatorNode("teip-bridge", "TEIP 1.0 Evolution Bridge", NavigatorNode.NodeType.TEIP_INTEGRATION, null, intRoot));
        intRoot.setChildren(intChildren);
        rootCategories.add(intRoot);

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
