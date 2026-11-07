package eu.kalafatic.tryst.ui.perspective;

import eu.kalafatic.tryst.ui.editor.TrystEditorManager;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PerspectiveAdapter;

/**
 * Workbench perspective listener that automatically ensures the central
 * {@link eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor} is open when {@link TrystMainPerspective} is activated.
 */
public class TrystPerspectiveLifecycleListener extends PerspectiveAdapter {

    @Override
    public void perspectiveActivated(IWorkbenchPage page, IPerspectiveDescriptor perspective) {
        if (page != null && perspective != null && TrystMainPerspective.ID.equals(perspective.getId())) {
            TrystEditorManager.ensureEditorOpen(page);
        }
    }
}
