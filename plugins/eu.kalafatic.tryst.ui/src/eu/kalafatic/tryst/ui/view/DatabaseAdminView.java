package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.nio.file.Files;

/**
 * Native SWT/JFace View for Database Administration.
 */
public class DatabaseAdminView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.databaseAdmin";

    private Label lblPath;
    private Label lblSize;
    private Label lblSchema;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        createLabel(parent, "Database Location:");
        lblPath = createValueLabel(parent);

        createLabel(parent, "Database File Size:");
        lblSize = createValueLabel(parent);

        createLabel(parent, "Schema Migration Version:");
        lblSchema = createValueLabel(parent);

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Database Info");
        btnRefresh.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        btnRefresh.addListener(SWT.Selection, e -> refreshData());

        refreshData();
    }

    private void createLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    }

    private Label createValueLabel(Composite parent) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText("-");
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void refreshData() {
        SqliteDatabaseManager db = TrystUiServices.getDatabaseManager();
        if (db != null && db.getDatabasePath() != null) {
            lblPath.setText(db.getDatabasePath().toString());
            try {
                long size = Files.size(db.getDatabasePath());
                lblSize.setText(size + " bytes (" + (size / 1024) + " KB)");
            } catch (Exception ignored) {}
            lblSchema.setText("V3 (Production Platform)");
        }
    }

    @Override
    public void setFocus() {
        if (lblPath != null) lblPath.setFocus();
    }
}
