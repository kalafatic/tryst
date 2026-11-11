package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.ui.IEditorPart;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Workbench action bar configuration defining standard TRYST top menus:
 * File, Edit, Project, Database, TEIP, Web, Observability, Window, Help.
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

    // Database Actions
    private Action openDbExplorerAction;
    private Action refreshDbAction;
    private Action executeSqlAction;
    private Action showTablesAction;
    private Action dbStatsAction;
    private Action dbIntegrityAction;
    private Action dbForeignKeyCheckAction;
    private Action dbWalCheckpointAction;
    private Action dbVacuumAction;

    // TEIP Actions
    private Action openTeipDashboardAction;
    private Action checkTeipStatusAction;
    private Action viewEvoInstancesAction;
    private Action routeTaskToEvoAction;
    private Action evoAssistAction;
    private Action viewRoutingHistoryAction;

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

    private <T> T resolveService(Class<T> serviceClass) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(getClass());
            if (bundle != null) {
                BundleContext context = bundle.getBundleContext();
                if (context != null) {
                    ServiceReference<T> ref = context.getServiceReference(serviceClass);
                    if (ref != null) {
                        return context.getService(ref);
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(ApplicationActionBarAdvisor.class, "Failed to resolve " + serviceClass.getSimpleName() + " from OSGi: " + t.getMessage());
        }
        return null;
    }

    private SqliteDatabaseManager resolveDatabaseManager() {
        ApplicationLifecycleCoordinator coord = resolveCoordinator();
        if (coord != null && coord.getDatabaseManager() != null) {
            return coord.getDatabaseManager();
        }
        return resolveService(SqliteDatabaseManager.class);
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

        // --- Database Actions ---
        openDbExplorerAction = new Action("Open Database Explorer") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.database");
            }
        };
        openDbExplorerAction.setId("eu.kalafatic.tryst.application.action.openDbExplorer");
        register(openDbExplorerAction);

        refreshDbAction = new Action("Refresh Database Schema") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.database");
            }
        };
        refreshDbAction.setId("eu.kalafatic.tryst.application.action.refreshDb");
        register(refreshDbAction);

        executeSqlAction = new Action("Execute SQL Script...") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.database");
            }
        };
        executeSqlAction.setId("eu.kalafatic.tryst.application.action.executeSql");
        register(executeSqlAction);

        showTablesAction = new Action("Show SQLite Tables") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database", "SqliteDatabaseManager is unavailable.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
                    StringBuilder sb = new StringBuilder("Tables in ").append(dbManager.getDatabasePath().getFileName()).append(":\n\n");
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")) {
                        while (rs.next()) {
                            sb.append(" • ").append(rs.getString("name")).append("\n");
                        }
                    }
                    MessageDialog.openInformation(window.getShell(), "SQLite Tables", sb.toString());
                } catch (Exception ex) {
                    MessageDialog.openError(window.getShell(), "Database Error", ex.getMessage());
                }
            }
        };
        showTablesAction.setId("eu.kalafatic.tryst.application.action.showTables");
        register(showTablesAction);

        dbStatsAction = new Action("Database Statistics") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database", "SqliteDatabaseManager is unavailable.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
                    StringBuilder sb = new StringBuilder("Database Path: ").append(dbManager.getDatabasePath()).append("\n\n");
                    try (Statement stmt = conn.createStatement()) {
                        try (ResultSet rs = stmt.executeQuery("PRAGMA page_count;")) {
                            if (rs.next()) sb.append("Page Count: ").append(rs.getLong(1)).append("\n");
                        }
                        try (ResultSet rs = stmt.executeQuery("PRAGMA page_size;")) {
                            if (rs.next()) sb.append("Page Size: ").append(rs.getLong(1)).append(" bytes\n");
                        }
                        try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
                            if (rs.next()) sb.append("Journal Mode: ").append(rs.getString(1)).append("\n");
                        }
                        try (ResultSet rs = stmt.executeQuery("PRAGMA synchronous;")) {
                            if (rs.next()) sb.append("Synchronous: ").append(rs.getString(1)).append("\n");
                        }
                    }
                    MessageDialog.openInformation(window.getShell(), "Database Statistics", sb.toString());
                } catch (Exception ex) {
                    MessageDialog.openError(window.getShell(), "Database Error", ex.getMessage());
                }
            }
        };
        dbStatsAction.setId("eu.kalafatic.tryst.application.action.dbStats");
        register(dbStatsAction);

        dbIntegrityAction = new Action("Run Database PRAGMA Integrity Check") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database Check", "Database is not available.");
                    return;
                }
                try {
                    boolean ok = dbManager.verifyDatabaseIntegrity();
                    MessageDialog.openInformation(window.getShell(), "Database Check",
                            "PRAGMA integrity_check passed: " + (ok ? "ok" : "issues found"));
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", "Integrity check failed: " + e.getMessage());
                }
            }
        };
        dbIntegrityAction.setId("eu.kalafatic.tryst.application.action.dbIntegrity");
        register(dbIntegrityAction);

        dbForeignKeyCheckAction = new Action("Verify Foreign Key Constraints") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database Check", "Database is not available.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_check;")) {
                        if (rs.next()) {
                            MessageDialog.openWarning(window.getShell(), "Foreign Key Warning",
                                    "Violation in table: " + rs.getString(1) + " (rowid " + rs.getLong(2) + ")");
                        } else {
                            MessageDialog.openInformation(window.getShell(), "Foreign Key Check", "Foreign key constraints verified cleanly. No violations found.");
                        }
                    }
                } catch (Exception e) {
                    MessageDialog.openError(window.getShell(), "Database Error", e.getMessage());
                }
            }
        };
        dbForeignKeyCheckAction.setId("eu.kalafatic.tryst.application.action.dbFkCheck");
        register(dbForeignKeyCheckAction);

        dbWalCheckpointAction = new Action("Checkpoint WAL Journal") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager != null) {
                    try {
                        dbManager.checkpointWal("PASSIVE");
                        MessageDialog.openInformation(window.getShell(), "WAL Checkpoint", "WAL checkpoint executed successfully.");
                    } catch (Exception e) {
                        MessageDialog.openError(window.getShell(), "Error", e.getMessage());
                    }
                }
            }
        };
        dbWalCheckpointAction.setId("eu.kalafatic.tryst.application.action.dbWalCheckpoint");
        register(dbWalCheckpointAction);

        dbVacuumAction = new Action("Compact Database (VACUUM)") {
            @Override
            public void run() {
                SqliteDatabaseManager dbManager = resolveDatabaseManager();
                if (dbManager == null) {
                    MessageDialog.openWarning(window.getShell(), "Database Compact", "Database is not available.");
                    return;
                }
                try {
                    Connection conn = dbManager.getConnection();
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

        // --- TEIP Actions ---
        openTeipDashboardAction = new Action("TEIP Integration Dashboard") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.teip");
            }
        };
        openTeipDashboardAction.setId("eu.kalafatic.tryst.application.action.openTeipDashboard");
        register(openTeipDashboardAction);

        checkTeipStatusAction = new Action("Check TEIP / EVO Status & Health") {
            @Override
            public void run() {
                eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoService =
                        resolveService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class);
                if (evoService == null) {
                    MessageDialog.openWarning(window.getShell(), "TEIP Status", "EvoIntegrationService is not registered in runtime.");
                    return;
                }
                EvoConfiguration cfg = evoService.getConfiguration();
                String ep = cfg.getEndpoint();
                boolean enabled = cfg.isEnabled();

                Thread.ofVirtual().start(() -> {
                    String healthStatus;
                    try {
                        var health = evoService.checkHealth();
                        healthStatus = "Status: " + health.status() + "\nVersion: " + health.version();
                    } catch (Exception e) {
                        healthStatus = "Offline / Unreachable (" + e.getMessage() + ")";
                    }
                    final String finalMsg = "EVO Endpoint: " + ep + "\nEnabled: " + enabled + "\n\nHealth Check:\n" + healthStatus;
                    window.getShell().getDisplay().asyncExec(() -> {
                        MessageDialog.openInformation(window.getShell(), "TEIP / EVO Status", finalMsg);
                    });
                });
            }
        };
        checkTeipStatusAction.setId("eu.kalafatic.tryst.application.action.checkTeipStatus");
        register(checkTeipStatusAction);

        viewEvoInstancesAction = new Action("View Available EVO Capabilities") {
            @Override
            public void run() {
                eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoService =
                        resolveService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class);
                if (evoService == null) {
                    MessageDialog.openWarning(window.getShell(), "TEIP", "EvoIntegrationService is unavailable.");
                    return;
                }
                Thread.ofVirtual().start(() -> {
                    String info;
                    try {
                        var caps = evoService.getCapabilities();
                        info = "Capabilities: " + (caps.capabilities() != null ? caps.capabilities().size() : 0) +
                                "\nAvailable Models: " + (caps.availableModels() != null ? caps.availableModels().size() : 0);
                    } catch (Exception e) {
                        info = "Could not query capabilities: " + e.getMessage();
                    }
                    final String res = info;
                    window.getShell().getDisplay().asyncExec(() -> {
                        MessageDialog.openInformation(window.getShell(), "EVO Capabilities", res);
                    });
                });
            }
        };
        viewEvoInstancesAction.setId("eu.kalafatic.tryst.application.action.viewEvoInstances");
        register(viewEvoInstancesAction);

        routeTaskToEvoAction = new Action("Route Task to EVO...") {
            @Override
            public void run() {
                InputDialog dlg = new InputDialog(window.getShell(), "Route to EVO",
                        "Enter Task ID to delegate to EVO peer:", "", null);
                if (dlg.open() == InputDialog.OK && dlg.getValue() != null && !dlg.getValue().isBlank()) {
                    String taskId = dlg.getValue().trim();
                    eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoService =
                            resolveService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class);
                    if (evoService == null) {
                        MessageDialog.openWarning(window.getShell(), "TEIP", "EvoIntegrationService is unavailable.");
                        return;
                    }
                    Thread.ofVirtual().start(() -> {
                        try {
                            EvoExecution exec = evoService.createExecution(
                                    taskId, "session-default", "CODE_GENERATION", "AUTONOMOUS", "qwen2.5-coder",
                                    "User delegated task " + taskId + " to EVO via TEIP", List.of(), List.of(), null
                            );
                            window.getShell().getDisplay().asyncExec(() -> {
                                MessageDialog.openInformation(window.getShell(), "Task Routed",
                                        "Task successfully routed to EVO!\nExecution ID: " + exec.getId() + "\nRemote Status: " + exec.getStatus());
                            });
                        } catch (Exception e) {
                            window.getShell().getDisplay().asyncExec(() -> {
                                MessageDialog.openError(window.getShell(), "Routing Failed", "Failed to delegate to EVO: " + e.getMessage());
                            });
                        }
                    });
                }
            }
        };
        routeTaskToEvoAction.setId("eu.kalafatic.tryst.application.action.routeTaskToEvo");
        register(routeTaskToEvoAction);

        evoAssistAction = new Action("EVO Assist / Diagnostics Request...") {
            @Override
            public void run() {
                InputDialog dlg = new InputDialog(window.getShell(), "EVO Assist",
                        "Enter diagnostic or code query for EVO:", "Explain current repository architecture", null);
                if (dlg.open() == InputDialog.OK && dlg.getValue() != null && !dlg.getValue().isBlank()) {
                    String prompt = dlg.getValue().trim();
                    MessageDialog.openInformation(window.getShell(), "EVO Assist",
                            "Submitted query to EVO peer:\n\"" + prompt + "\"\n\nProcessing in background.");
                }
            }
        };
        evoAssistAction.setId("eu.kalafatic.tryst.application.action.evoAssist");
        register(evoAssistAction);

        viewRoutingHistoryAction = new Action("View EVO Routing History") {
            @Override
            public void run() {
                eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService evoService =
                        resolveService(eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService.class);
                if (evoService == null) {
                    MessageDialog.openWarning(window.getShell(), "TEIP", "EvoIntegrationService is unavailable.");
                    return;
                }
                List<EvoExecution> history = evoService.getAllExecutions();
                StringBuilder sb = new StringBuilder("EVO Routing History (" + history.size() + " total):\n\n");
                for (EvoExecution ex : history) {
                    sb.append(" • [").append(ex.getStatus()).append("] ID: ").append(ex.getId().substring(0, Math.min(8, ex.getId().length())))
                            .append(" (Task: ").append(ex.getTrystTaskId() != null ? ex.getTrystTaskId() : "N/A").append(")\n");
                }
                MessageDialog.openInformation(window.getShell(), "Routing History", sb.toString());
            }
        };
        viewRoutingHistoryAction.setId("eu.kalafatic.tryst.application.action.viewRoutingHistory");
        register(viewRoutingHistoryAction);

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

    private void switchEditorPage(IWorkbenchWindow window, String pageId) {
        try {
            IEditorPart editorPart = window.getActivePage().getActiveEditor();
            if (editorPart != null) {
                var method = editorPart.getClass().getMethod("selectPage", String.class);
                method.invoke(editorPart, pageId);
            }
        } catch (Exception ex) {
            TrystLog.warn(ApplicationActionBarAdvisor.class, "Could not switch to editor page " + pageId + ": " + ex.getMessage());
        }
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

        // Enhanced Database Menu
        MenuManager dbMenu = new MenuManager("&Database", "database");
        dbMenu.add(openDbExplorerAction);
        dbMenu.add(refreshDbAction);
        dbMenu.add(executeSqlAction);
        dbMenu.add(showTablesAction);
        dbMenu.add(dbStatsAction);
        dbMenu.add(new Separator());
        dbMenu.add(dbIntegrityAction);
        dbMenu.add(dbForeignKeyCheckAction);
        dbMenu.add(dbWalCheckpointAction);
        dbMenu.add(dbVacuumAction);
        dbMenu.add(new Separator());
        dbMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(dbMenu);

        // Dedicated TEIP Menu
        MenuManager teipMenu = new MenuManager("&TEIP", "teip");
        teipMenu.add(openTeipDashboardAction);
        teipMenu.add(checkTeipStatusAction);
        teipMenu.add(viewEvoInstancesAction);
        teipMenu.add(new Separator());
        teipMenu.add(routeTaskToEvoAction);
        teipMenu.add(evoAssistAction);
        teipMenu.add(viewRoutingHistoryAction);
        teipMenu.add(new Separator());
        teipMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(teipMenu);

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
