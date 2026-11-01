package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.ui.TrystUiPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

/**
 * Native SWT/JFace View for AI Model & Inference Monitoring.
 */
public class AiMonitoringView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.aiMonitoring";

    private Label lblEndpoint;
    private Label lblStatus;
    private Label lblModel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        createLabel(parent, "AI Inference Service:");
        lblEndpoint = createValueLabel(parent);

        createLabel(parent, "Connection Status:");
        lblStatus = createValueLabel(parent);

        createLabel(parent, "Active Model:");
        lblModel = createValueLabel(parent);

        Button btnCheck = new Button(parent, SWT.PUSH);
        btnCheck.setText("Test AI Endpoint Connection");
        btnCheck.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        btnCheck.addListener(SWT.Selection, e -> checkConnection());

        checkConnection();
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

    private void checkConnection() {
        AiInferenceService ai = TrystUiPlugin.getDefault().getAiInferenceService();
        if (ai != null) {
            lblEndpoint.setText("Configured (Ollama)");
            lblModel.setText("codellama");
            ai.checkConnection().thenAccept(online -> {
                Display.getDefault().asyncExec(() -> {
                    if (!lblStatus.isDisposed()) {
                        lblStatus.setText(online ? "ONLINE" : "OFFLINE / UNREACHABLE");
                    }
                });
            });
        } else {
            lblStatus.setText("AiInferenceService unavailable");
        }
    }

    @Override
    public void setFocus() {
        if (lblEndpoint != null) lblEndpoint.setFocus();
    }
}
