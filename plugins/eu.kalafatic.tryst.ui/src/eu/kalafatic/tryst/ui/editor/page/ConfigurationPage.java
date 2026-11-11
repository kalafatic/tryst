package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

import java.nio.file.Files;

/**
 * Editor page for Configuration, Port Mappings, and Database Administration.
 * Enhanced with toolbar controls for copying database info, compacting database, and server navigation.
 */
public class ConfigurationPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.configuration";

    private Label lblHttpPort;
    private Label lblApiPort;
    private Label lblDbPath;
    private Label lblDbSize;
    private Label lblDbSchema;

    public ConfigurationPage(TrystWorkbenchEditor editor) {
        super(ID, "Configuration", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(5, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Configuration Info");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnCopyDb = new Button(toolbar, SWT.PUSH);
        btnCopyDb.setText("📋 Copy Database Path");
        btnCopyDb.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{lblDbPath.getText()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        });

        Button btnCompact = new Button(toolbar, SWT.PUSH);
        btnCompact.setText("🗜 Vacuum / Compact DB");
        btnCompact.addListener(SWT.Selection, e -> onCompactDatabase());

        Button btnServerPage = new Button(toolbar, SWT.PUSH);
        btnServerPage.setText("📊 Server Monitoring");
        btnServerPage.addListener(SWT.Selection, e -> editor.selectPage(ServerMonitoringPage.ID));

        Group portGroup = new Group(parent, SWT.NONE);
        portGroup.setText("Network Endpoints & Port Offsets (+30000 Base)");
        portGroup.setLayout(new GridLayout(2, false));
        portGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(portGroup, "HTTP Listener Port:");
        lblHttpPort = createValueLabel(portGroup, "38080");

        createRow(portGroup, "API Listener Port:");
        lblApiPort = createValueLabel(portGroup, "38180");

        Group dbGroup = new Group(parent, SWT.NONE);
        dbGroup.setText("Database Maintenance & Schema");
        dbGroup.setLayout(new GridLayout(2, false));
        dbGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(dbGroup, "SQLite Database Path:");
        lblDbPath = createValueLabel(dbGroup, "-");

        createRow(dbGroup, "Database Size:");
        lblDbSize = createValueLabel(dbGroup, "-");

        createRow(dbGroup, "Migration Version:");
        lblDbSchema = createValueLabel(dbGroup, "V14 (Workflow & Scheduling Persistent)");

        refresh();
    }

    private void onCompactDatabase() {
        SqliteDatabaseManager db = TrystUiServices.getDatabaseManager();
        if (db != null) {
            try {
                try (var stmt = db.getConnection().createStatement()) {
                    stmt.execute("VACUUM;");
                }
                MessageDialog.openInformation(getControl().getShell(), "Database Compaction", "SQLite VACUUM operation completed successfully.");
                refresh();
            } catch (Exception ex) {
                MessageDialog.openError(getControl().getShell(), "Compaction Error", "Failed to vacuum database: " + ex.getMessage());
            }
        }
    }

    private void createRow(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (lblDbPath == null || lblDbPath.isDisposed()) return;

        ApplicationConfiguration config = TrystUiServices.getConfiguration();
        if (config != null) {
            var http = TrystUiServices.getHttpService();
            int port = (http != null && http.isRunning()) ? http.getPort() : config.httpPort();
            lblHttpPort.setText(String.valueOf(port));
            lblApiPort.setText(String.valueOf(config.tcpPort()));
        }

        SqliteDatabaseManager db = TrystUiServices.getDatabaseManager();
        if (db != null && db.getDatabasePath() != null) {
            lblDbPath.setText(db.getDatabasePath().toString());
            try {
                long size = Files.size(db.getDatabasePath());
                lblDbSize.setText(size + " bytes (" + (size / 1024) + " KB)");
            } catch (Exception ignored) {
                lblDbSize.setText("Unknown");
            }
        }
    }
}
