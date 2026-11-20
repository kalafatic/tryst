package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.ui.TrystUiServices;
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
        // Resolve the live default provider/model truth: the default selection in the
        // AiProviderRegistry, the persisted GLOBAL execution configuration, and finally
        // the runtime AI inference service. Show "—" when nothing is configured.
        String providerText = null;
        String modelText = null;

        eu.kalafatic.tryst.domain.ai.AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        if (registry != null) {
            var defaultId = registry.getDefaultProviderId();
            if (defaultId.isPresent()) {
                var descriptor = registry.findProvider(defaultId.get());
                if (descriptor.isPresent()) {
                    providerText = descriptor.get().name();
                    if (descriptor.get().endpoint() != null && !descriptor.get().endpoint().isBlank()) {
                        providerText = providerText + " (" + descriptor.get().endpoint() + ")";
                    }
                    modelText = descriptor.get().defaultModel();
                }
            }
        }

        if (modelText == null || modelText.isBlank()) {
            var execConfig = TrystUiServices.getExecutionConfigurationService();
            if (execConfig != null) {
                var global = execConfig.getGlobal();
                if (global.isPresent() && global.get().modelId() != null && !global.get().modelId().isBlank()) {
                    modelText = global.get().modelId();
                }
            }
        }

        AiInferenceService ai = TrystUiServices.getAiInferenceService();
        if (providerText == null || providerText.isBlank()) {
            if (ai instanceof eu.kalafatic.tryst.domain.ai.AIProvider provider) {
                providerText = provider.getProviderId();
            } else if (ai != null) {
                providerText = ai.getClass().getSimpleName();
            }
        }

        lblEndpoint.setText(providerText != null && !providerText.isBlank() ? providerText : "—");
        lblModel.setText(modelText != null && !modelText.isBlank() ? modelText : "—");

        if (ai != null) {
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
