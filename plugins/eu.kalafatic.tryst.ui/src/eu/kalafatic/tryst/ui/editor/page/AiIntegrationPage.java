package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.service.AiMonitoringService;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.editor.AbstractTrystEditorPage;
import eu.kalafatic.tryst.ui.editor.TrystWorkbenchEditor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.util.List;

/**
 * Editor page for AI Infrastructure, LLM provider connectivity, and Darwin reasoning models.
 * Enhanced with toolbar controls for testing, start/stop, configuring endpoint, copying model info, and metrics.
 */
public class AiIntegrationPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.aiIntegration";

    private Label lblProvider;
    private Label lblEndpoint;
    private Combo cmbModel;
    private Label lblStatus;
    private Label lblRequests;
    private Button btnStartAi;
    private Button btnStopAi;

    public AiIntegrationPage(TrystWorkbenchEditor editor) {
        super(ID, "AI Integration", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(6, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnCheck = new Button(toolbar, SWT.PUSH);
        btnCheck.setText("⚡ Test AI Connectivity");
        btnCheck.addListener(SWT.Selection, e -> testAiConnectivity());

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Models");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        btnStartAi = new Button(toolbar, SWT.PUSH);
        btnStartAi.setText("▶ Start AI Service");
        btnStartAi.addListener(SWT.Selection, e -> startAiService());

        btnStopAi = new Button(toolbar, SWT.PUSH);
        btnStopAi.setText("⏹ Stop AI Service");
        btnStopAi.addListener(SWT.Selection, e -> stopAiService());

        Button btnConfigure = new Button(toolbar, SWT.PUSH);
        btnConfigure.setText("⚙ Configure Endpoint...");
        btnConfigure.addListener(SWT.Selection, e -> onConfigureEndpoint());

        Button btnCopyInfo = new Button(toolbar, SWT.PUSH);
        btnCopyInfo.setText("📋 Copy Model Info");
        btnCopyInfo.addListener(SWT.Selection, e -> copyModelInfo());

        Group infoGroup = new Group(parent, SWT.NONE);
        infoGroup.setText("AI Provider & Reasoning Engine");
        infoGroup.setLayout(new GridLayout(2, false));
        infoGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(infoGroup, "Configured Provider:");
        lblProvider = createValueLabel(infoGroup, "Ollama / Local LLM");

        createRow(infoGroup, "Endpoint:");
        lblEndpoint = createValueLabel(infoGroup, "-");

        createRow(infoGroup, "Active Model:");
        cmbModel = new Combo(infoGroup, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        cmbModel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cmbModel.addListener(SWT.Selection, e -> onModelSelected());

        createRow(infoGroup, "Endpoint Status:");
        lblStatus = createValueLabel(infoGroup, "Checking...");

        createRow(infoGroup, "Inference Requests Processed:");
        lblRequests = createValueLabel(infoGroup, "0");

        refresh();
    }

    private void onConfigureEndpoint() {
        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
        String currentEndpoint = cfg != null ? cfg.ollamaEndpoint() : "http://localhost:11434";
        InputDialog dialog = new InputDialog(resolveShell(), "Configure AI Endpoint", "Ollama REST Endpoint URL:", currentEndpoint, null);
        if (dialog.open() == Window.OK && dialog.getValue() != null && !dialog.getValue().isBlank()) {
            ConfigurationService cfgSvc = TrystUiServices.getConfigurationService();
            if (cfgSvc != null && cfg != null) {
                cfgSvc.updateConfiguration(cfg.withOllamaEndpoint(dialog.getValue().trim()));
                refresh();
            }
        }
    }

    private void copyModelInfo() {
        Clipboard cb = new Clipboard(getControl().getDisplay());
        try {
            String text = "AI Provider: " + lblProvider.getText() + "\nEndpoint: " + lblEndpoint.getText() + "\nModel: " + cmbModel.getText() + "\nStatus: " + lblStatus.getText();
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
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

    private void onModelSelected() {
        int idx = cmbModel.getSelectionIndex();
        if (idx >= 0) {
            String selected = cmbModel.getItem(idx);
            ConfigurationService cfgSvc = TrystUiServices.getConfigurationService();
            ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
            if (cfgSvc != null && cfg != null) {
                ApplicationConfiguration updated = cfg.withDefaultModel(selected);
                cfgSvc.updateConfiguration(updated);
            }
            AiModelRegistryService reg = TrystUiServices.getAiModelRegistryService();
            if (reg != null) {
                reg.setDefaultModel(selected);
            }
            AiMonitoringService mon = TrystUiServices.getAiMonitoringService();
            if (mon != null) {
                mon.setActiveModel(selected);
            }
        }
    }

    private Shell resolveShell() {
        if (getControl() != null && !getControl().isDisposed()) {
            return getControl().getShell();
        }
        return Display.getDefault().getActiveShell();
    }

    private void testAiConnectivity() {
        AiInferenceService ai = TrystUiServices.getAiInferenceService();
        if (ai == null) {
            lblStatus.setText("AiInferenceService unavailable in registry");
            Shell shell = resolveShell();
            if (shell != null) {
                MessageDialog.openWarning(shell, "AI Connectivity Test", "AI Inference Service is not running or unavailable.");
            }
            return;
        }

        lblStatus.setText("Testing connectivity with inference request...");
        String model = cmbModel.getText();
        if (model == null || model.isBlank()) {
            model = "codellama";
        }

        AiInferenceService.InferenceRequest req = new AiInferenceService.InferenceRequest(
                model,
                List.of(
                        AiInferenceService.ChatMessage.system("You are a connectivity test helper."),
                        AiInferenceService.ChatMessage.user("Ping")
                )
        );

        ai.generateResponse(req).thenAccept(resp -> {
            Display.getDefault().asyncExec(() -> {
                if (!lblStatus.isDisposed()) {
                    lblStatus.setText("ONLINE (Active)");
                    AiMonitoringService mon = TrystUiServices.getAiMonitoringService();
                    if (mon != null) {
                        mon.recordInference(true, resp.durationMs(), resp.evalCount(), null);
                        lblRequests.setText(String.valueOf(mon.checkHealth().totalRequests()));
                    }
                    Shell shell = resolveShell();
                    if (shell != null) {
                        MessageDialog.openInformation(shell, "AI Connectivity Test",
                                "Inference test succeeded via model '" + req.model() + "':\n\n" + resp.content());
                    }
                }
            });
        }).exceptionally(t -> {
            Display.getDefault().asyncExec(() -> {
                if (!lblStatus.isDisposed()) {
                    lblStatus.setText("OFFLINE / UNREACHABLE");
                    AiMonitoringService mon = TrystUiServices.getAiMonitoringService();
                    if (mon != null) {
                        mon.recordInference(false, 0, 0, t.getMessage());
                        lblRequests.setText(String.valueOf(mon.checkHealth().totalRequests()));
                    }
                    Shell shell = resolveShell();
                    if (shell != null) {
                        MessageDialog.openError(shell, "AI Connectivity Test",
                                "Inference test failed: " + t.getMessage());
                    }
                }
            });
            return null;
        });
    }

    private void startAiService() {
        var coord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (coord != null) {
            coord.startAiService();
        }
        refresh();
    }

    private void stopAiService() {
        var coord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        if (coord != null) {
            coord.stopAiService();
        }
        refresh();
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

            // Populate installed models
            ai.listModels().thenAccept(models -> {
                Display.getDefault().asyncExec(() -> {
                    if (cmbModel == null || cmbModel.isDisposed()) return;
                    String current = cmbModel.getText();
                    if (current == null || current.isBlank()) {
                        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
                        current = (cfg != null) ? cfg.defaultModel() : "codellama";
                    }
                    cmbModel.removeAll();
                    if (models != null && !models.isEmpty()) {
                        for (AiInferenceService.ModelMetadata m : models) {
                            cmbModel.add(m.name());
                        }
                        int selectIdx = -1;
                        for (int i = 0; i < cmbModel.getItemCount(); i++) {
                            if (cmbModel.getItem(i).equalsIgnoreCase(current) || cmbModel.getItem(i).startsWith(current + ":")) {
                                selectIdx = i;
                                break;
                            }
                        }
                        if (selectIdx >= 0) {
                            cmbModel.select(selectIdx);
                        } else if (cmbModel.getItemCount() > 0) {
                            cmbModel.select(0);
                        }
                    } else if (current != null && !current.isBlank()) {
                        cmbModel.add(current);
                        cmbModel.select(0);
                    }
                });
            }).exceptionally(t -> {
                Display.getDefault().asyncExec(() -> {
                    if (cmbModel == null || cmbModel.isDisposed()) return;
                    if (cmbModel.getItemCount() == 0) {
                        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
                        String fallback = (cfg != null) ? cfg.defaultModel() : "codellama";
                        cmbModel.add(fallback);
                        cmbModel.select(0);
                    }
                });
                return null;
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
        super.refresh();
        if (lblProvider == null || lblProvider.isDisposed()) return;

        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
        if (cfg != null && lblEndpoint != null && !lblEndpoint.isDisposed()) {
            lblEndpoint.setText(cfg.ollamaEndpoint());
        }

        AiMonitoringService mon = TrystUiServices.getAiMonitoringService();
        if (mon != null) {
            var health = mon.checkHealth();
            if (health != null) {
                lblRequests.setText(String.valueOf(health.totalRequests()));
            }
        }

        var coord = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
        boolean running = (coord != null) ? coord.isAiServiceRunning() : (TrystUiServices.getAiInferenceService() != null);
        if (btnStartAi != null && !btnStartAi.isDisposed()) {
            btnStartAi.setEnabled(!running);
        }
        if (btnStopAi != null && !btnStopAi.isDisposed()) {
            btnStopAi.setEnabled(running);
        }

        checkAiConnection();
    }
}
