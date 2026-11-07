package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import java.util.Objects;

/**
 * Convenient abstract base implementation for {@link ITrystEditorPage}.
 */
public abstract class AbstractTrystEditorPage implements ITrystEditorPage {

    private final String id;
    private final String title;
    protected final TrystWorkbenchEditor editor;
    private Control rootControl;

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
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        container.setLayout(layout);

        createPageContent(container);
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
    }

    @Override
    public void onDeactivated() {
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
    }

    @Override
    public void refresh() {
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
        }
    }
}
