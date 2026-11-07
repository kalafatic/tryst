package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
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
 * Editor page for Code Review Center inspection, diffs, and approval tracking.
 */
public class CodeReviewPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.codeReview";

    private TableViewer reviewViewer;
    private Label statusLabel;
    private String filterTaskId;

    public CodeReviewPage(TrystWorkbenchEditor editor) {
        super(ID, "Code Review", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Active Code Reviews across projects:");

        reviewViewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = reviewViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createColumn("Review ID", 120);
        createColumn("Task ID", 120);
        createColumn("Status", 100);
        createColumn("Revisions", 80);
        createColumn("Created", 180);

        reviewViewer.setContentProvider(ArrayContentProvider.getInstance());
        reviewViewer.setLabelProvider(new ReviewLabelProvider());

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Reviews");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
    }

    private void createColumn(String title, int width) {
        TableColumn col = new TableColumn(reviewViewer.getTable(), SWT.LEFT);
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
        if (reviewViewer == null || reviewViewer.getControl().isDisposed()) return;
        CodeReviewRepository repo = TrystUiServices.getCodeReviewRepository();
        if (repo != null) {
            List<CodeReview> reviews = repo.findAll();
            if (filterTaskId != null && !filterTaskId.isBlank()) {
                reviews = reviews.stream()
                        .filter(r -> filterTaskId.equals(r.getTaskId()))
                        .toList();
                statusLabel.setText("Filtered Reviews for Task: " + filterTaskId + " (" + reviews.size() + " reviews)");
            } else {
                statusLabel.setText("Active Code Reviews across projects (" + reviews.size() + " total):");
            }
            reviewViewer.setInput(reviews);
        } else {
            statusLabel.setText("CodeReviewRepository unavailable.");
            reviewViewer.setInput(Collections.emptyList());
        }
    }

    private static class ReviewLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element instanceof CodeReview r) {
                return switch (columnIndex) {
                    case 0 -> r.getId();
                    case 1 -> r.getTaskId();
                    case 2 -> r.getStatus() != null ? r.getStatus().name() : "";
                    case 3 -> String.valueOf(r.getRevisionCount());
                    case 4 -> r.getCreatedAt() != null ? r.getCreatedAt().toString() : "";
                    default -> "";
                };
            }
            return "";
        }
    }
}
