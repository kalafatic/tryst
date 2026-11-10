package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.sql.Connection;

/**
 * Workbench action bar configuration defining standard TRYST top menus:
 * File, Edit, Project, Database, Web, Observability, Window, Help.
 */
public class ApplicationActionBarAdvisor extends ActionBarAdvisor {

    private final ApplicationLifecycleCoordinator coordinator;

    private IWorkbenchAction exitAction;
    private IWorkbenchAction aboutAction;
    private IWorkbenchAction preferencesAction;
    private IWorkbenchAction resetPerspectiveAction;
    private IWorkbenchAction editActionSetAction;
    private IWorkbenchAction showViewMenuAction;

    private Action openWebBrowserAction;
    private Action openMainPerspectiveAction;
    private Action openAdminPerspectiveAction;
    private Action dbIntegrityAction;
    private Action dbVacuumAction;
    private Action refreshAllAction;

    public ApplicationActionBarAdvisor(IActionBarConfigurer configurer) {
        this(configurer, null);
    }

    public ApplicationActionBarAdvisor(IActionBarConfigurer configurer, ApplicationLifecycleCoordinator coordinator) {
        super(configurer);
        this.coordinator = coordinator;
    }

    private ApplicationLifecycleCoordinator resolveCoordinator() {
        if (this.coordinator != null) {
            return this.coordinator;
        }
        return ApplicationWorkbenchAdvisor.resolveCoordinator();
    }

    private SqliteDatabaseManager resolveDatabaseManager() {
        ApplicationLifecycleCoordinator coord = resolveCoordinator();
        if (coord != null && coord.getDatabaseManager() != null) {
            return coord.getDatabaseManager();
        }
        // Fallback to OSGi service registry if coordinator reference was not directly injected
        try {
            Bundle bundle = FrameworkUtil.getBundle(getClass());
            if (bundle != null) {
                BundleContext context = bundle.getBundleContext();
                if (context != null) {
                    ServiceReference<SqliteDatabaseManager> ref = context.getServiceReference(SqliteDatabaseManager.class);
                    if (ref != null) {
                        return context.getService(ref);
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(ApplicationActionBarAdvisor.class, "Failed to resolve SqliteDatabaseManager from OSGi: " + t.getMessage());
        }
        return null;
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        exitAction = ActionFactory.QUIT.create(window);
        register(exitAction);

        aboutAction = ActionFactory.ABOUT.create(window);
        register(aboutAction);

        preferencesAction = ActionFactory.PREFERENCES.create(window);
        register(preferencesAction);

        resetPerspectiveAction = ActionFactory.RESET_PERSPECTIVE.create(window);
        register(resetPerspectiveAction);

        editActionSetAction = ActionFactory.EDIT_ACTION_SETS.create(window);
        register(editActionSetAction);

        showViewMenuAction = ActionFactory.SHOW_VIEW_MENU.create(window);
        register(showViewMenuAction);

        openWebBrowserAction = new Action("Open TRYST Web Client") {
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
        openWebBrowserAction.setId("eu.kalafatic.tryst.application.action.openWebBrowser");
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
        openMainPerspectiveAction.setId("eu.kalafatic.tryst.application.action.openMainPerspective");
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
        openAdminPerspectiveAction.setId("eu.kalafatic.tryst.application.action.openAdminPerspective");
        register(openAdminPerspectiveAction);

        dbIntegrityAction = new Action("Run Database PRAGMA Integrity Check") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database Check",
                            "Database is not available: SqliteDatabaseManager could not be resolved from coordinator or OSGi registry.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
                    if (conn == null || conn.isClosed()) {
                        MessageDialog.openWarning(window.getShell(), "Database Check",
                                "Database connection is closed or uninitialized at: " + dbManager.getDatabasePath());
                        return;
                    }
                    try (var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("PRAGMA integrity_check;")) {
                        if (rs.next()) {
                            String result = rs.getString(1);
                            MessageDialog.openInformation(window.getShell(), "Database Check",
                                    "Database Path: " + dbManager.getDatabasePath() + "\nPRAGMA integrity_check: " + result);
                        } else {
                            MessageDialog.openWarning(window.getShell(), "Database Check", "PRAGMA integrity_check returned no results.");
                        }
                    }
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", "Integrity check failed: " + e.getMessage());
                }
            }
        };
        dbIntegrityAction.setId("eu.kalafatic.tryst.application.action.dbIntegrity");
        register(dbIntegrityAction);

        dbVacuumAction = new Action("Compact Database (VACUUM)") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database Compact",
                            "Database is not available: SqliteDatabaseManager could not be resolved from coordinator or OSGi registry.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
                    if (conn == null || conn.isClosed()) {
                        MessageDialog.openWarning(window.getShell(), "Database Compact",
                                "Database connection is closed or uninitialized at: " + dbManager.getDatabasePath());
                        return;
                    }
                    try (var stmt = conn.createStatement()) {
                        stmt.execute("VACUUM;");
                        MessageDialog.openInformation(window.getShell(), "Database Compact",
                                "VACUUM completed successfully on: " + dbManager.getDatabasePath());
                    }
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", "VACUUM failed: " + e.getMessage());
                }
            }
        };
        dbVacuumAction.setId("eu.kalafatic.tryst.application.action.dbVacuum");
        register(dbVacuumAction);

        refreshAllAction = new Action("Refresh System Status") {
            @Override
            public void run() {
                var coord = resolveCoordinator();
                if (coord != null && coord.getServerReadinessService() != null) {
                    var rep = coord.getServerReadinessService().evaluateReadiness(coord.getCurrentStage());
                    MessageDialog.openInformation(window.getShell(), "TRYST Server Readiness",
                            "Stage: " + coord.getCurrentStage() + "\nReady: " + rep.ready() + "\nDegraded: " + rep.degraded());
                } else {
                    MessageDialog.openWarning(window.getShell(), "TRYST Server Readiness",
                            "Lifecycle coordinator or ServerReadinessService is currently unavailable.");
                }
            }
        };
        refreshAllAction.setId("eu.kalafatic.tryst.application.action.refreshAll");
        register(refreshAllAction);
    }

    private String resolveWebUrl() {
        var coord = resolveCoordinator();
        if (coord != null && coord.getConfigurationService() != null) {
            var reg = coord.getConfigurationService().getEndpointRegistry();
            if (reg != null && reg.getCurrentEndpoint() != null) {
                var ep = reg.getCurrentEndpoint();
                if (ep.externalUrl() != null && !ep.externalUrl().isBlank()) return ep.externalUrl();
                if (ep.internalUrl() != null && !ep.internalUrl().isBlank()) return ep.internalUrl();
                if (ep.canonicalUrl() != null && !ep.canonicalUrl().isBlank()) return ep.canonicalUrl();
            }
            if (coord.getConfiguration() != null) {
                var cfg = coord.getConfiguration();
                if (coord.getHttpService() != null && coord.getHttpService().isRunning()) {
                    return cfg.resolveEffectiveExternalUrl(coord.getHttpService().getPort());
                }
                if (cfg.canonicalUrl() != null) {
                    return cfg.canonicalUrl();
                }
            }
        }
        return "http://127.0.0.1:38080/tryst";
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
        MenuManager showViewMenu = new MenuManager("Show &View", "showView");
        showViewMenu.add(new Action("TRYST Navigator") {
            @Override
            public void run() {
                try {
                    getActionBarConfigurer().getWindowConfigurer().getWindow().getActivePage().showView("eu.kalafatic.tryst.ui.navigator.view");
                } catch (Exception ignored) {}
            }
        });
        showViewMenu.add(new Action("TRYST Console") {
            @Override
            public void run() {
                try {
                    getActionBarConfigurer().getWindowConfigurer().getWindow().getActivePage().showView("eu.kalafatic.tryst.ui.view.consoleOutput");
                } catch (Exception ignored) {}
            }
        });
        windowMenu.add(showViewMenu);
        windowMenu.add(new Separator());
        windowMenu.add(resetPerspectiveAction);
        windowMenu.add(editActionSetAction);
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
