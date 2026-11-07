package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * Editor page for TEIP communication and delegated execution.
 */
public class TeipIntegrationPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.teip";

    private Label lblProtocolVersion;
    private Label lblRemoteEndpoint;
    private Label lblStatus;
    private Text txtEventLog;

    public TeipIntegrationPage(TrystWorkbenchEditor editor) {
        super(ID, "TEIP Integration", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group configGroup = new Group(parent, SWT.NONE);
        configGroup.setText("TEIP 1.0 Specification & Configuration");
        configGroup.setLayout(new GridLayout(2, false));
        configGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(configGroup, "Protocol Version:");
        lblProtocolVersion = createValueLabel(configGroup, "TEIP 1.0 (Normative Spec)");

        createRow(configGroup, "EVO Remote Peer:");
        lblRemoteEndpoint = createValueLabel(configGroup, "http://localhost:8500 (Evolution Bridge)");

        createRow(configGroup, "Connection State:");
        lblStatus = createValueLabel(configGroup, "DISCONNECTED / STANDBY");

        Group logGroup = new Group(parent, SWT.NONE);
        logGroup.setText("TEIP Communication Stream & Cursor Events");
        logGroup.setLayout(new GridLayout(1, false));
        logGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        txtEventLog = new Text(logGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        txtEventLog.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        txtEventLog.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        txtEventLog.setText("[TEIP] Standby for remote EVO task delegation requests...\n");

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Check Peer Availability");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        refresh();
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
        if (lblStatus == null || lblStatus.isDisposed()) return;
        lblStatus.setText("READY (Optional Evolution Integration Subsystem Active)");
    }
}
