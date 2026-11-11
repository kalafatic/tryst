package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Editor page for Automated Tests, Quality Reports, and Execution Results.
 * Enhanced with filter search, copy output summary, show all results, and action toolbar.
 */
public class TestQualityPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.testQuality";

    private TableViewer resultsViewer;
    private Label statusLabel;
    private String filterTaskId;
    private Text searchFilter;
    private Button btnCopyResult;
    private Button btnShowAll;
    private boolean showAll = false;

    public TestQualityPage(TrystWorkbenchEditor editor) {
        super(ID, "Tests & Quality", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(6, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Quality Reports");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        btnShowAll = new Button(toolbar, SWT.PUSH);
        btnShowAll.setText("📋 Show All Task Results");
        btnShowAll.addListener(SWT.Selection, e -> {
            showAll = !showAll;
            btnShowAll.setText(showAll ? "🎯 Filter to Active Task" : "📋 Show All Task Results");
            refresh();
        });

        btnCopyResult = new Button(toolbar, SWT.PUSH);
        btnCopyResult.setText("📋 Copy Summary");
        btnCopyResult.setEnabled(false);
        btnCopyResult.addListener(SWT.Selection, e -> copySelectedResult());

        Button btnBuildPage = new Button(toolbar, SWT.PUSH);
        btnBuildPage.setText("⚡ Open Build Page");
        btnBuildPage.addListener(SWT.Selection, e -> editor.selectPage(BuildPage.ID));

        Label lblSearch = new Label(toolbar, SWT.NONE);
        lblSearch.setText("  🔍 Filter:");

        searchFilter = new Text(toolbar, SWT.BORDER | SWT.SEARCH);
        searchFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchFilter.setMessage("Filter results by ID, task or output...");
        searchFilter.addModifyListener(e -> applyFilter());

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Execution Results & Test Runs:");

        resultsViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = resultsViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Execution ID", 120);
        createColumn("Task ID", 120);
        createColumn("Success", 80);
        createColumn("Output Summary", 380);
        createColumn("Executed At", 180);

        resultsViewer.setContentProvider(ArrayContentProvider.getInstance());
        resultsViewer.setLabelProvider(new ResultLabelProvider());

        resultsViewer.addSelectionChangedListener(e -> {
            btnCopyResult.setEnabled(!e.getSelection().isEmpty());
        });

        refresh();
    }

    private void applyFilter() {
        String query = searchFilter.getText().trim().toLowerCase();
        if (query.isEmpty()) {
            resultsViewer.setFilters(new ViewerFilter[0]);
        } else {
            resultsViewer.setFilters(new ViewerFilter() {
                @Override
                public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element) {
                    if (element instanceof ExecutionResult r) {
                        return (r.getId() != null && r.getId().toLowerCase().contains(query))
                                || (r.getTaskId() != null && r.getTaskId().toLowerCase().contains(query))
                                || (r.getOutput() != null && r.getOutput().toLowerCase().contains(query));
                    }
                    return true;
                }
            });
        }
    }

    private void copySelectedResult() {
        IStructuredSelection sel = (IStructuredSelection) resultsViewer.getSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof ExecutionResult r) {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                String text = "Execution Result: " + r.getId() + "\nTask: " + r.getTaskId() + "\nSuccess: " + r.isSuccess() + "\nOutput:\n" + r.getOutput();
                cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        }
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(resultsViewer.getTable(), SWT.LEFT);
        col.setText(title);
        col.setWidth(width);
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        super.onContextChanged(context);
        this.filterTaskId = context.taskId();
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (resultsViewer == null || resultsViewer.getControl().isDisposed()) return;
        ExecutionResultRepository repo = TrystUiServices.getExecutionResultRepository();
        if (repo != null) {
            List<ExecutionResult> results = new ArrayList<>();
            if (!showAll && filterTaskId != null && !filterTaskId.isBlank()) {
                results = repo.findByTaskId(filterTaskId);
                statusLabel.setText("Test Results for Task: " + filterTaskId + " (" + results.size() + " records)");
            } else {
                WorkspaceService ws = TrystUiServices.getWorkspaceService();
                if (ws != null && ws.getCurrentWorkspace() != null) {
                    for (var t : ws.getCurrentWorkspace().getTasks()) {
                        results.addAll(repo.findByTaskId(t.getId()));
                    }
                }
                statusLabel.setText("Execution Results (" + results.size() + " total records)");
            }
            resultsViewer.setInput(results);
        } else {
            statusLabel.setText("ExecutionResultRepository unavailable.");
            resultsViewer.setInput(Collections.emptyList());
        }
    }

    private static class ResultLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof ExecutionResult r) {
                return switch (columnIndex) {
                    case 0 -> r.getId();
                    case 1 -> r.getTaskId();
                    case 2 -> r.isSuccess() ? "PASS" : "FAIL";
                    case 3 -> r.getSummary() != null ? r.getSummary() : (r.getOutput() != null ? r.getOutput().replace("\n", " ") : "");
                    case 4 -> r.getCompletedAt() != null ? r.getCompletedAt().toString() : "";
                    default -> "";
                };
            }
            return "";
        }
    }
}
