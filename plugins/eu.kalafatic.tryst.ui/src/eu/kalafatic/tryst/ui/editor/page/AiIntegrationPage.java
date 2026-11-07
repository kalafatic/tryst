package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.service.AiMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/**
 * Editor page for AI Infrastructure, LLM provider connectivity, and Darwin reasoning models.
 */
public class AiIntegrationPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.aiIntegration";

    private Label lblProvider;
    private Label lblModel;
    private Label lblStatus;
    private Label lblRequests;

    public AiIntegrationPage(TrystWorkbenchEditor editor) {
        super(ID, "AI / Darwin", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group infoGroup = new Group(parent, SWT.NONE);
        infoGroup.setText("AI Provider & Reasoning Engine");
        infoGroup.setLayout(new GridLayout(2, false));
        infoGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(infoGroup, "Configured Provider:");
        lblProvider = createValueLabel(infoGroup, "Ollama / Local LLM");

        createRow(infoGroup, "Active Model:");
        lblModel = createValueLabel(infoGroup, "codellama");

        createRow(infoGroup, "Endpoint Status:");
        lblStatus = createValueLabel(infoGroup, "Checking...");

        createRow(infoGroup, "Inference Requests Processed:");
        lblRequests = createValueLabel(infoGroup, "0");

        Composite btnBar = new Composite(parent, SWT.NONE);
        btnBar.setLayout(new GridLayout(2, false));

        Button btnCheck = new Button(btnBar, SWT.PUSH);
        btnCheck.setText("Test AI Connectivity");
        btnCheck.addListener(SWT.Selection, e -> checkAiConnection());

        Button btnRefresh = new Button(btnBar, SWT.PUSH);
        btnRefresh.setText("Refresh");
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

    private void checkAiConnection() {
        AiInferenceService ai = TrystUiServices.getAiInferenceService();
        if (ai != null) {
            lblStatus.setText("Pinging AI endpoint...");
            ai.checkConnection().thenAccept(online -> {
                Display.getDefault().asyncExec(() -> {
                    if (!lblStatus.isDisposed()) {
                        lblStatus.setText(online ? "ONLINE (Active)" : "OFFLINE / UNREACHABLE");
                    }
                });
            });
        } else {
            lblStatus.setText("AiInferenceService unavailable in registry");
        }
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void refresh() {
        if (lblProvider == null || lblProvider.isDisposed()) return;
        AiMonitoringService mon = TrystUiServices.getAiMonitoringService();
        if (mon != null) {
            var health = mon.checkHealth();
            if (health != null) {
                lblRequests.setText(String.valueOf(health.totalRequests()));
            }
        }
        checkAiConnection();
    }
}
