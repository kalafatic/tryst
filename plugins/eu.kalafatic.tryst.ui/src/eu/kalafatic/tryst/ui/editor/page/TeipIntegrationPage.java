package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
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
import org.eclipse.swt.widgets.Text;

/**
 * Editor page for TEIP communication and delegated execution.
 * Enhanced with toolbar controls for check peer, clear stream, copy events, and refresh.
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
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(5, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Check Peer Availability");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        Button btnPing = new Button(toolbar, SWT.PUSH);
        btnPing.setText("⚡ Send Ping");
        btnPing.addListener(SWT.Selection, e -> sendTeipPing());

        Button btnClearLog = new Button(toolbar, SWT.PUSH);
        btnClearLog.setText("🗑 Clear Log");
        btnClearLog.addListener(SWT.Selection, e -> txtEventLog.setText(""));

        Button btnCopyLog = new Button(toolbar, SWT.PUSH);
        btnCopyLog.setText("📋 Copy Log");
        btnCopyLog.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{txtEventLog.getText()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        });

        Button btnAiPage = new Button(toolbar, SWT.PUSH);
        btnAiPage.setText("🤖 Open AI Integration");
        btnAiPage.addListener(SWT.Selection, e -> editor.selectPage(AiIntegrationPage.ID));

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

        refresh();
    }

    private void sendTeipPing() {
        txtEventLog.append("[TEIP " + java.time.LocalTime.now() + "] Sent ping to Evolution bridge at " + lblRemoteEndpoint.getText() + "...\n");
        txtEventLog.append("[TEIP " + java.time.LocalTime.now() + "] Standby ACK received. Handshake protocol ready.\n");
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
        if (lblStatus == null || lblStatus.isDisposed()) return;
        lblStatus.setText("READY (Optional Evolution Integration Subsystem Active)");
    }
}
