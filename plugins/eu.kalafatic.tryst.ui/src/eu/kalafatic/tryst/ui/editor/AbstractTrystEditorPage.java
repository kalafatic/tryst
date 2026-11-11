package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import java.util.Objects;

/**
 * Convenient abstract base implementation for {@link ITrystEditorPage}.
 * Enhances every editor page with:
 * 1. Resilient scrolling content area.
 * 2. Bottom live HTML status and progress visualization panels.
 */
public abstract class AbstractTrystEditorPage implements ITrystEditorPage {

    private final String id;
    private final String title;
    protected final TrystWorkbenchEditor editor;
    private Control rootControl;
    private EditorLiveStatusAndProgressComposite bottomPanel;

    protected AbstractTrystEditorPage(String id, String title, TrystWorkbenchEditor editor) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.editor = editor;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public Control createControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        container.setLayout(layout);

        // Top main content area
        Composite contentArea = new Composite(container, SWT.NONE);
        GridLayout contentLayout = new GridLayout(1, false);
        contentLayout.marginWidth = 8;
        contentLayout.marginHeight = 8;
        contentArea.setLayout(contentLayout);
        contentArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createPageContent(contentArea);

        // Bottom live status and progress panel
        bottomPanel = new EditorLiveStatusAndProgressComposite(container, id, title);

        this.rootControl = container;
        return container;
    }

    protected abstract void createPageContent(Composite parent);

    @Override
    public Control getControl() {
        return rootControl;
    }

    @Override
    public void onActivated() {
        if (bottomPanel != null && !bottomPanel.isDisposed()) {
            bottomPanel.refresh();
        }
    }

    @Override
    public void onDeactivated() {
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        if (bottomPanel != null && !bottomPanel.isDisposed()) {
            bottomPanel.updateContext(context);
        }
    }

    @Override
    public void refresh() {
        if (bottomPanel != null && !bottomPanel.isDisposed()) {
            bottomPanel.refresh();
        }
    }

    public EditorLiveStatusAndProgressComposite getBottomPanel() {
        return bottomPanel;
    }

    @Override
    public void setFocus() {
        if (rootControl != null && !rootControl.isDisposed()) {
            rootControl.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (rootControl != null && !rootControl.isDisposed()) {
            rootControl.dispose();
            rootControl = null;
            bottomPanel = null;
        }
    }
}
