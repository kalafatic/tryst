package eu.kalafatic.tryst.application;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Workbench action bar configuration defining the standard Eclipse TRYST top menus:
 * File, Edit, Navigate, Project, Tasks, Database, TEIP, Web, Tools, Observability, Window, Help.
 */
public class ApplicationActionBarAdvisor extends ActionBarAdvisor {

    private final ApplicationLifecycleCoordinator coordinator;

    private IWorkbenchAction exitAction;
    private IWorkbenchAction preferencesAction;
    private IWorkbenchAction resetPerspectiveAction;
    private IWorkbenchAction showViewMenuAction;

    // Standard Edit Actions
    private IWorkbenchAction undoAction;
    private IWorkbenchAction redoAction;
    private IWorkbenchAction cutAction;
    private IWorkbenchAction copyAction;
    private IWorkbenchAction pasteAction;
    private IWorkbenchAction selectAllAction;

    // Standard Navigate Actions
    private IWorkbenchAction backAction;
    private IWorkbenchAction forwardAction;

    private Action openWebBrowserAction;
    private Action openMainPerspectiveAction;
    private Action openAdminPerspectiveAction;
    private Action newTaskAction;
    private Action openNavigatorViewAction;
    private Action openConsoleViewAction;
    private Action openCentralWorkspaceAction;
    private Action openTerminalAction;

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

        preferencesAction = ActionFactory.PREFERENCES.create(window);
        register(preferencesAction);

        resetPerspectiveAction = ActionFactory.RESET_PERSPECTIVE.create(window);
        register(resetPerspectiveAction);

        showViewMenuAction = ActionFactory.SHOW_VIEW_MENU.create(window);
        register(showViewMenuAction);

        // --- Standard Edit Actions (Eclipse command infrastructure; enablement follows focus) ---
        undoAction = ActionFactory.UNDO.create(window);
        register(undoAction);
        redoAction = ActionFactory.REDO.create(window);
        register(redoAction);
        cutAction = ActionFactory.CUT.create(window);
        register(cutAction);
        copyAction = ActionFactory.COPY.create(window);
        register(copyAction);
        pasteAction = ActionFactory.PASTE.create(window);
        register(pasteAction);
        selectAllAction = ActionFactory.SELECT_ALL.create(window);
        register(selectAllAction);

        // --- Standard Navigate Actions (workbench navigation history) ---
        backAction = ActionFactory.BACK.create(window);
        register(backAction);
        forwardAction = ActionFactory.FORWARD.create(window);
        register(forwardAction);

        newTaskAction = new Action("New &Task...") {
            @Override
            public void run() {
                // Task intake is owned by the live orchestration pipeline (TaskOrchestrator +
                // TaskIntakeService) exposed through the web UI. Open the web client directly
                // on the intake form instead of creating a dead legacy AgentTask.
                String url = eu.kalafatic.tryst.application.launcher.WebInterfaceLauncher.resolveUrl();
                if (!url.contains("?")) {
                    url += "?newTask=1";
                } else {
                    url += "&newTask=1";
                }
                boolean success = Program.launch(url);
                if (!success) {
                    TrystLog.warn(ApplicationActionBarAdvisor.class, "Failed to launch external browser for new task intake: " + url);
                    MessageDialog.openInformation(window.getShell(), "New Task",
                            "Submit new tasks through the TRYST web client at: " + url);
                }
            }
        };
        newTaskAction.setId("eu.kalafatic.tryst.application.action.newTask");
        register(newTaskAction);

        openNavigatorViewAction = new Action("TRYST &Navigator") {
            @Override
            public void run() {
                try {
                    window.getActivePage().showView("eu.kalafatic.tryst.ui.navigator.view");
                } catch (Exception ignored) {
                }
            }
        };
        openNavigatorViewAction.setId("eu.kalafatic.tryst.application.action.showNavigator");
        register(openNavigatorViewAction);

        openConsoleViewAction = new Action("TRYST &Console") {
            @Override
            public void run() {
                try {
                    window.getActivePage().showView("eu.kalafatic.tryst.ui.view.consoleOutput");
                } catch (Exception ignored) {
                }
            }
        };
        openConsoleViewAction.setId("eu.kalafatic.tryst.application.action.showConsole");
        register(openConsoleViewAction);

        openCentralWorkspaceAction = new Action("Open &Central Workspace") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.studio");
            }
        };
        openCentralWorkspaceAction.setId("eu.kalafatic.tryst.application.action.openCentralWorkspace");
        register(openCentralWorkspaceAction);

        openWebBrowserAction = new Action("Open TRYST &Web Interface") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.launcher.WebInterfaceLauncher.open(window.getShell());
            }
        };
        openWebBrowserAction.setId("eu.kalafatic.tryst.application.action.openWebBrowser");
        register(openWebBrowserAction);

        openTerminalAction = new Action("Open &Terminal") {
            @Override
            public void run() {
                String directory = resolveWorkspaceDirectory();
                Path terminalDir = directory != null ? Path.of(directory) : null;
                eu.kalafatic.tryst.application.launcher.TerminalLauncher.launchWithUi(window.getShell(), terminalDir);
            }
        };
        openTerminalAction.setId("eu.kalafatic.tryst.application.action.openTerminal");
        register(openTerminalAction);

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

    /**
     * Resolves the current workspace root directory from the workspace service when
     * available; returns {@code null} when no live workspace is loaded.
     */
    private String resolveWorkspaceDirectory() {
        var coord = resolveCoordinator();
        if (coord != null && coord.getWorkspaceService() != null) {
            try {
                var ws = coord.getWorkspaceService().getCurrentWorkspace();
                if (ws != null && ws.getRootPath() != null && Files.isDirectory(ws.getRootPath())) {
                    return ws.getRootPath().toAbsolutePath().toString();
                }
            } catch (Exception e) {
                TrystLog.warn(ApplicationActionBarAdvisor.class, "Could not resolve workspace directory: " + e.getMessage());
            }
        }
        return null;
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        IWorkbenchWindow window = getActionBarConfigurer().getWindowConfigurer().getWindow();

        // --- Standard File Menu (only actions that are functional in TRYST) ---
        MenuManager fileMenu = new MenuManager("&File", IWorkbenchActionConstants.M_FILE);
        fileMenu.add(new GroupMarker(IWorkbenchActionConstants.FILE_START));
        fileMenu.add(newTaskAction);
        fileMenu.add(refreshAllAction);
        fileMenu.add(new Separator());
        fileMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        fileMenu.add(new Separator());
        fileMenu.add(exitAction);
        menuBar.add(fileMenu);

        // --- Standard Edit Menu (Eclipse command infrastructure) ---
        MenuManager editMenu = new MenuManager("&Edit", IWorkbenchActionConstants.M_EDIT);
        editMenu.add(undoAction);
        editMenu.add(redoAction);
        editMenu.add(new Separator());
        editMenu.add(cutAction);
        editMenu.add(copyAction);
        editMenu.add(pasteAction);
        editMenu.add(new Separator());
        editMenu.add(selectAllAction);
        editMenu.add(new Separator());
        editMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        editMenu.add(new Separator());
        editMenu.add(preferencesAction);
        menuBar.add(editMenu);

        // --- Standard Navigate Menu (only navigation that is functional in TRYST) ---
        MenuManager navigateMenu = new MenuManager("&Navigate", "navigate");
        navigateMenu.add(backAction);
        navigateMenu.add(forwardAction);
        navigateMenu.add(new Separator());
        navigateMenu.add(openNavigatorViewAction);
        navigateMenu.add(openConsoleViewAction);
        navigateMenu.add(new Separator());
        navigateMenu.add(openCentralWorkspaceAction);
        navigateMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(navigateMenu);

        MenuManager projectMenu = new MenuManager("&Project", "project");
        projectMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(projectMenu);

        // Dedicated Tasks & Studio Menu
        MenuManager tasksMenu = new MenuManager("&Tasks", "tasks");
        tasksMenu.add(new Action("Open in Studio...") {
            @Override
            public void run() {
                switchEditorPage(window, "eu.kalafatic.tryst.ui.editor.page.studio");
            }
        });
        tasksMenu.add(new Separator());
        tasksMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(tasksMenu);

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
        teipMenu.add(viewRoutingHistoryAction);
        teipMenu.add(new Separator());
        teipMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(teipMenu);

        // Web Interface menu: lifecycle controls that reflect the actual server
        // state. All actions route through the single coordinator lifecycle
        // (startHttpService / stopHttpService / restartHttpService) — no second
        // server implementation. Rebuilt on every menu open.
        MenuManager webMenu = new MenuManager("&Web", "web");
        webMenu.add(new WebInterfaceContributionItem());
        webMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(webMenu);

        // Tools menu (Family UX standard v1.0): single home of the terminal and web
        // interface launch actions.
        MenuManager toolsMenu = new MenuManager("&Tools", "tools");
        toolsMenu.add(openTerminalAction);
        toolsMenu.add(openWebBrowserAction);
        toolsMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(toolsMenu);

        MenuManager obsMenu = new MenuManager("&Observability", "observability");
        obsMenu.add(refreshAllAction);
        obsMenu.add(new Separator());
        obsMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(obsMenu);

        // --- Standard Window Menu ---
        MenuManager windowMenu = new MenuManager("&Window", IWorkbenchActionConstants.M_WINDOW);
        MenuManager perspMenu = new MenuManager("Open &Perspective", "openPerspective");
        perspMenu.add(openMainPerspectiveAction);
        perspMenu.add(openAdminPerspectiveAction);
        windowMenu.add(perspMenu);
        windowMenu.add(showViewMenuAction);
        windowMenu.add(new Separator());
        windowMenu.add(resetPerspectiveAction);
        windowMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createCustomizePerspectiveItem(window));
        windowMenu.add(new Separator());
        windowMenu.add(preferencesAction);
        windowMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(windowMenu);

        // --- Standard Help Menu (web-based help topics, then native Eclipse dialogs and p2) ---
        MenuManager helpMenu = new MenuManager("&Help", IWorkbenchActionConstants.M_HELP);
        helpMenu.add(new GroupMarker(IWorkbenchActionConstants.HELP_START));
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createHelpContentsAction(window));
        helpMenu.add(new Action("&Getting Started") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.help.HelpLauncher.openTopic(window, eu.kalafatic.tryst.application.help.HelpLauncher.ANCHOR_GETTING_STARTED);
            }
        });
        helpMenu.add(new Action("Context &Help") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.help.HelpLauncher.openContextHelp(window);
            }
        });
        helpMenu.add(new Action("&Keyboard Shortcuts") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.help.HelpLauncher.openTopic(window, eu.kalafatic.tryst.application.help.HelpLauncher.ANCHOR_SHORTCUTS);
            }
        });
        helpMenu.add(new Action("&CLI Commands") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.help.HelpLauncher.openTopic(window, eu.kalafatic.tryst.application.help.HelpLauncher.ANCHOR_CLI);
            }
        });
        helpMenu.add(new Action("&Diagnostics") {
            @Override
            public void run() {
                eu.kalafatic.tryst.application.launcher.WebInterfaceLauncher.openPath(window.getShell(), "/api/status");
            }
        });
        helpMenu.add(new Separator());
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createAboutAction(window));
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createInstallationDetailsItem(window));
        helpMenu.add(new Separator());
        // Check for Updates and Install New Software... are contributed by the
        // standard Eclipse p2 UI (org.eclipse.equinox.p2.ui.sdk) into the
        // additions group below.
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createInstallNewSoftwareItem(window));
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createManageSoftwareSitesAction(window));
        helpMenu.add(new Separator());
        helpMenu.add(eu.kalafatic.tryst.application.help.TrystHelpMenuSupport.createAboutInstallationAction(window));
        helpMenu.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menuBar.add(helpMenu);
    }

    /**
     * Dynamic Web menu content: Open Web Interface / Open configured URL /
     * Start / Stop / Restart / Check Status. Rebuilt on every menu open so
     * enablement reflects the actual server state. Every control routes
     * through the single {@link ApplicationLifecycleCoordinator} lifecycle.
     */
    private final class WebInterfaceContributionItem extends ContributionItem {

        WebInterfaceContributionItem() {
            super("eu.kalafatic.tryst.application.webInterfaceControls");
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public void fill(Menu parent, int index) {
            IWorkbenchWindow window = getActionBarConfigurer().getWindowConfigurer()
                    .getWindow();
            ApplicationLifecycleCoordinator coord = resolveCoordinator();
            boolean running = coord != null && coord.isHttpServerRunning();
            boolean disabled = coord == null
                    || "DISABLED".equals(coord.webServerState());
            String state = coord == null ? "STOPPED" : coord.webServerState();

            MenuItem openItem = new MenuItem(parent, SWT.PUSH, index++);
            openItem.setText("Open Web Interface");
            openItem.setEnabled(running);
            openItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    eu.kalafatic.tryst.application.launcher.WebInterfaceLauncher
                            .open(window.getShell());
                }
            });

            MenuItem openUrlItem = new MenuItem(parent, SWT.PUSH, index++);
            openUrlItem.setText("Open Configured Web URL");
            openUrlItem.setEnabled(running);
            openUrlItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    Program.launch(eu.kalafatic.tryst.application.launcher
                            .WebInterfaceLauncher.resolveUrl());
                }
            });

            new MenuItem(parent, SWT.SEPARATOR, index++);

            MenuItem startItem = new MenuItem(parent, SWT.PUSH, index++);
            startItem.setText("Start Web Server");
            startItem.setEnabled(!running && !disabled);
            startItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    runServerOperation(window, "start", () -> coord.startHttpServer());
                }
            });

            MenuItem stopItem = new MenuItem(parent, SWT.PUSH, index++);
            stopItem.setText("Stop Web Server");
            stopItem.setEnabled(running);
            stopItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    runServerOperation(window, "stop", () -> coord.stopHttpService());
                }
            });

            MenuItem restartItem = new MenuItem(parent, SWT.PUSH, index++);
            restartItem.setText("Restart Web Server");
            restartItem.setEnabled(running);
            restartItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    runServerOperation(window, "restart", () -> coord.restartHttpService());
                }
            });

            new MenuItem(parent, SWT.SEPARATOR, index++);

            MenuItem statusItem = new MenuItem(parent, SWT.PUSH, index++);
            statusItem.setText("Check Status");
            statusItem.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
                @Override
                public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                    String url = eu.kalafatic.tryst.application.launcher
                            .WebInterfaceLauncher.resolveUrl();
                    MessageDialog.openInformation(window.getShell(), "Web Server Status",
                            "State: " + state
                                    + (running ? "\nURL: " + url : ""));
                }
            });
        }

        private void runServerOperation(IWorkbenchWindow window, String operation,
                                        ServerOperationBody body) {
            try {
                body.run();
            } catch (Exception e) {
                MessageDialog.openError(window.getShell(),
                        "Web Server " + operation + " failed", e.getMessage());
            }
        }

        private interface ServerOperationBody {
            void run() throws Exception;
        }
    }
}
