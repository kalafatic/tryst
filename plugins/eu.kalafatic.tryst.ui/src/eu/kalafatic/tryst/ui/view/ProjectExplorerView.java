package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.application.launcher.TerminalLauncher;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.controller.ProjectExplorerController;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.part.ViewPart;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * View presenting project list in the left dock of TRYST.
 * Contains no business logic; delegates completely to ProjectExplorerController.
 *
 * <p>Context menu (Family UX standard v1.0): Refresh, Copy Path, Open Terminal for the
 * selected project location (workspace root fallback). "Open" is intentionally omitted:
 * this view lists projects only and has no file-open support.
 */
public class ProjectExplorerView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.projectExplorer";

    private ProjectExplorerController controller;
    private TableViewer viewer;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        viewer = new TableViewer(parent, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.getTable().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Project project) {
                    return (project.isActive() ? "★ " : "  ") + project.getName() + " (" + project.getLocation() + ")";
                }
                return super.getText(element);
            }
        });

        this.controller = new ProjectExplorerController(TrystUiServices.getWorkspaceService());
        this.controller.setViewCallback(() -> {
            Display display = viewer.getControl().getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!viewer.getControl().isDisposed()) {
                        viewer.setInput(controller.getProjects());
                        viewer.refresh();
                    }
                });
            }
        });

        viewer.addSelectionChangedListener(event -> {
            IStructuredSelection selection = viewer.getStructuredSelection();
            if (!selection.isEmpty() && selection.getFirstElement() instanceof Project project) {
                controller.handleProjectSelection(project);
            }
        });

        viewer.setInput(controller.getProjects());

        createActions();
        createContextMenu();
    }

    private void createActions() {
        Action addProjectAction = new Action("Add Project...") {
            @Override
            public void run() {
                InputDialog dialog = new InputDialog(
                        getSite().getShell(),
                        "Add Project",
                        "Enter project name:",
                        "DemoProject",
                        null
                );
                if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
                    String name = dialog.getValue().trim();
                    Path loc = Path.of(System.getProperty("user.home"), name);
                    controller.handleAddProject(name, loc);
                }
            }
        };

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(addProjectAction);

        IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        menu.add(addProjectAction);
    }

    private void createContextMenu() {
        MenuManager menuMgr = new MenuManager("#ProjectExplorerPopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                IStructuredSelection selection = viewer.getStructuredSelection();
                Project selectedProject = (!selection.isEmpty() && selection.getFirstElement() instanceof Project project)
                        ? project
                        : null;
                populateContextMenu(manager, selectedProject);
            }
        });
        Menu menu = menuMgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuMgr, viewer);
    }

    private void populateContextMenu(IMenuManager manager, Project selectedProject) {
        manager.add(new Action("Refresh") {
            @Override
            public void run() {
                if (controller != null && viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.setInput(controller.getProjects());
                    viewer.refresh();
                }
            }
        });

        if (selectedProject != null) {
            final Path location = selectedProject.getLocation();
            manager.add(new Action("Copy Path") {
                @Override
                public void run() {
                    if (location != null) {
                        copyToClipboard(location.toString());
                    }
                }
            });
            manager.add(new Action("Open Terminal") {
                @Override
                public void run() {
                    TerminalLauncher.launchWithUi(getSite().getShell(), resolveTerminalDirectory(location));
                }
            });
        } else {
            manager.add(new Separator());
            manager.add(new Action("Open Terminal") {
                @Override
                public void run() {
                    TerminalLauncher.launchWithUi(getSite().getShell(), resolveTerminalDirectory(null));
                }
            });
        }
    }

    /**
     * Resolves the directory the terminal should open in: the selected project location when
     * it exists, otherwise the current workspace root (user home fallback handled by the
     * shared {@link TerminalLauncher}).
     */
    private Path resolveTerminalDirectory(Path projectLocation) {
        if (projectLocation != null && Files.isDirectory(projectLocation)) {
            return projectLocation;
        }
        WorkspaceService workspaceService = TrystUiServices.getWorkspaceService();
        if (workspaceService != null) {
            try {
                var workspace = workspaceService.getCurrentWorkspace();
                if (workspace != null && workspace.getRootPath() != null && Files.isDirectory(workspace.getRootPath())) {
                    return workspace.getRootPath();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void copyToClipboard(String text) {
        if (text == null) {
            return;
        }
        Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay());
        try {
            clipboard.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
        }
        super.dispose();
    }
}
