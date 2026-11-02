package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/**
 * Workbench action bar configuration defining standard TRYST top menus:
 * File, Edit, Project, Database, Web, Observability, Window, Help.
 */
public class ApplicationActionBarAdvisor extends ActionBarAdvisor {

    private IWorkbenchAction exitAction;
    private IWorkbenchAction aboutAction;
    private IWorkbenchAction preferencesAction;

    private Action openWebBrowserAction;
    private Action openMainPerspectiveAction;
    private Action openAdminPerspectiveAction;
    private Action dbIntegrityAction;
    private Action dbVacuumAction;
    private Action refreshAllAction;

    public ApplicationActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        exitAction = ActionFactory.QUIT.create(window);
        register(exitAction);

        aboutAction = ActionFactory.ABOUT.create(window);
        register(aboutAction);

        preferencesAction = ActionFactory.PREFERENCES.create(window);
        register(preferencesAction);

        openWebBrowserAction = new Action("Open TRYST Web Client (localhost/tryst)") {
            @Override
            public void run() {
                String url = resolveWebUrl();
                boolean success = Program.launch(url);
                if (!success) {
                    TrystLog.warn(ApplicationActionBarAdvisor.class, "Failed to launch external browser for: " + url);
                    MessageDialog.openInformation(window.getShell(), "TRYST Web", "Server running at: " + url);
                }
            }
        };
        register(openWebBrowserAction);

        openMainPerspectiveAction = new Action("Switch to Main Perspective") {
            @Override
            public void run() {
                try {
                    window.getWorkbench().showPerspective("eu.kalafatic.tryst.ui.perspective.main", window);
                } catch (Exception e) {
                    TrystLog.error(ApplicationActionBarAdvisor.class, "Failed to show main perspective", e);
                }
            }
        };
        register(openMainPerspectiveAction);

        openAdminPerspectiveAction = new Action("Switch to Administration Perspective") {
            @Override
            public void run() {
                try {
                    window.getWorkbench().showPerspective("eu.kalafatic.tryst.ui.perspective.administration", window);
                } catch (Exception e) {
                    TrystLog.error(ApplicationActionBarAdvisor.class, "Failed to show admin perspective", e);
                }
            }
        };
        register(openAdminPerspectiveAction);

        dbIntegrityAction = new Action("Run Database PRAGMA Integrity Check") {
            @Override
            public void run() {
                try {
                    var coord = TrystApplicationPlugin.getDefault() != null ? TrystApplicationPlugin.getDefault().getLifecycleCoordinator() : null;
                    if (coord != null && coord.getDatabaseManager() != null) {
                        try (var conn = coord.getDatabaseManager().getConnection();
                             var stmt = conn.createStatement();
                             var rs = stmt.executeQuery("PRAGMA integrity_check;")) {
                            if (rs.next()) {
                                MessageDialog.openInformation(window.getShell(), "Database Check", "PRAGMA integrity_check: " + rs.getString(1));
                            }
                        }
                    } else {
                        MessageDialog.openWarning(window.getShell(), "Database Check", "Database is not available.");
                    }
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", "Integrity check failed: " + e.getMessage());
                }
            }
        };
        register(dbIntegrityAction);

        dbVacuumAction = new Action("Compact Database (VACUUM)") {
            @Override
            public void run() {
                try {
                    var coord = TrystApplicationPlugin.getDefault() != null ? TrystApplicationPlugin.getDefault().getLifecycleCoordinator() : null;
                    if (coord != null && coord.getDatabaseManager() != null) {
                        try (var conn = coord.getDatabaseManager().getConnection();
                             var stmt = conn.createStatement()) {
                            stmt.execute("VACUUM;");
                            MessageDialog.openInformation(window.getShell(), "Database Compact", "VACUUM completed successfully.");
                        }
                    } else {
                        MessageDialog.openWarning(window.getShell(), "Database Compact", "Database is not available.");
                    }
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", "VACUUM failed: " + e.getMessage());
                }
            }
        };
        register(dbVacuumAction);

        refreshAllAction = new Action("Refresh System Status") {
            @Override
            public void run() {
                var coord = TrystApplicationPlugin.getDefault() != null ? TrystApplicationPlugin.getDefault().getLifecycleCoordinator() : null;
                if (coord != null && coord.getServerReadinessService() != null) {
                    var rep = coord.getServerReadinessService().evaluateReadiness(coord.getCurrentStage());
                    MessageDialog.openInformation(window.getShell(), "TRYST Server Readiness",
                            "Stage: " + coord.getCurrentStage() + "\nReady: " + rep.ready() + "\nDegraded: " + rep.degraded());
                }
            }
        };
        register(refreshAllAction);
    }

    private String resolveWebUrl() {
        var coord = TrystApplicationPlugin.getDefault() != null ? TrystApplicationPlugin.getDefault().getLifecycleCoordinator() : null;
        if (coord != null && coord.getConfigurationService() != null) {
            var reg = coord.getConfigurationService().getEndpointRegistry();
            if (reg != null && reg.getCurrentEndpoint() != null) {
                var ep = reg.getCurrentEndpoint();
                if (ep.canonicalUrl() != null && !ep.canonicalUrl().isBlank()) return ep.canonicalUrl();
                if (ep.externalUrl() != null && !ep.externalUrl().isBlank()) return ep.externalUrl();
            }
            if (coord.getConfiguration() != null && coord.getConfiguration().canonicalUrl() != null) {
                return coord.getConfiguration().canonicalUrl();
            }
        }
        return "http://localhost/tryst";
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager fileMenu = new MenuManager("&File", IWorkbenchActionConstants.M_FILE);
        fileMenu.add(new GroupMarker(IWorkbenchActionConstants.FILE_START));
        fileMenu.add(openWebBrowserAction);
        fileMenu.add(new Separator());
        fileMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        fileMenu.add(exitAction);
        menuBar.add(fileMenu);

        MenuManager projectMenu = new MenuManager("&Project", "project");
        projectMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(projectMenu);

        MenuManager dbMenu = new MenuManager("&Database", "database");
        dbMenu.add(dbIntegrityAction);
        dbMenu.add(dbVacuumAction);
        dbMenu.add(new Separator());
        dbMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(dbMenu);

        MenuManager webMenu = new MenuManager("&Web", "web");
        webMenu.add(openWebBrowserAction);
        webMenu.add(new Separator());
        webMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(webMenu);

        MenuManager obsMenu = new MenuManager("&Observability", "observability");
        obsMenu.add(refreshAllAction);
        obsMenu.add(new Separator());
        obsMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(obsMenu);

        MenuManager windowMenu = new MenuManager("&Window", IWorkbenchActionConstants.M_WINDOW);
        MenuManager perspMenu = new MenuManager("Open &Perspective", "openPerspective");
        perspMenu.add(openMainPerspectiveAction);
        perspMenu.add(openAdminPerspectiveAction);
        windowMenu.add(perspMenu);
        windowMenu.add(new Separator());
        windowMenu.add(preferencesAction);
        windowMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(windowMenu);

        MenuManager helpMenu = new MenuManager("&Help", IWorkbenchActionConstants.M_HELP);
        helpMenu.add(new GroupMarker(IWorkbenchActionConstants.HELP_START));
        helpMenu.add(aboutAction);
        helpMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(helpMenu);
    }
}
