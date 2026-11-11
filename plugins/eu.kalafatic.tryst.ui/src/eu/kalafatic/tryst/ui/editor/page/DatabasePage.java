package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.concurrent.AsyncExecutionService;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated Database Page in the TRYST Multipage Editor offering a full Eclipse DTP/Database Viewer experience:
 * - Left: Database Navigator tree (Connections, Schemas, Tables, Views, Columns, PK/FK constraints, Indexes).
 * - Right Top: Results area supporting tabular browsing, sorting, pagination, NULL formatting, cell/row copying, CSV export, execution badge.
 * - Right Bottom: SQL Editor supporting statement execution, formatting, history, cancellation, script load/save.
 */
public class DatabasePage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.database";

    // Navigator
    private TreeViewer dbTreeViewer;

    // Results area
    private Table resultTable;
    private Label lblResultStatus;
    private Button btnCopyResult;
    private Button btnExportCsv;

    // SQL Editor
    private StyledText sqlEditor;
    private ComboViewer queryHistoryCombo;
    private final List<String> queryHistory = new ArrayList<>();
    private Button btnExecute;
    private Button btnExecuteSelected;
    private Button btnCancel;
    private final AtomicBoolean executingQuery = new AtomicBoolean(false);
    private Statement activeStatement = null;

    public DatabasePage(TrystWorkbenchEditor editor) {
        super(ID, "Database", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Main horizontal sash: Left = Navigator (25%), Right = Results + SQL Editor (75%)
        SashForm mainSash = new SashForm(parent, SWT.HORIZONTAL);
        mainSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // 1. LEFT PANE - Database Navigator
        createLeftNavigatorPane(mainSash);

        // 2. RIGHT PANE - Results & SQL Editor
        SashForm rightSash = new SashForm(mainSash, SWT.VERTICAL);
        rightSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createRightTopResultsPane(rightSash);
        createRightBottomSqlEditorPane(rightSash);

        mainSash.setWeights(25, 75);
        rightSash.setWeights(55, 45);

        refreshDatabaseNavigator();
    }

    // -------------------------------------------------------------
    // LEFT PANE: Database Navigator
    // -------------------------------------------------------------
    private void createLeftNavigatorPane(Composite parent) {
        Group navGroup = new Group(parent, SWT.NONE);
        navGroup.setText("Database Navigator");
        navGroup.setLayout(new GridLayout(1, false));

        Composite toolbar = new Composite(navGroup, SWT.NONE);
        toolbar.setLayout(new GridLayout(2, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Schema");
        btnRefresh.addListener(SWT.Selection, e -> refreshDatabaseNavigator());

        Button btnNewScript = new Button(toolbar, SWT.PUSH);
        btnNewScript.setText("➕ New Query");
        btnNewScript.addListener(SWT.Selection, e -> {
            sqlEditor.setText("SELECT * FROM sqlite_master WHERE type='table';");
            sqlEditor.setFocus();
        });

        dbTreeViewer = new TreeViewer(navGroup, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        dbTreeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        dbTreeViewer.setContentProvider(new DbTreeContentProvider());
        dbTreeViewer.setLabelProvider(new DbTreeLabelProvider());

        dbTreeViewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof DbTreeNode node) {
                if (node.type == DbNodeType.TABLE || node.type == DbNodeType.VIEW) {
                    openTableData(node.name);
                }
            }
        });

        createNavigatorContextMenu();
    }

    private void createNavigatorContextMenu() {
        MenuManager menuMgr = new MenuManager("#DbNavigatorPopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(manager -> {
            IStructuredSelection sel = (IStructuredSelection) dbTreeViewer.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof DbTreeNode node) {
                populateNavigatorContextMenu(manager, node);
            }
        });
        Menu menu = menuMgr.createContextMenu(dbTreeViewer.getControl());
        dbTreeViewer.getControl().setMenu(menu);
    }

    private void populateNavigatorContextMenu(IMenuManager manager, DbTreeNode node) {
        if (node.type == DbNodeType.TABLE || node.type == DbNodeType.VIEW) {
            manager.add(new Action("View Data (SELECT * LIMIT 100)") {
                @Override
                public void run() {
                    openTableData(node.name);
                }
            });
            manager.add(new Action("View Structure (PRAGMA table_info)") {
                @Override
                public void run() {
                    executeSqlAsync("PRAGMA table_info(" + node.name + ");");
                }
            });
            manager.add(new Separator());
            manager.add(new Action("Generate SELECT Statement") {
                @Override
                public void run() {
                    sqlEditor.setText("SELECT * FROM " + node.name + " LIMIT 100;");
                }
            });
            manager.add(new Action("Generate INSERT Template") {
                @Override
                public void run() {
                    generateInsertTemplate(node.name);
                }
            });
            manager.add(new Action("Generate UPDATE Template") {
                @Override
                public void run() {
                    sqlEditor.setText("UPDATE " + node.name + " SET column1 = value1 WHERE id = '...';");
                }
            });
            manager.add(new Action("Generate DELETE Template") {
                @Override
                public void run() {
                    sqlEditor.setText("DELETE FROM " + node.name + " WHERE id = '...';");
                }
            });
            manager.add(new Separator());
            manager.add(new Action("Export Table Data to CSV...") {
                @Override
                public void run() {
                    openTableData(node.name);
                    exportCurrentResultsToCsv();
                }
            });
            manager.add(new Action("Copy Table Name") {
                @Override
                public void run() {
                    copyToClipboard(node.name);
                }
            });
            manager.add(new Separator());
            manager.add(new Action("Drop Table (destructive)...") {
                @Override
                public void run() {
                    boolean confirm = MessageDialog.openConfirm(getControl().getShell(), "Confirm DROP TABLE",
                            "WARNING: Are you sure you want to DROP TABLE " + node.name + "? All data will be permanently deleted.");
                    if (confirm) {
                        executeSqlAsync("DROP TABLE " + node.name + ";");
                        refreshDatabaseNavigator();
                    }
                }
            });
        } else if (node.type == DbNodeType.COLUMN) {
            manager.add(new Action("Copy Column Name") {
                @Override
                public void run() {
                    copyToClipboard(node.name);
                }
            });
            manager.add(new Action("Generate SELECT by " + node.name) {
                @Override
                public void run() {
                    DbTreeNode parentTable = node.parent;
                    if (parentTable != null) {
                        sqlEditor.setText("SELECT * FROM " + parentTable.name + " WHERE " + node.name + " = '...';");
                    }
                }
            });
        }
    }

    private void generateInsertTemplate(String tableName) {
        SqliteDatabaseManager dbManager = TrystUiServices.getDatabaseManager();
        if (dbManager == null) return;
        try {
            Connection conn = dbManager.getConnection();
            List<String> cols = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ");")) {
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
            }
            if (!cols.isEmpty()) {
                String colList = String.join(", ", cols);
                String valList = String.join(", ", Collections.nCopies(cols.size(), "?"));
                sqlEditor.setText("INSERT INTO " + tableName + " (" + colList + ")\nVALUES (" + valList + ");");
            }
        } catch (Exception e) {
            sqlEditor.setText("INSERT INTO " + tableName + " VALUES (...);");
        }
    }

    // -------------------------------------------------------------
    // RIGHT TOP PANE: Results Area
    // -------------------------------------------------------------
    private void createRightTopResultsPane(Composite parent) {
        Group resGroup = new Group(parent, SWT.NONE);
        resGroup.setText("Query Results & Data Browser");
        resGroup.setLayout(new GridLayout(1, false));

        Composite topBar = new Composite(resGroup, SWT.NONE);
        topBar.setLayout(new GridLayout(4, false));
        topBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        lblResultStatus = new Label(topBar, SWT.NONE);
        lblResultStatus.setText("Ready | 0 rows returned");
        lblResultStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        btnCopyResult = new Button(topBar, SWT.PUSH);
        btnCopyResult.setText("📋 Copy Row");
        btnCopyResult.addListener(SWT.Selection, e -> copySelectedResultRows());

        btnExportCsv = new Button(topBar, SWT.PUSH);
        btnExportCsv.setText("💾 Export CSV...");
        btnExportCsv.addListener(SWT.Selection, e -> exportCurrentResultsToCsv());

        Button btnClearResults = new Button(topBar, SWT.PUSH);
        btnClearResults.setText("🗑 Clear Results");
        btnClearResults.addListener(SWT.Selection, e -> clearResults());

        resultTable = new Table(resGroup, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        resultTable.setHeaderVisible(true);
        resultTable.setLinesVisible(true);
        resultTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    private void clearResults() {
        resultTable.removeAll();
        for (TableColumn c : resultTable.getColumns()) {
            c.dispose();
        }
        lblResultStatus.setText("Cleared | 0 rows");
    }

    private void copySelectedResultRows() {
        TableItem[] sel = resultTable.getSelection();
        if (sel.length == 0) return;
        StringBuilder sb = new StringBuilder();
        int colCount = resultTable.getColumnCount();
        for (TableItem item : sel) {
            List<String> rowVals = new ArrayList<>();
            for (int i = 0; i < colCount; i++) {
                rowVals.add(item.getText(i));
            }
            sb.append(String.join("\t", rowVals)).append("\n");
        }
        copyToClipboard(sb.toString());
    }

    private void exportCurrentResultsToCsv() {
        FileDialog dialog = new FileDialog(getControl().getShell(), SWT.SAVE);
        dialog.setFilterNames(new String[]{"CSV Files (*.csv)", "All Files (*.*)"});
        dialog.setFilterExtensions(new String[]{"*.csv", "*.*"});
        dialog.setFileName("tryst-db-export-" + System.currentTimeMillis() + ".csv");
        String filePath = dialog.open();
        if (filePath != null) {
            try {
                StringBuilder sb = new StringBuilder();
                int colCount = resultTable.getColumnCount();
                List<String> headers = new ArrayList<>();
                for (int i = 0; i < colCount; i++) {
                    headers.add("\"" + resultTable.getColumn(i).getText().replace("\"", "\"\"") + "\"");
                }
                sb.append(String.join(",", headers)).append("\n");

                for (TableItem item : resultTable.getItems()) {
                    List<String> rowVals = new ArrayList<>();
                    for (int i = 0; i < colCount; i++) {
                        rowVals.add("\"" + item.getText(i).replace("\"", "\"\"") + "\"");
                    }
                    sb.append(String.join(",", rowVals)).append("\n");
                }

                Files.writeString(Path.of(filePath), sb.toString());
                MessageDialog.openInformation(getControl().getShell(), "CSV Export", "Exported successfully to:\n" + filePath);
            } catch (Exception ex) {
                MessageDialog.openError(getControl().getShell(), "Export Error", "Failed to export CSV: " + ex.getMessage());
            }
        }
    }

    // -------------------------------------------------------------
    // RIGHT BOTTOM PANE: SQL Editor
    // -------------------------------------------------------------
    private void createRightBottomSqlEditorPane(Composite parent) {
        Group editorGroup = new Group(parent, SWT.NONE);
        editorGroup.setText("SQL Editor (Ctrl+Enter / F5 to Execute)");
        editorGroup.setLayout(new GridLayout(1, false));

        // Toolbar
        Composite toolbar = new Composite(editorGroup, SWT.NONE);
        toolbar.setLayout(new GridLayout(8, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        btnExecute = new Button(toolbar, SWT.PUSH);
        btnExecute.setText("▶ Execute SQL (F5)");
        btnExecute.addListener(SWT.Selection, e -> onExecuteCurrent());

        btnExecuteSelected = new Button(toolbar, SWT.PUSH);
        btnExecuteSelected.setText("⚡ Execute Selection");
        btnExecuteSelected.addListener(SWT.Selection, e -> onExecuteSelected());

        btnCancel = new Button(toolbar, SWT.PUSH);
        btnCancel.setText("⏹ Cancel");
        btnCancel.setEnabled(false);
        btnCancel.addListener(SWT.Selection, e -> onCancelQuery());

        Button btnFormat = new Button(toolbar, SWT.PUSH);
        btnFormat.setText("✨ Format SQL");
        btnFormat.addListener(SWT.Selection, e -> formatSql());

        Button btnClearEditor = new Button(toolbar, SWT.PUSH);
        btnClearEditor.setText("🗑 Clear");
        btnClearEditor.addListener(SWT.Selection, e -> sqlEditor.setText(""));

        Button btnLoadScript = new Button(toolbar, SWT.PUSH);
        btnLoadScript.setText("📂 Open Script...");
        btnLoadScript.addListener(SWT.Selection, e -> loadSqlScript());

        Button btnSaveScript = new Button(toolbar, SWT.PUSH);
        btnSaveScript.setText("💾 Save Script...");
        btnSaveScript.addListener(SWT.Selection, e -> saveSqlScript());

        Label lblHist = new Label(toolbar, SWT.NONE);
        lblHist.setText("History:");

        queryHistoryCombo = new ComboViewer(toolbar, SWT.READ_ONLY);
        queryHistoryCombo.setContentProvider(ArrayContentProvider.getInstance());
        queryHistoryCombo.setLabelProvider(new LabelProvider());
        queryHistoryCombo.setInput(queryHistory);
        queryHistoryCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        queryHistoryCombo.addSelectionChangedListener(event -> {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();
            if (!sel.isEmpty() && sel.getFirstElement() instanceof String q) {
                sqlEditor.setText(q);
            }
        });

        sqlEditor = new StyledText(editorGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        sqlEditor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sqlEditor.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        sqlEditor.setText("SELECT * FROM tasks ORDER BY created_at DESC LIMIT 50;");

        sqlEditor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.F5 || ((e.stateMask & SWT.CTRL) != 0 && (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR))) {
                    onExecuteCurrent();
                    e.doit = false;
                }
            }
        });
    }

    private void formatSql() {
        String sql = sqlEditor.getText();
        if (sql == null || sql.isBlank()) return;
        String formatted = sql
                .replaceAll("(?i)\\bSELECT\\b", "SELECT")
                .replaceAll("(?i)\\bFROM\\b", "\nFROM")
                .replaceAll("(?i)\\bWHERE\\b", "\nWHERE")
                .replaceAll("(?i)\\bAND\\b", "\n  AND")
                .replaceAll("(?i)\\bOR\\b", "\n  OR")
                .replaceAll("(?i)\\bORDER BY\\b", "\nORDER BY")
                .replaceAll("(?i)\\bGROUP BY\\b", "\nGROUP BY")
                .replaceAll("(?i)\\bLIMIT\\b", "\nLIMIT")
                .replaceAll("(?i)\\bINSERT INTO\\b", "INSERT INTO")
                .replaceAll("(?i)\\bVALUES\\b", "\nVALUES")
                .replaceAll("(?i)\\bUPDATE\\b", "UPDATE")
                .replaceAll("(?i)\\bSET\\b", "\nSET")
                .replaceAll("(?i)\\bDELETE FROM\\b", "DELETE FROM");
        sqlEditor.setText(formatted.trim());
    }

    private void loadSqlScript() {
        FileDialog dialog = new FileDialog(getControl().getShell(), SWT.OPEN);
        dialog.setFilterNames(new String[]{"SQL Scripts (*.sql)", "All Files (*.*)"});
        dialog.setFilterExtensions(new String[]{"*.sql", "*.*"});
        String path = dialog.open();
        if (path != null) {
            try {
                sqlEditor.setText(Files.readString(Path.of(path)));
            } catch (IOException e) {
                MessageDialog.openError(getControl().getShell(), "Error", "Could not read file: " + e.getMessage());
            }
        }
    }

    private void saveSqlScript() {
        FileDialog dialog = new FileDialog(getControl().getShell(), SWT.SAVE);
        dialog.setFilterNames(new String[]{"SQL Scripts (*.sql)", "All Files (*.*)"});
        dialog.setFilterExtensions(new String[]{"*.sql", "*.*"});
        dialog.setFileName("query.sql");
        String path = dialog.open();
        if (path != null) {
            try {
                Files.writeString(Path.of(path), sqlEditor.getText());
                MessageDialog.openInformation(getControl().getShell(), "Saved", "Saved script to:\n" + path);
            } catch (IOException e) {
                MessageDialog.openError(getControl().getShell(), "Error", "Could not write file: " + e.getMessage());
            }
        }
    }

    private void onExecuteCurrent() {
        String sql = sqlEditor.getText();
        if (sql == null || sql.isBlank()) return;
        executeSqlAsync(sql.trim());
    }

    private void onExecuteSelected() {
        String sel = sqlEditor.getSelectionText();
        if (sel != null && !sel.isBlank()) {
            executeSqlAsync(sel.trim());
        } else {
            onExecuteCurrent();
        }
    }

    private void onCancelQuery() {
        try {
            if (activeStatement != null && !activeStatement.isClosed()) {
                activeStatement.cancel();
                lblResultStatus.setText("Query cancellation requested...");
            }
        } catch (Exception ex) {
            TrystLog.warn(DatabasePage.class, "Failed to cancel statement: " + ex.getMessage());
        }
    }

    public void openTableData(String tableName) {
        String sql = "SELECT * FROM " + tableName + " LIMIT 100;";
        sqlEditor.setText(sql);
        executeSqlAsync(sql);
    }

    public void executeSqlAsync(String sql) {
        // Safeguards: DROP TABLE, or DELETE without WHERE
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("DROP ") || upper.startsWith("TRUNCATE ")) {
            boolean confirm = MessageDialog.openConfirm(getControl().getShell(), "Confirm DDL Operation",
                    "You are executing a destructive DDL operation:\n" + sql + "\n\nDo you want to proceed?");
            if (!confirm) return;
        } else if (upper.startsWith("DELETE") && !upper.contains("WHERE")) {
            boolean confirm = MessageDialog.openConfirm(getControl().getShell(), "Confirm Unconditional DELETE",
                    "WARNING: DELETE without WHERE clause will delete all rows from the table!\n\nDo you want to proceed?");
            if (!confirm) return;
        }

        // Add to query history
        if (!queryHistory.contains(sql)) {
            queryHistory.add(0, sql);
            if (queryHistory.size() > 20) queryHistory.remove(queryHistory.size() - 1);
            if (queryHistoryCombo != null && !queryHistoryCombo.getControl().isDisposed()) {
                queryHistoryCombo.setInput(queryHistory);
            }
        }

        executingQuery.set(true);
        btnExecute.setEnabled(false);
        btnExecuteSelected.setEnabled(false);
        btnCancel.setEnabled(true);
        lblResultStatus.setText("⏳ Executing query...");

        long startTime = System.currentTimeMillis();
        AsyncExecutionService async = TrystUiServices.getAsyncExecutionService();

        Runnable task = () -> {
            SqliteDatabaseManager dbManager = TrystUiServices.getDatabaseManager();
            if (dbManager == null) {
                renderQueryResult(null, Collections.emptyList(), 0, "Error: DatabaseManager is unavailable", -1);
                return;
            }

            try {
                Connection conn = dbManager.getConnection();
                try (Statement stmt = conn.createStatement()) {
                    activeStatement = stmt;
                    boolean hasResultSet = stmt.execute(sql);
                    long duration = System.currentTimeMillis() - startTime;

                    if (hasResultSet) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            List<String> headers = new ArrayList<>();
                            for (int i = 1; i <= colCount; i++) {
                                headers.add(meta.getColumnLabel(i));
                            }
                            List<List<String>> rows = new ArrayList<>();
                            while (rs.next()) {
                                List<String> r = new ArrayList<>();
                                for (int i = 1; i <= colCount; i++) {
                                    Object val = rs.getObject(i);
                                    r.add(val == null ? "<NULL>" : String.valueOf(val));
                                }
                                rows.add(r);
                            }
                            renderQueryResult(headers, rows, rows.size(), "Success", duration);
                        }
                    } else {
                        int updateCount = stmt.getUpdateCount();
                        renderQueryResult(List.of("Result"), List.of(List.of("Rows affected: " + updateCount)),
                                updateCount, "Statement executed successfully (" + updateCount + " rows affected)", duration);
                    }
                }
            } catch (Exception ex) {
                long duration = System.currentTimeMillis() - startTime;
                renderQueryResult(null, Collections.emptyList(), 0, "Query Error: " + ex.getMessage(), duration);
            } finally {
                activeStatement = null;
                executingQuery.set(false);
            }
        };

        if (async != null) {
            async.runAsync(task);
        } else {
            Thread.ofVirtual().start(task);
        }
    }

    private void renderQueryResult(List<String> headers, List<List<String>> rows, int count, String statusMessage, long durationMs) {
        Display display = getControl().getDisplay();
        if (display == null || display.isDisposed()) return;

        display.asyncExec(() -> {
            if (resultTable.isDisposed()) return;
            btnExecute.setEnabled(true);
            btnExecuteSelected.setEnabled(true);
            btnCancel.setEnabled(false);

            resultTable.setRedraw(false);
            try {
                resultTable.removeAll();
                for (TableColumn c : resultTable.getColumns()) {
                    c.dispose();
                }

                if (headers != null && !headers.isEmpty()) {
                    for (String h : headers) {
                        TableColumn col = new TableColumn(resultTable, SWT.LEFT);
                        col.setText(h);
                        col.setWidth(120);
                    }
                    for (List<String> r : rows) {
                        TableItem item = new TableItem(resultTable, SWT.NONE);
                        item.setText(r.toArray(new String[0]));
                    }
                    lblResultStatus.setText("✅ " + statusMessage + " | " + count + " rows (" + durationMs + " ms)");
                } else {
                    lblResultStatus.setText("❌ " + statusMessage + " (" + durationMs + " ms)");
                }
            } finally {
                resultTable.setRedraw(true);
            }
        });
    }

    public void refreshDatabaseNavigator() {
        AsyncExecutionService async = TrystUiServices.getAsyncExecutionService();
        Runnable task = () -> {
            List<DbTreeNode> roots = loadSchemaModel();
            Display display = dbTreeViewer != null && !dbTreeViewer.getControl().isDisposed() ?
                    dbTreeViewer.getControl().getDisplay() : null;
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> {
                    if (dbTreeViewer != null && !dbTreeViewer.getControl().isDisposed()) {
                        dbTreeViewer.setInput(roots);
                        dbTreeViewer.expandToLevel(2);
                    }
                });
            }
        };

        if (async != null) {
            async.runAsync(task);
        } else {
            Thread.ofVirtual().start(task);
        }
    }

    private List<DbTreeNode> loadSchemaModel() {
        List<DbTreeNode> roots = new ArrayList<>();
        SqliteDatabaseManager dbManager = TrystUiServices.getDatabaseManager();
        if (dbManager == null) return roots;

        DbTreeNode rootConn = new DbTreeNode("tryst-sqlite", "TRYST SQLite Database (" +
                (dbManager.getDatabasePath() != null ? dbManager.getDatabasePath().getFileName() : "local") + ")",
                DbNodeType.CONNECTION, null);

        DbTreeNode tablesCat = new DbTreeNode("cat-tables", "Tables", DbNodeType.CATEGORY, rootConn);
        DbTreeNode viewsCat = new DbTreeNode("cat-views", "Views", DbNodeType.CATEGORY, rootConn);

        List<DbTreeNode> tableNodes = new ArrayList<>();
        List<DbTreeNode> viewNodes = new ArrayList<>();

        try {
            Connection conn = dbManager.getConnection();
            if (conn != null && !conn.isClosed()) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' ORDER BY name;")) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        String type = rs.getString("type");
                        DbNodeType nt = "view".equalsIgnoreCase(type) ? DbNodeType.VIEW : DbNodeType.TABLE;
                        DbTreeNode tblNode = new DbTreeNode("tbl-" + name, name, nt, "view".equalsIgnoreCase(type) ? viewsCat : tablesCat);

                        // Load columns & indexes
                        List<DbTreeNode> colNodes = new ArrayList<>();
                        try (Statement colStmt = conn.createStatement();
                             ResultSet colRs = colStmt.executeQuery("PRAGMA table_info(" + name + ");")) {
                            while (colRs.next()) {
                                String colName = colRs.getString("name");
                                String colType = colRs.getString("type");
                                boolean isPk = colRs.getInt("pk") > 0;
                                String label = colName + " : " + colType + (isPk ? " [PK]" : "");
                                colNodes.add(new DbTreeNode("col-" + name + "-" + colName, label, DbNodeType.COLUMN, tblNode));
                            }
                        } catch (Exception ignored) {}

                        // Indexes
                        try (Statement idxStmt = conn.createStatement();
                             ResultSet idxRs = idxStmt.executeQuery("PRAGMA index_list(" + name + ");")) {
                            while (idxRs.next()) {
                                String idxName = idxRs.getString("name");
                                boolean unique = idxRs.getInt("unique") == 1;
                                colNodes.add(new DbTreeNode("idx-" + name + "-" + idxName, idxName + (unique ? " [UNIQUE]" : " [INDEX]"), DbNodeType.INDEX, tblNode));
                            }
                        } catch (Exception ignored) {}

                        tblNode.children = colNodes;
                        if (nt == DbNodeType.VIEW) {
                            viewNodes.add(tblNode);
                        } else {
                            tableNodes.add(tblNode);
                        }
                    }
                }
            }
        } catch (Exception e) {
            TrystLog.warn(DatabasePage.class, "Failed to load database schema: " + e.getMessage());
        }

        tablesCat.children = tableNodes;
        viewsCat.children = viewNodes;
        rootConn.children = List.of(tablesCat, viewsCat);
        roots.add(rootConn);
        return roots;
    }

    private void copyToClipboard(String text) {
        if (text == null) return;
        Clipboard cb = new Clipboard(getControl().getDisplay());
        try {
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        super.onContextChanged(context);
    }

    @Override
    public void refresh() {
        super.refresh();
        refreshDatabaseNavigator();
    }

    // -------------------------------------------------------------
    // Inner Schema Tree Classes
    // -------------------------------------------------------------
    public enum DbNodeType {
        CONNECTION, CATEGORY, TABLE, VIEW, COLUMN, INDEX
    }

    public static class DbTreeNode {
        public final String id;
        public final String name;
        public final DbNodeType type;
        public final DbTreeNode parent;
        public List<DbTreeNode> children = Collections.emptyList();

        public DbTreeNode(String id, String name, DbNodeType type, DbTreeNode parent) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.parent = parent;
        }
    }

    private static class DbTreeContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement instanceof List<?> l) return l.toArray();
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            if (parentElement instanceof DbTreeNode node) return node.children.toArray();
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            if (element instanceof DbTreeNode node) return node.parent;
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            if (element instanceof DbTreeNode node) return !node.children.isEmpty();
            return false;
        }

        @Override
        public void dispose() {}

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {}
    }

    private static class DbTreeLabelProvider extends LabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof DbTreeNode node) {
                String prefix = switch (node.type) {
                    case CONNECTION -> "🗄️ ";
                    case CATEGORY -> "📁 ";
                    case TABLE -> "📊 ";
                    case VIEW -> "👁️ ";
                    case COLUMN -> "🔹 ";
                    case INDEX -> "🔑 ";
                };
                return prefix + node.name;
            }
            return super.getText(element);
        }
    }
}
