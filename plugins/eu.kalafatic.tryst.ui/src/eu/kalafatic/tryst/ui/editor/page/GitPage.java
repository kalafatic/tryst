package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import java.util.Collections;
import java.util.List;

/**
 * Editor page for Git VCS operations, branches, and repository statuses.
 */
public class GitPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.git";

    private Label lblRepoLocation;
    private Label lblBranch;
    private TableViewer branchViewer;
    private Project currentProject;

    public GitPage(TrystWorkbenchEditor editor) {
        super(ID, "Git", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group infoGroup = new Group(parent, SWT.NONE);
        infoGroup.setText("Repository Info");
        infoGroup.setLayout(new GridLayout(2, false));
        infoGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(infoGroup, "Repository Path:");
        lblRepoLocation = createValueLabel(infoGroup, "None");

        createRow(infoGroup, "Current Branch:");
        lblBranch = createValueLabel(infoGroup, "None");

        Group branchGroup = new Group(parent, SWT.NONE);
        branchGroup.setText("Git Branches");
        branchGroup.setLayout(new GridLayout(1, false));
        branchGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        branchViewer = new TableViewer(branchGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = branchViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Branch Name", 250);
        createColumn("Type", 120);

        branchViewer.setContentProvider(ArrayContentProvider.getInstance());
        branchViewer.setLabelProvider(new BranchLabelProvider());

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Git Status");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
    }

    private void createRow(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(branchViewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        this.currentProject = context.project();
        if (currentProject != null) {
            lblRepoLocation.setText(currentProject.getLocation() != null ? currentProject.getLocation().toString() : "N/A");
        } else if (context.repositoryPath() != null) {
            lblRepoLocation.setText(context.repositoryPath());
        } else {
            lblRepoLocation.setText("None");
        }
        if (context.branchName() != null) {
            lblBranch.setText(context.branchName());
        }
        refresh();
    }

    @Override
    public void refresh() {
        if (lblRepoLocation == null || lblRepoLocation.isDisposed()) return;
        if (currentProject == null) {
            WorkspaceService ws = TrystUiServices.getWorkspaceService();
            if (ws != null && ws.getCurrentWorkspace() != null) {
                currentProject = ws.getCurrentWorkspace().getActiveProject().orElse(null);
            }
        }

        if (currentProject != null && currentProject.getLocation() != null) {
            lblRepoLocation.setText(currentProject.getLocation().toString());
            GitService git = TrystUiServices.getGitService();
            if (git != null) {
                try {
                    var branchResult = git.listBranches(currentProject.getLocation());
                    List<String> branches = new java.util.ArrayList<>();
                    String currentBranch = "main";
                    if (branchResult != null && branchResult.output() != null) {
                        for (String line : branchResult.output().lines().toList()) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("* ")) {
                                currentBranch = trimmed.substring(2).trim();
                                branches.add(currentBranch);
                            } else if (!trimmed.isBlank()) {
                                branches.add(trimmed);
                            }
                        }
                    }
                    lblBranch.setText(currentBranch);
                    branchViewer.setInput(branches);
                } catch (Exception e) {
                    lblBranch.setText("Error reading branch");
                    branchViewer.setInput(Collections.emptyList());
                }
            } else {
                lblBranch.setText("GitService unavailable");
                branchViewer.setInput(Collections.emptyList());
            }
        } else {
            lblRepoLocation.setText("No active repository");
            lblBranch.setText("-");
            branchViewer.setInput(Collections.emptyList());
        }
    }

    private static class BranchLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof String name) {
                return switch (columnIndex) {
                    case 0 -> name;
                    case 1 -> name.startsWith("origin/") ? "Remote" : "Local";
                    default -> "";
                };
            }
            return "";
        }
    }
}
