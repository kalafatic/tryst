package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;

import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.MessageDialog;
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

import java.util.Collections;
import java.util.List;

/**
 * Editor page for Code Review Center inspection, diffs, and approval tracking.
 * Enhanced with toolbar controls, filter search, copy review ID, and status actions.
 */
public class CodeReviewPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.codeReview";

    private TableViewer reviewViewer;
    private Label statusLabel;
    private String filterTaskId;
    private Text searchFilter;
    private Button btnCopyReviewId;
    private Button btnShowAll;
    private boolean showAll = false;

    public CodeReviewPage(TrystWorkbenchEditor editor) {
        super(ID, "Code Review", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(6, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Reviews");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        btnShowAll = new Button(toolbar, SWT.PUSH);
        btnShowAll.setText("📋 Show All Reviews");
        btnShowAll.addListener(SWT.Selection, e -> {
            showAll = !showAll;
            btnShowAll.setText(showAll ? "🎯 Filter to Active Task" : "📋 Show All Reviews");
            refresh();
        });

        btnCopyReviewId = new Button(toolbar, SWT.PUSH);
        btnCopyReviewId.setText("📋 Copy Review ID");
        btnCopyReviewId.setEnabled(false);
        btnCopyReviewId.addListener(SWT.Selection, e -> copySelectedReviewId());

        Button btnTaskPage = new Button(toolbar, SWT.PUSH);
        btnTaskPage.setText("⚡ Task Management");
        btnTaskPage.addListener(SWT.Selection, e -> editor.selectPage(TaskManagementPage.ID));

        Label lblSearch = new Label(toolbar, SWT.NONE);
        lblSearch.setText("  🔍 Filter:");

        searchFilter = new Text(toolbar, SWT.BORDER | SWT.SEARCH);
        searchFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchFilter.setMessage("Filter reviews by ID, task or status...");
        searchFilter.addModifyListener(e -> applyFilter());

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
        createColumn("Status", 110);
        createColumn("Revisions", 80);
        createColumn("Created", 180);

        reviewViewer.setContentProvider(ArrayContentProvider.getInstance());
        reviewViewer.setLabelProvider(new ReviewLabelProvider());

        reviewViewer.addSelectionChangedListener(event -> {
            btnCopyReviewId.setEnabled(!event.getSelection().isEmpty());
        });

        refresh();
    }

    private void applyFilter() {
        String query = searchFilter.getText().trim().toLowerCase();
        if (query.isEmpty()) {
            reviewViewer.setFilters(new ViewerFilter[0]);
        } else {
            reviewViewer.setFilters(new ViewerFilter() {
                @Override
                public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element) {
                    if (element instanceof CodeReview r) {
                        return (r.getId() != null && r.getId().toLowerCase().contains(query))
                                || (r.getTaskId() != null && r.getTaskId().toLowerCase().contains(query))
                                || (r.getStatus() != null && r.getStatus().name().toLowerCase().contains(query));
                    }
                    return true;
                }
            });
        }
    }

    private void copySelectedReviewId() {
        IStructuredSelection sel = (IStructuredSelection) reviewViewer.getSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof CodeReview r) {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{r.getId()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        }
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
        super.onContextChanged(context);
        this.filterTaskId = context.taskId();
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (reviewViewer == null || reviewViewer.getControl().isDisposed()) return;
        CodeReviewRepository repo = TrystUiServices.getCodeReviewRepository();
        if (repo != null) {
            List<CodeReview> reviews = repo.findAll();
            if (!showAll && filterTaskId != null && !filterTaskId.isBlank()) {
                reviews = reviews.stream()
                        .filter(r -> filterTaskId.equals(r.getTaskId()))
                        .toList();
                statusLabel.setText("Filtered Reviews for Task: " + filterTaskId + " (" + reviews.size() + " reviews)");
            } else {
                statusLabel.setText("Code Reviews (" + reviews.size() + " total records):");
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
