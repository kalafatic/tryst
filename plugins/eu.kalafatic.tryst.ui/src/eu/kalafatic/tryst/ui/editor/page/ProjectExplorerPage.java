package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import eu.kalafatic.tryst.ui.controller.ProjectExplorerController;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import java.nio.file.Path;

/**
 * Editor page providing Project Explorer management within the central workspace.
 */
public class ProjectExplorerPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.projectExplorer";

    private ProjectExplorerController controller;
    private TableViewer viewer;

    public ProjectExplorerPage(TrystWorkbenchEditor editor) {
        super(ID, "Project Explorer", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Project Name", 200);
        createColumn("Location", 300);
        createColumn("Status", 100);
        createColumn("Active", 80);

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setLabelProvider(new ProjectLabelProvider());

        Composite btnBar = new Composite(parent, SWT.NONE);
        btnBar.setLayout(new GridLayout(2, false));

        Button btnAdd = new Button(btnBar, SWT.PUSH);
        btnAdd.setText("Add Project...");
        btnAdd.addListener(SWT.Selection, e -> onAddProject());

        Button btnRefresh = new Button(btnBar, SWT.PUSH);
        btnRefresh.setText("Refresh");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

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
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof Project project) {
                controller.handleProjectSelection(project);
                WorkbenchContextManager.getInstance().setContext(
                        WorkbenchContextManager.getInstance().getContext().toBuilder()
                                .project(project)
                                .build()
                );
            }
        });

        viewer.setInput(controller.getProjects());
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(viewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    private void onAddProject() {
        InputDialog dialog = new InputDialog(
                getControl().getShell(),
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

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void refresh() {
        if (viewer != null && !viewer.getControl().isDisposed() && controller != null) {
            viewer.setInput(controller.getProjects());
            viewer.refresh();
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
            controller = null;
        }
        super.dispose();
    }

    private static class ProjectLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof Project p) {
                return switch (columnIndex) {
                    case 0 -> p.getName();
                    case 1 -> p.getLocation() != null ? p.getLocation().toString() : "";
                    case 2 -> p.getStatus() != null ? p.getStatus().name() : "";
                    case 3 -> p.isActive() ? "★ Active" : "";
                    default -> "";
                };
            }
            return "";
        }
    }
}
