package eu.kalafatic.tryst.ui.navigator;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.AgentTask;
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
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
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
 */
public class TrystNavigatorView extends ViewPart implements WorkspaceChangeListener {

    public static final String ID = "eu.kalafatic.tryst.ui.navigator.view";

    private TreeViewer treeViewer;
    private Action refreshAction;
    private Action openInEditorAction;
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

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(refreshAction);
        toolbar.add(openInEditorAction);
    }

    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager("#NavigatorPopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                manager.add(openInEditorAction);
                manager.add(refreshAction);
            }
        });
        Menu menu = menuMgr.createContextMenu(treeViewer.getControl());
        treeViewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuMgr, treeViewer);
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
