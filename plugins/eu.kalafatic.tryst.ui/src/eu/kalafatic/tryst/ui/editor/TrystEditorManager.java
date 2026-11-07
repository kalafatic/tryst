package eu.kalafatic.tryst.ui.editor;

import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;

/**
 * Manages the lifecycle and reliable auto-opening of the central {@link TrystWorkbenchEditor}.
 */
public final class TrystEditorManager {

    private TrystEditorManager() {
    }

    public static IEditorPart ensureEditorOpen(IWorkbenchPage page) {
        if (page == null) return null;

        try {
            TrystWorkbenchEditorInput input = TrystWorkbenchEditorInput.getInstance();

            IEditorReference[] refs = page.getEditorReferences();
            for (IEditorReference ref : refs) {
                if (TrystWorkbenchEditor.ID.equals(ref.getId())) {
                    IEditorPart existing = ref.getEditor(true);
                    if (existing != null) {
                        page.activate(existing);
                        return existing;
                    }
                }
            }

            return page.openEditor(input, TrystWorkbenchEditor.ID, true);
        } catch (Exception e) {
            TrystLog.error(TrystEditorManager.class, "Failed to open central workbench editor: " + e.getMessage(), e);
            return null;
        }
    }

    public static boolean isEditorOpen(IWorkbenchPage page) {
        if (page == null) return false;
        IEditorReference[] refs = page.getEditorReferences();
        for (IEditorReference ref : refs) {
            if (TrystWorkbenchEditor.ID.equals(ref.getId())) {
                return true;
            }
        }
        return false;
    }
}
