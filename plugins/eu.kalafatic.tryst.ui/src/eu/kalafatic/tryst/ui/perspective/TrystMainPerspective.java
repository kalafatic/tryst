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
        layout.setEditorAreaVisible(false);

        IFolderLayout leftFolder = layout.createFolder("leftFolder", IPageLayout.LEFT, 0.25f, editorArea);
        leftFolder.addView(ProjectExplorerView.ID);

        IFolderLayout bottomFolder = layout.createFolder("bottomFolder", IPageLayout.BOTTOM, 0.70f, editorArea);
        bottomFolder.addView(ConsoleOutputView.ID);

        IFolderLayout centerFolder = layout.createFolder("centerFolder", IPageLayout.RIGHT, 0.75f, "leftFolder");
        centerFolder.addView(MainWorkspaceView.ID);

        layout.getViewLayout(ProjectExplorerView.ID).setCloseable(false);
        layout.getViewLayout(MainWorkspaceView.ID).setCloseable(false);
        layout.getViewLayout(ConsoleOutputView.ID).setCloseable(false);
    }
}
