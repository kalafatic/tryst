package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.repository.ExecutionResultRepository;
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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

import java.util.Collections;
import java.util.List;

/**
 * Editor page for Automated Tests, Quality Reports, and Execution Results.
 */
public class TestQualityPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.testQuality";

    private TableViewer resultsViewer;
    private Label statusLabel;
    private String filterTaskId;

    public TestQualityPage(TrystWorkbenchEditor editor) {
        super(ID, "Tests & Quality", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
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
        createColumn("Output Summary", 350);
        createColumn("Executed At", 180);

        resultsViewer.setContentProvider(ArrayContentProvider.getInstance());
        resultsViewer.setLabelProvider(new ResultLabelProvider());

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Quality Reports");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
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
        this.filterTaskId = context.taskId();
        refresh();
    }

    @Override
    public void refresh() {
        if (resultsViewer == null || resultsViewer.getControl().isDisposed()) return;
        ExecutionResultRepository repo = TrystUiServices.getExecutionResultRepository();
        if (repo != null) {
            List<ExecutionResult> results;
            if (filterTaskId != null && !filterTaskId.isBlank()) {
                results = repo.findByTaskId(filterTaskId);
                statusLabel.setText("Test Results for Task: " + filterTaskId + " (" + results.size() + " records)");
            } else {
                results = Collections.emptyList();
                statusLabel.setText("Select a task in Navigator to inspect automated test results.");
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
