package eu.kalafatic.tryst.ui.perspective;

import eu.kalafatic.tryst.ui.view.ConsoleOutputView;
import eu.kalafatic.tryst.ui.view.MainWorkspaceView;
import eu.kalafatic.tryst.ui.view.ProjectExplorerView;
import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * Main workbench perspective defining the initial layout:
 * - Left: Project Explorer
 * - Center: Main Workspace
 * - Bottom: Console / Output / Status
 */
public class TrystMainPerspective implements IPerspectiveFactory {

    public static final String ID = "eu.kalafatic.tryst.ui.perspective.main";

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        IFolderLayout leftTopFolder = layout.createFolder("navigatorFolder", IPageLayout.LEFT, 0.22f, editorArea);
        leftTopFolder.addView(eu.kalafatic.tryst.ui.navigator.TrystNavigatorView.ID);

        IFolderLayout leftBottomFolder = layout.createFolder("consoleFolder", IPageLayout.BOTTOM, 0.50f, "navigatorFolder");
        leftBottomFolder.addView(ConsoleOutputView.ID);

        if (layout.getViewLayout(eu.kalafatic.tryst.ui.navigator.TrystNavigatorView.ID) != null) {
            layout.getViewLayout(eu.kalafatic.tryst.ui.navigator.TrystNavigatorView.ID).setCloseable(false);
        }
        if (layout.getViewLayout(ConsoleOutputView.ID) != null) {
            layout.getViewLayout(ConsoleOutputView.ID).setCloseable(false);
        }

        layout.addShowViewShortcut(eu.kalafatic.tryst.ui.navigator.TrystNavigatorView.ID);
        layout.addShowViewShortcut(ConsoleOutputView.ID);
        layout.addPerspectiveShortcut(ID);
        layout.addPerspectiveShortcut(TrystAdministrationPerspective.ID);
    }
}
