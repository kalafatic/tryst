package eu.kalafatic.tryst.ui.perspective;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * Native Eclipse RCP TRYST Administration Perspective.
 * Arranges server monitoring, users/sessions, processes, audit, and database views.
 */
public class TrystAdministrationPerspective implements IPerspectiveFactory {

    public static final String ID = "eu.kalafatic.tryst.ui.perspective.administration";

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(false);

        // Top Left: Server Overview & AI & Embedded Browser
        IFolderLayout topLeft = layout.createFolder("topLeft", IPageLayout.LEFT, 0.40f, editorArea);
        topLeft.addView("eu.kalafatic.tryst.ui.view.serverOverview");
        topLeft.addView("eu.kalafatic.tryst.ui.view.embeddedBrowser");
        topLeft.addView("eu.kalafatic.tryst.ui.view.aiMonitoring");

        // Bottom Left: Telemetry & Database
        IFolderLayout bottomLeft = layout.createFolder("bottomLeft", IPageLayout.BOTTOM, 0.50f, "topLeft");
        bottomLeft.addView("eu.kalafatic.tryst.ui.view.telemetry");
        bottomLeft.addView("eu.kalafatic.tryst.ui.view.databaseAdmin");

        // Top Right: Users & Sessions, Projects & Tasks, Session Detail
        IFolderLayout topRight = layout.createFolder("topRight", IPageLayout.RIGHT, 0.60f, editorArea);
        topRight.addView("eu.kalafatic.tryst.ui.view.sessionDetail");
        topRight.addView("eu.kalafatic.tryst.ui.view.usersAndSessions");
        topRight.addView("eu.kalafatic.tryst.ui.view.projectsAndGit");
        topRight.addView("eu.kalafatic.tryst.ui.view.tasksAndAgents");

        // Bottom Right: Processes, Audit & Logs
        IFolderLayout bottomRight = layout.createFolder("bottomRight", IPageLayout.BOTTOM, 0.50f, "topRight");
        bottomRight.addView("eu.kalafatic.tryst.ui.view.processes");
        bottomRight.addView("eu.kalafatic.tryst.ui.view.auditAndLogs");
    }
}
