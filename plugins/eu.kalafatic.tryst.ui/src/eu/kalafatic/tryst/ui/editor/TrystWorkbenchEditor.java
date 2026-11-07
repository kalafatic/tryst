package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.ui.context.IWorkbenchContextListener;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.context.WorkbenchContextManager;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.MultiPageEditorPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Dominant MultiPage Editor serving as the primary central working area of TRYST.
 */
public class TrystWorkbenchEditor extends MultiPageEditorPart implements IWorkbenchContextListener {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.workbenchEditor";

    private final List<ITrystEditorPage> pages = new ArrayList<>();
    private int activePageIndex = -1;

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        super.init(site, input);
        setPartName("TRYST Central Workspace");
        WorkbenchContextManager.getInstance().addContextListener(this);
    }

    @Override
    protected void createPages() {
        if (getContainer() instanceof CTabFolder tabFolder) {
            tabFolder.setSimple(false);
            tabFolder.setBorderVisible(true);
        }

        List<TrystEditorPageDescriptor> descriptors = TrystEditorPageRegistry.getInstance().getDescriptors();
        if (descriptors.isEmpty()) {
            StandardEditorPagesBootstrap.initializeStandardPages();
            descriptors = TrystEditorPageRegistry.getInstance().getDescriptors();
        }
        for (TrystEditorPageDescriptor desc : descriptors) {
            try {
                ITrystEditorPage page = desc.factory().createPage(this);
                if (page != null) {
                    Control control = page.createControl(getContainer());
                    int index = addPage(control);
                    setPageText(index, page.getTitle());
                    pages.add(page);
                }
            } catch (Exception e) {
                TrystLog.error(TrystWorkbenchEditor.class, "Failed to instantiate editor page: " + desc.id(), e);
            }
        }

        if (!pages.isEmpty()) {
            setActivePage(0);
            activePageIndex = 0;
            pages.get(0).onActivated();
        }
    }

    @Override
    protected void pageChange(int newPageIndex) {
        if (activePageIndex >= 0 && activePageIndex < pages.size()) {
            pages.get(activePageIndex).onDeactivated();
        }
        super.pageChange(newPageIndex);
        this.activePageIndex = newPageIndex;
        if (newPageIndex >= 0 && newPageIndex < pages.size()) {
            ITrystEditorPage page = pages.get(newPageIndex);
            page.onActivated();
            page.onContextChanged(WorkbenchContextManager.getInstance().getContext());
            page.setFocus();
        }
    }

    public boolean selectPage(String pageId) {
        if (pageId == null) return false;
        for (int i = 0; i < pages.size(); i++) {
            if (pageId.equals(pages.get(i).getId())) {
                setActivePage(i);
                return true;
            }
        }
        return false;
    }

    public ITrystEditorPage getActiveEditorPage() {
        if (activePageIndex >= 0 && activePageIndex < pages.size()) {
            return pages.get(activePageIndex);
        }
        return null;
    }

    public List<ITrystEditorPage> getPages() {
        return List.copyOf(pages);
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        Control control = getContainer();
        if (control == null || control.isDisposed()) return;

        Display display = control.getDisplay();
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (activePageIndex >= 0 && activePageIndex < pages.size()) {
                    pages.get(activePageIndex).onContextChanged(context);
                }
            });
        }
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
    }

    @Override
    public void doSaveAs() {
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void dispose() {
        WorkbenchContextManager.getInstance().removeContextListener(this);
        for (ITrystEditorPage page : pages) {
            try {
                page.dispose();
            } catch (Exception e) {
                TrystLog.warn(TrystWorkbenchEditor.class, "Error disposing page " + page.getId() + ": " + e.getMessage());
            }
        }
        pages.clear();
        super.dispose();
    }
}
