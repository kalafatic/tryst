package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.domain.ai.AgentProfileSupport;
import eu.kalafatic.tryst.domain.ai.AiInferenceService;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.AgentConfiguration;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
import eu.kalafatic.tryst.domain.repository.TaskRepository;
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
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TRYST AI Environment page: unified multi-harness / multi-provider / multi-agent
 * execution configuration (Phase 9). Replaces the former Ollama-only AI Integration
 * experience while preserving every existing operation (Test AI Connectivity,
 * Refresh Models, Start/Stop AI Service, Configure Endpoint, Copy Model Info).
 *
 * <p>Sections: global execution-environment defaults (harness/provider/model/agent),
 * connected-harness management, provider management, agent-profile management and a
 * runtime monitor showing the actual execution environment of every task.</p>
 */
public class AiIntegrationPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.aiIntegration";

    // preserved legacy section
    private Label lblProvider;
    private Label lblEndpoint;
    private Combo cmbModel;
    private Label lblStatus;
    private Label lblRequests;
    private Button btnStartAi;
    private Button btnStopAi;

    // execution environment defaults
    private Combo cmbHarness;
    private Combo cmbProvider;
    private Combo cmbExecModel;
    private Combo cmbAgent;
    private Table harnessTable;
    private Table providerTable;
    private Table agentTable;
    private Table monitorTable;
    private Table modelTable;
    private Combo cmbSession;
    private Button btnLlmLoad;
    private Button btnLlmUnload;
    private Button btnLlmRemove;

    private List<ConnectedApplicationDescriptor> harnesses = List.of();
    private List<AiProviderDescriptor> providers = List.of();
    private List<AgentConfiguration> agents = List.of();
    private List<AiModelRegistryService.PublishedModel> publishedModels = List.of();
    private List<ExecutionConfigurationService.EnvironmentSession> sessions = List.of();

    public AiIntegrationPage(TrystWorkbenchEditor editor) {
        super(ID, "AI Environment", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar (existing operations preserved)
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

        createExecutionEnvironmentSection(parent);
        createHarnessSection(parent);
        createProviderSection(parent);
        createModelSection(parent);
        createAgentSection(parent);
        createSessionSection(parent);
        createRuntimeMonitorSection(parent);

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

    // ------------------------------------------------------------------
    // Execution environment (global defaults)
    // ------------------------------------------------------------------

    private void createExecutionEnvironmentSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Execution Environment (Global Defaults)");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(group, "Harness:");
        cmbHarness = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        cmbHarness.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createRow(group, "Provider:");
        cmbProvider = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        cmbProvider.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createRow(group, "Model (LLM):");
        cmbExecModel = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        cmbExecModel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createRow(group, "Agent Profile:");
        cmbAgent = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        cmbAgent.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button applyButton = new Button(group, SWT.PUSH);
        applyButton.setText("Apply Global Defaults");
        applyButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
        applyButton.addListener(SWT.Selection, e -> onApplyGlobalDefaults());
    }

    private void onApplyGlobalDefaults() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service == null) {
            showWarning("Execution Configuration", "Execution configuration service is not available.");
            return;
        }
        String harnessId = comboValue(cmbHarness, harnesses, ConnectedApplicationDescriptor::getId);
        String providerId = comboValue(cmbProvider, providers, AiProviderDescriptor::id);
        String agentId = comboValue(cmbAgent, agents, AgentConfiguration::getId);
        String model = cmbExecModel != null && !cmbExecModel.isDisposed()
                && cmbExecModel.getSelectionIndex() > 0
                        ? cmbExecModel.getText().trim()
                        : (cmbModel.getText() == null ? "" : cmbModel.getText().trim());
        ExecutionConfiguration existing = service.getGlobal()
                .orElseGet(() -> ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL));
        service.saveGlobal(new ExecutionConfiguration(harnessId, providerId, model, agentId,
                existing.executionParameters(), existing.toolPermissions(),
                existing.resourceLimits(), existing.harnessSettings(),
                ExecutionConfiguration.SOURCE_GLOBAL));
        showInfo("Execution Configuration", "Global execution environment defaults saved.");
        refresh();
    }

    // ------------------------------------------------------------------
    // Connected harnesses
    // ------------------------------------------------------------------

    private void createHarnessSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Connected Harnesses");
        group.setLayout(new GridLayout(6, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        harnessTable = new Table(group, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        harnessTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 6, 1));
        harnessTable.setHeaderVisible(true);
        harnessTable.setLinesVisible(true);
        addColumn(harnessTable, "Application", 170);
        addColumn(harnessTable, "Type", 80);
        addColumn(harnessTable, "Enabled", 60);
        addColumn(harnessTable, "Status", 130);
        addColumn(harnessTable, "Default", 70);

        Button selectButton = new Button(group, SWT.PUSH);
        selectButton.setText("Set Default");
        selectButton.addListener(SWT.Selection, e -> onHarnessSelect());
        Button enableButton = new Button(group, SWT.PUSH);
        enableButton.setText("Enable/Disable");
        enableButton.addListener(SWT.Selection, e -> onHarnessToggle());
        Button testButton = new Button(group, SWT.PUSH);
        testButton.setText("Scan/Test");
        testButton.addListener(SWT.Selection, e -> onHarnessTest());
        Button configButton = new Button(group, SWT.PUSH);
        configButton.setText("Configure...");
        configButton.addListener(SWT.Selection, e -> onHarnessConfigure());
        Button browseButton = new Button(group, SWT.PUSH);
        browseButton.setText("Browse");
        browseButton.addListener(SWT.Selection, e -> onHarnessBrowse());
        Button removeButton = new Button(group, SWT.PUSH);
        removeButton.setText("Remove");
        removeButton.addListener(SWT.Selection, e -> onHarnessRemove());
    }

    private ConnectedApplicationDescriptor selectedHarness() {
        int index = harnessTable.getSelectionIndex();
        return index >= 0 && index < harnesses.size() ? harnesses.get(index) : null;
    }

    private void onHarnessSelect() {
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (registry != null && descriptor != null) {
            registry.selectApplication(descriptor.getId());
            refresh();
        }
    }

    private void onHarnessToggle() {
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (registry != null && descriptor != null) {
            registry.setApplicationEnabled(descriptor.getId(), !descriptor.isEnabled());
            refresh();
        }
    }

    private void onHarnessTest() {
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (registry != null && descriptor != null) {
            var result = registry.testConnection(descriptor.getId());
            showInfo("Harness Connection Test",
                    result.success() ? result.message() : result.message());
            refresh();
        }
    }

    private void onHarnessConfigure() {
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (registry == null || descriptor == null) {
            return;
        }
        var adapter = registry.findAdapter(descriptor.getId());
        Map<String, String> current = adapter.map(a -> a.getConnectionConfiguration())
                .orElse(Map.of());
        InputDialog dialog = new InputDialog(resolveShell(), "Configure Harness",
                "Configuration (key=value, comma separated):",
                joinConfiguration(current), null);
        if (dialog.open() == Window.OK && dialog.getValue() != null) {
            registry.updateConfiguration(descriptor.getId(), parseConfiguration(dialog.getValue()));
            refresh();
        }
    }

    private void onHarnessBrowse() {
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (descriptor == null) {
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append("Application: ").append(descriptor.getName()).append("\n");
        info.append("Id: ").append(descriptor.getId()).append("\n");
        info.append("Type: ").append(descriptor.getApplicationType()).append("\n");
        info.append("Enabled: ").append(descriptor.isEnabled()).append("\n");
        info.append("Status: ").append(descriptor.getStatus() == null ? "UNKNOWN"
                : descriptor.getStatus().name()).append("\n");
        if (descriptor.getStatusMessage() != null && !descriptor.getStatusMessage().isBlank()) {
            info.append("Status message: ").append(descriptor.getStatusMessage()).append("\n");
        }
        info.append("Capabilities: ").append(String.join(", ", descriptor.getCapabilities()));
        showInfo("Harness Details", info.toString());
    }

    private void onHarnessRemove() {
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        ConnectedApplicationDescriptor descriptor = selectedHarness();
        if (registry == null || descriptor == null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Remove Harness",
                "Remove connected application '" + descriptor.getName() + "'?\n"
                        + "Its persisted descriptor will be deleted; adapters are re-registered on restart.");
        if (confirmed) {
            registry.unregisterApplication(descriptor.getId());
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // AI providers
    // ------------------------------------------------------------------

    private void createProviderSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("AI Providers");
        group.setLayout(new GridLayout(6, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        providerTable = new Table(group, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        providerTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 6, 1));
        providerTable.setHeaderVisible(true);
        providerTable.setLinesVisible(true);
        addColumn(providerTable, "Provider", 130);
        addColumn(providerTable, "Type", 90);
        addColumn(providerTable, "Endpoint", 220);
        addColumn(providerTable, "Enabled", 60);
        addColumn(providerTable, "Health", 100);
        addColumn(providerTable, "Default", 70);

        Button testButton = new Button(group, SWT.PUSH);
        testButton.setText("Test Connection");
        testButton.addListener(SWT.Selection, e -> onProviderTest());
        Button scanButton = new Button(group, SWT.PUSH);
        scanButton.setText("Scan Models");
        scanButton.addListener(SWT.Selection, e -> onProviderScan());
        Button enableButton = new Button(group, SWT.PUSH);
        enableButton.setText("Enable/Disable");
        enableButton.addListener(SWT.Selection, e -> onProviderToggle());
        Button defaultButton = new Button(group, SWT.PUSH);
        defaultButton.setText("Set Default");
        defaultButton.addListener(SWT.Selection, e -> onProviderDefault());
        Button configButton = new Button(group, SWT.PUSH);
        configButton.setText("Configure...");
        configButton.addListener(SWT.Selection, e -> onProviderConfigure());
        Button removeButton = new Button(group, SWT.PUSH);
        removeButton.setText("Remove");
        removeButton.addListener(SWT.Selection, e -> onProviderRemove());
    }

    private AiProviderDescriptor selectedProvider() {
        int index = providerTable.getSelectionIndex();
        return index >= 0 && index < providers.size() ? providers.get(index) : null;
    }

    private void onProviderTest() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry != null && descriptor != null) {
            var result = registry.testConnection(descriptor.id());
            showInfo("Provider Connection Test", result.message());
            refresh();
        }
    }

    private void onProviderScan() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry == null || descriptor == null) {
            return;
        }
        registry.discoverModels(descriptor.id()).thenRun(() -> {
            Display.getDefault().asyncExec(() -> {
                showInfo("Model Scan", "Model discovery for provider '"
                        + descriptor.name() + "' completed.");
                refresh();
            });
        }).exceptionally(t -> {
            Display.getDefault().asyncExec(() -> {
                showWarning("Model Scan", "Model discovery failed: " + t.getMessage());
                refresh();
            });
            return null;
        });
    }

    private void onProviderRemove() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry == null || descriptor == null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Remove Provider",
                "Remove provider '" + descriptor.name() + "'?\n"
                        + "Its persisted descriptor and published models will be removed.");
        if (confirmed) {
            registry.unregisterProvider(descriptor.id());
            refresh();
        }
    }

    private void onProviderToggle() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry != null && descriptor != null) {
            registry.setProviderEnabled(descriptor.id(), !descriptor.enabled());
            refresh();
        }
    }

    private void onProviderDefault() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry != null && descriptor != null) {
            registry.selectDefaultProvider(descriptor.id());
            refresh();
        }
    }

    private void onProviderConfigure() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiProviderDescriptor descriptor = selectedProvider();
        if (registry == null || descriptor == null) {
            return;
        }
        Map<String, String> current = registry.getConfiguration(descriptor.id());
        InputDialog dialog = new InputDialog(resolveShell(), "Configure Provider",
                "Configuration (key=value, comma separated; apiKey/token values are "
                        + "stored encrypted, **** keeps the current value):",
                joinConfiguration(current), null);
        if (dialog.open() == Window.OK && dialog.getValue() != null) {
            registry.updateConfiguration(descriptor.id(), parseConfiguration(dialog.getValue()));
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Models (published model catalog)
    // ------------------------------------------------------------------

    private void createModelSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Models");
        group.setLayout(new GridLayout(6, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        modelTable = new Table(group, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        modelTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 6, 1));
        modelTable.setHeaderVisible(true);
        modelTable.setLinesVisible(true);
        addColumn(modelTable, "Model", 170);
        addColumn(modelTable, "Provider", 120);
        addColumn(modelTable, "Available", 70);
        addColumn(modelTable, "Published", 70);
        addColumn(modelTable, "Status", 90);

        Button scanAllButton = new Button(group, SWT.PUSH);
        scanAllButton.setText("Scan All");
        scanAllButton.addListener(SWT.Selection, e -> onModelScanAll());
        btnLlmLoad = new Button(group, SWT.PUSH);
        btnLlmLoad.setText("Load (preload)");
        btnLlmLoad.addListener(SWT.Selection, e -> onModelLoad());
        btnLlmUnload = new Button(group, SWT.PUSH);
        btnLlmUnload.setText("Unload");
        btnLlmUnload.addListener(SWT.Selection, e -> onModelUnload());
        btnLlmRemove = new Button(group, SWT.PUSH);
        btnLlmRemove.setText("Remove (catalog)");
        btnLlmRemove.addListener(SWT.Selection, e -> onModelRemoveCatalog());
        Button browseButton = new Button(group, SWT.PUSH);
        browseButton.setText("Browse");
        browseButton.addListener(SWT.Selection, e -> onModelBrowse());
        Button deleteButton = new Button(group, SWT.PUSH);
        deleteButton.setText("Delete (provider)");
        deleteButton.addListener(SWT.Selection, e -> onModelDeleteFromProvider());
    }

    private AiModelRegistryService.PublishedModel selectedModel() {
        int index = modelTable.getSelectionIndex();
        return index >= 0 && index < publishedModels.size() ? publishedModels.get(index) : null;
    }

    private void onModelScanAll() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        if (registry == null) {
            return;
        }
        List<AiProviderDescriptor> targets = new ArrayList<>(registry.listProviders());
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(targets.size());
        for (AiProviderDescriptor descriptor : targets) {
            registry.discoverModels(descriptor.id()).whenComplete((v, t) -> {
                if (remaining.decrementAndGet() <= 0) {
                    Display.getDefault().asyncExec(() -> {
                        showInfo("Model Scan", "Model discovery completed for "
                                + targets.size() + " provider(s).");
                        refresh();
                    });
                }
            });
        }
    }

    private void onModelBrowse() {
        AiModelRegistryService.PublishedModel model = selectedModel();
        if (model == null) {
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append("Model: ").append(model.modelIdentifier()).append("\n");
        info.append("Id: ").append(model.id()).append("\n");
        info.append("Provider: ").append(model.providerId()).append("\n");
        info.append("Available: ").append(model.available()).append("\n");
        info.append("Published: ").append(model.published()).append("\n");
        info.append("Min role: ").append(model.minRole()).append("\n");
        info.append("Context limit: ").append(model.contextLimit()).append("\n");
        info.append("Last verified: ").append(model.lastVerifiedAt());
        showInfo("Model Details", info.toString());
    }

    private void onModelLoad() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiModelRegistryService.PublishedModel model = selectedModel();
        if (registry == null || model == null) {
            return;
        }
        registry.findAdapter(model.providerId()).ifPresentOrElse(adapter -> {
            adapter.loadModel(model.modelIdentifier()).whenComplete((ok, t) -> {
                Display.getDefault().asyncExec(() -> {
                    if (Boolean.TRUE.equals(ok)) {
                        showInfo("LLM Load", "Model '" + model.modelIdentifier()
                                + "' loaded into inference memory.");
                    } else {
                        showWarning("LLM Load", "Provider does not support preloading or the request failed"
                                + (t == null ? "" : ": " + t.getMessage()) + ".");
                    }
                    refresh();
                });
            });
        }, () -> showWarning("LLM Load", "No adapter registered for provider '"
                + model.providerId() + "'."));
    }

    private void onModelUnload() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiModelRegistryService.PublishedModel model = selectedModel();
        if (registry == null || model == null) {
            return;
        }
        registry.findAdapter(model.providerId()).ifPresentOrElse(adapter -> {
            adapter.unloadModel(model.modelIdentifier()).whenComplete((ok, t) -> {
                Display.getDefault().asyncExec(() -> {
                    if (Boolean.TRUE.equals(ok)) {
                        showInfo("LLM Unload", "Model '" + model.modelIdentifier()
                                + "' released from inference memory.");
                    } else {
                        showWarning("LLM Unload", "Provider does not support unloading or the request failed"
                                + (t == null ? "" : ": " + t.getMessage()) + ".");
                    }
                    refresh();
                });
            });
        }, () -> showWarning("LLM Unload", "No adapter registered for provider '"
                + model.providerId() + "'."));
    }

    private void onModelRemoveCatalog() {
        AiModelRegistryService catalog = TrystUiServices.getAiModelRegistryService();
        AiModelRegistryService.PublishedModel model = selectedModel();
        if (catalog == null || model == null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Remove Model",
                "Remove model '" + model.modelIdentifier() + "' from the published catalog?\n"
                        + "The model is NOT deleted from the provider.");
        if (confirmed) {
            catalog.removeModel(model.id());
            refresh();
        }
    }

    private void onModelDeleteFromProvider() {
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiModelRegistryService catalog = TrystUiServices.getAiModelRegistryService();
        AiModelRegistryService.PublishedModel model = selectedModel();
        if (registry == null || model == null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Delete Model",
                "Permanently delete model '" + model.modelIdentifier()
                        + "' from provider '" + model.providerId() + "' storage?");
        if (!confirmed) {
            return;
        }
        registry.findAdapter(model.providerId()).ifPresentOrElse(adapter -> {
            adapter.deleteModel(model.modelIdentifier()).whenComplete((ok, t) -> {
                Display.getDefault().asyncExec(() -> {
                    if (Boolean.TRUE.equals(ok)) {
                        if (catalog != null) {
                            catalog.removeModel(model.id());
                        }
                        showInfo("Delete Model", "Model '" + model.modelIdentifier()
                                + "' deleted from provider.");
                    } else {
                        showWarning("Delete Model", "Deletion failed or unsupported"
                                + (t == null ? "" : ": " + t.getMessage()) + ".");
                    }
                    refresh();
                });
            });
        }, () -> showWarning("Delete Model", "No adapter registered for provider '"
                + model.providerId() + "'."));
    }

    // ------------------------------------------------------------------
    // Execution environment sessions
    // ------------------------------------------------------------------

    private void createSessionSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Execution Environment Sessions (Save/Load)");
        group.setLayout(new GridLayout(4, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(group, "Session:");
        cmbSession = new Combo(group, SWT.DROP_DOWN | SWT.BORDER);
        cmbSession.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button saveButton = new Button(group, SWT.PUSH);
        saveButton.setText("Save Current");
        saveButton.addListener(SWT.Selection, e -> onSessionSave());
        Button loadButton = new Button(group, SWT.PUSH);
        loadButton.setText("Load");
        loadButton.addListener(SWT.Selection, e -> onSessionLoad());
        Button removeButton = new Button(group, SWT.PUSH);
        removeButton.setText("Remove");
        removeButton.addListener(SWT.Selection, e -> onSessionRemove());
        Button clearButton = new Button(group, SWT.PUSH);
        clearButton.setText("Clear Selection");
        clearButton.addListener(SWT.Selection, e -> {
            if (cmbSession != null && !cmbSession.isDisposed()) {
                cmbSession.setText("");
            }
        });
    }

    private ExecutionConfiguration currentSelection() {
        String harnessId = comboValue(cmbHarness, harnesses, ConnectedApplicationDescriptor::getId);
        String providerId = comboValue(cmbProvider, providers, AiProviderDescriptor::id);
        String agentId = comboValue(cmbAgent, agents, AgentConfiguration::getId);
        String model = cmbExecModel != null && !cmbExecModel.isDisposed()
                && cmbExecModel.getSelectionIndex() > 0 ? cmbExecModel.getText().trim() : "";
        return new ExecutionConfiguration(harnessId, providerId, model, agentId,
                Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_SESSION);
    }

    private void onSessionSave() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service == null) {
            showWarning("Sessions", "Execution configuration service is not available.");
            return;
        }
        String name = cmbSession.getText() == null ? "" : cmbSession.getText().trim();
        if (name.isBlank()) {
            showWarning("Sessions", "Enter a session name first.");
            return;
        }
        service.saveSession(name, currentSelection());
        showInfo("Sessions", "Execution environment saved as session '" + name + "'.");
        refresh();
    }

    private void onSessionLoad() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service == null) {
            showWarning("Sessions", "Execution configuration service is not available.");
            return;
        }
        String name = cmbSession.getText() == null ? "" : cmbSession.getText().trim();
        if (name.isBlank()) {
            showWarning("Sessions", "Select or enter a session name first.");
            return;
        }
        service.loadSession(name).ifPresentOrElse(session -> {
            service.saveGlobal(session);
            showInfo("Sessions", "Session '" + name + "' applied as the global execution environment.");
            refresh();
        }, () -> showWarning("Sessions", "Session '" + name + "' not found."));
    }

    private void onSessionRemove() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service == null) {
            return;
        }
        String name = cmbSession.getText() == null ? "" : cmbSession.getText().trim();
        if (name.isBlank()) {
            showWarning("Sessions", "Select or enter a session name first.");
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Remove Session",
                "Remove saved session '" + name + "'?");
        if (confirmed) {
            service.removeSession(name);
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Agent profiles
    // ------------------------------------------------------------------

    private void createAgentSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Agent Profiles");
        group.setLayout(new GridLayout(5, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        agentTable = new Table(group, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        agentTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 5, 1));
        agentTable.setHeaderVisible(true);
        agentTable.setLinesVisible(true);
        addColumn(agentTable, "Agent", 120);
        addColumn(agentTable, "Model", 120);
        addColumn(agentTable, "Pref. Harness", 100);
        addColumn(agentTable, "Pref. Provider", 100);
        addColumn(agentTable, "Pref. Model", 110);

        Button newButton = new Button(group, SWT.PUSH);
        newButton.setText("New...");
        newButton.addListener(SWT.Selection, e -> onAgentCreate());
        Button editButton = new Button(group, SWT.PUSH);
        editButton.setText("Edit...");
        editButton.addListener(SWT.Selection, e -> onAgentEdit());
        Button cloneButton = new Button(group, SWT.PUSH);
        cloneButton.setText("Clone");
        cloneButton.addListener(SWT.Selection, e -> onAgentClone());
        Button deleteButton = new Button(group, SWT.PUSH);
        deleteButton.setText("Delete");
        deleteButton.addListener(SWT.Selection, e -> onAgentDelete());
        Button defaultButton = new Button(group, SWT.PUSH);
        defaultButton.setText("Set Default");
        defaultButton.addListener(SWT.Selection, e -> onAgentDefault());
    }

    private AgentConfiguration selectedAgent() {
        int index = agentTable.getSelectionIndex();
        return index >= 0 && index < agents.size() ? agents.get(index) : null;
    }

    private void onAgentCreate() {
        AgentRepository repository = TrystUiServices.getAgentRepository();
        if (repository == null) {
            return;
        }
        AgentProfileDialog dialog = new AgentProfileDialog(resolveShell(), null);
        if (dialog.open() == Window.OK && dialog.getResult() != null) {
            AgentProfileSupport.AgentProfileView view = dialog.getResult();
            repository.save(new AgentConfiguration(UUID.randomUUID().toString(),
                    view.name(), view.modelName(), view.systemPrompt(),
                    AgentProfileSupport.withProfileFields(null, view.role(),
                            view.description(), view.toolPermissions(), view.restrictions(),
                            view.preferredHarness(), view.preferredProvider(),
                            view.preferredModel(), view.executionParameters()),
                    java.time.Instant.now(), java.time.Instant.now()));
            refresh();
        }
    }

    private void onAgentEdit() {
        AgentRepository repository = TrystUiServices.getAgentRepository();
        AgentConfiguration agent = selectedAgent();
        if (repository == null || agent == null) {
            return;
        }
        AgentProfileDialog dialog = new AgentProfileDialog(resolveShell(),
                AgentProfileSupport.view(agent));
        if (dialog.open() == Window.OK && dialog.getResult() != null) {
            AgentProfileSupport.AgentProfileView view = dialog.getResult();
            repository.save(new AgentConfiguration(agent.getId(), view.name(),
                    view.modelName(), view.systemPrompt(),
                    AgentProfileSupport.withProfileFields(agent, view.role(),
                            view.description(), view.toolPermissions(), view.restrictions(),
                            view.preferredHarness(), view.preferredProvider(),
                            view.preferredModel(), view.executionParameters()),
                    agent.getCreatedAt(), java.time.Instant.now()));
            refresh();
        }
    }

    private void onAgentClone() {
        AgentRepository repository = TrystUiServices.getAgentRepository();
        AgentConfiguration agent = selectedAgent();
        if (repository == null || agent == null) {
            return;
        }
        AgentProfileSupport.AgentProfileView view = AgentProfileSupport.view(agent);
        repository.save(new AgentConfiguration(UUID.randomUUID().toString(),
                view.name() + " (copy)", view.modelName(), view.systemPrompt(),
                AgentProfileSupport.withProfileFields(null, view.role(), view.description(),
                        view.toolPermissions(), view.restrictions(), view.preferredHarness(),
                        view.preferredProvider(), view.preferredModel(),
                        view.executionParameters()),
                java.time.Instant.now(), java.time.Instant.now()));
        refresh();
    }

    private void onAgentDelete() {
        AgentRepository repository = TrystUiServices.getAgentRepository();
        AgentConfiguration agent = selectedAgent();
        if (repository == null || agent == null) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(resolveShell(), "Delete Agent Profile",
                "Delete agent profile '" + agent.getName() + "'?");
        if (confirmed) {
            repository.delete(agent.getId());
            refresh();
        }
    }

    private void onAgentDefault() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        AgentConfiguration agent = selectedAgent();
        if (service == null || agent == null) {
            return;
        }
        ExecutionConfiguration existing = service.getGlobal()
                .orElseGet(() -> ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL));
        service.saveGlobal(new ExecutionConfiguration(existing.harnessId(),
                existing.providerId(), existing.modelId(), agent.getId(),
                existing.executionParameters(), existing.toolPermissions(),
                existing.resourceLimits(), existing.harnessSettings(),
                ExecutionConfiguration.SOURCE_GLOBAL));
        refresh();
    }

    // ------------------------------------------------------------------
    // Runtime monitor
    // ------------------------------------------------------------------

    private void createRuntimeMonitorSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Runtime Monitor — Actual Execution Environment per Task");
        group.setLayout(new GridLayout(1, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        monitorTable = new Table(group, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        monitorTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        monitorTable.setHeaderVisible(true);
        monitorTable.setLinesVisible(true);
        addColumn(monitorTable, "Task", 130);
        addColumn(monitorTable, "Status", 110);
        addColumn(monitorTable, "Harness", 110);
        addColumn(monitorTable, "Provider", 110);
        addColumn(monitorTable, "Model", 130);
        addColumn(monitorTable, "Agent", 110);
    }

    private void refreshMonitorTable() {
        if (monitorTable == null || monitorTable.isDisposed()) {
            return;
        }
        monitorTable.removeAll();
        TaskRepository taskRepository = TrystUiServices.getTaskRepository();
        ExecutionConfigurationService configService =
                TrystUiServices.getExecutionConfigurationService();
        if (taskRepository == null) {
            return;
        }
        List<Task> tasks = new ArrayList<>(taskRepository.findAllTasks());
        for (Task task : tasks) {
            ExecutionConfiguration snapshot = configService == null ? null
                    : configService.getSnapshot(task.getId()).orElse(null);
            TableItem item = new TableItem(monitorTable, SWT.NONE);
            item.setText(0, task.getId());
            item.setText(1, task.getStatus() == null ? "" : task.getStatus().name());
            item.setText(2, snapshot == null ? "" : snapshot.harnessId());
            item.setText(3, snapshot == null ? "" : snapshot.providerId());
            item.setText(4, snapshot == null ? "" : snapshot.modelId());
            item.setText(5, snapshot == null ? "" : snapshot.agentProfileId());
        }
    }

    // ------------------------------------------------------------------
    // Legacy operations (preserved)
    // ------------------------------------------------------------------

    private void onConfigureEndpoint() {
        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
        String currentEndpoint = cfg != null ? cfg.ollamaEndpoint() : "http://localhost:11434";
        InputDialog dialog = new InputDialog(resolveShell(), "Configure AI Endpoint",
                "Ollama REST Endpoint URL:", currentEndpoint, null);
        if (dialog.open() == Window.OK && dialog.getValue() != null
                && !dialog.getValue().isBlank()) {
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
            String text = "AI Provider: " + lblProvider.getText() + "\nEndpoint: "
                    + lblEndpoint.getText() + "\nModel: " + cmbModel.getText()
                    + "\nStatus: " + lblStatus.getText();
            cb.setContents(new Object[]{text}, new Transfer[]{TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
    }

    private void onModelSelected() {
        int idx = cmbModel.getSelectionIndex();
        if (idx >= 0) {
            String selected = cmbModel.getItem(idx);
            ConfigurationService cfgSvc = TrystUiServices.getConfigurationService();
            ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
            if (cfgSvc != null && cfg != null) {
                cfgSvc.updateConfiguration(cfg.withDefaultModel(selected));
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

    private void testAiConnectivity() {
        AiInferenceService ai = TrystUiServices.getAiInferenceService();
        if (ai == null) {
            lblStatus.setText("AiInferenceService unavailable in registry");
            showWarning("AI Connectivity Test", "AI Inference Service is not running or unavailable.");
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
                    showInfo("AI Connectivity Test",
                            "Inference test succeeded via model '" + req.model() + "':\n\n"
                                    + resp.content());
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
                    MessageDialog.openError(resolveShell(), "AI Connectivity Test",
                            "Inference test failed: " + t.getMessage());
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
        } else {
            lblStatus.setText("AiInferenceService unavailable in registry");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private static void addColumn(Table table, String text, int width) {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(text);
        column.setWidth(width);
    }

    private Shell resolveShell() {
        if (getControl() != null && !getControl().isDisposed()) {
            return getControl().getShell();
        }
        return Display.getDefault().getActiveShell();
    }

    private void showInfo(String title, String message) {
        Shell shell = resolveShell();
        if (shell != null) {
            MessageDialog.openInformation(shell, title, message);
        }
    }

    private void showWarning(String title, String message) {
        Shell shell = resolveShell();
        if (shell != null) {
            MessageDialog.openWarning(shell, title, message);
        }
    }

    private static String joinConfiguration(Map<String, String> configuration) {
        List<String> parts = new ArrayList<>();
        configuration.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(",", parts);
    }

    private static Map<String, String> parseConfiguration(String text) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (equals > 0) {
                result.put(token.substring(0, equals).trim(),
                        token.substring(equals + 1).trim());
            }
        }
        return result;
    }

    private static <T> String comboValue(Combo combo, List<T> items,
            java.util.function.Function<T, String> idExtractor) {
        if (combo == null || combo.getSelectionIndex() <= 0) {
            return "";
        }
        int index = combo.getSelectionIndex() - 1;
        if (index >= 0 && index < items.size()) {
            return idExtractor.apply(items.get(index));
        }
        return "";
    }

    private static <T> void populateCombo(Combo combo, List<T> items, String noneLabel,
            String selectedId, java.util.function.Function<T, String> idExtractor,
            java.util.function.Function<T, String> labelExtractor) {
        if (combo == null || combo.isDisposed()) {
            return;
        }
        String current = combo.getText();
        combo.removeAll();
        combo.add(noneLabel);
        int select = 0;
        for (int i = 0; i < items.size(); i++) {
            combo.add(labelExtractor.apply(items.get(i)));
            if (idExtractor.apply(items.get(i)).equals(selectedId)) {
                select = i + 1;
            }
            if (idExtractor.apply(items.get(i)).equals(current)) {
                select = i + 1;
            }
        }
        combo.select(select);
    }

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (lblProvider == null || lblProvider.isDisposed()) {
            return;
        }
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
        boolean running = (coord != null) ? coord.isAiServiceRunning()
                : (TrystUiServices.getAiInferenceService() != null);
        if (btnStartAi != null && !btnStartAi.isDisposed()) {
            btnStartAi.setEnabled(!running);
        }
        if (btnStopAi != null && !btnStopAi.isDisposed()) {
            btnStopAi.setEnabled(running);
        }
        refreshHarnesses();
        refreshProviders();
        refreshModels();
        refreshAgents();
        refreshModelCombo();
        refreshSessions();
        refreshEnvironmentCombos();
        refreshMonitorTable();
        checkAiConnection();
    }

    private void refreshHarnesses() {
        if (harnessTable == null || harnessTable.isDisposed()) {
            return;
        }
        ConnectedApplicationRegistry registry = TrystUiServices.getConnectedApplicationRegistry();
        harnesses = registry == null ? List.of() : new ArrayList<>(registry.listApplications());
        String selectedId = registry == null ? "" : registry.getSelectedApplicationId().orElse("");
        harnessTable.removeAll();
        for (ConnectedApplicationDescriptor descriptor : harnesses) {
            TableItem item = new TableItem(harnessTable, SWT.NONE);
            item.setText(0, descriptor.getName());
            item.setText(1, descriptor.getApplicationType());
            item.setText(2, descriptor.isEnabled() ? "yes" : "no");
            item.setText(3, statusText(descriptor.getStatus() == null ? null
                            : descriptor.getStatus().name(),
                    descriptor.getStatusMessage()));
            item.setText(4, descriptor.getId().equals(selectedId) ? "selected" : "");
            applyStatusColor(item, 3, descriptor.getStatus() == null ? null
                    : descriptor.getStatus().name());
        }
    }

    private void refreshProviders() {
        if (providerTable == null || providerTable.isDisposed()) {
            return;
        }
        AiProviderRegistry registry = TrystUiServices.getAiProviderRegistry();
        AiModelRegistryService catalog = TrystUiServices.getAiModelRegistryService();
        providers = registry == null ? List.of() : new ArrayList<>(registry.listProviders());
        String defaultId = registry == null ? "" : registry.getDefaultProviderId().orElse("");
        providerTable.removeAll();
        for (AiProviderDescriptor descriptor : providers) {
            TableItem item = new TableItem(providerTable, SWT.NONE);
            item.setText(0, descriptor.name());
            item.setText(1, descriptor.type());
            item.setText(2, descriptor.endpoint());
            item.setText(3, descriptor.enabled() ? "yes" : "no");
            AiModelRegistryService.ProviderHealth health = catalog == null
                    ? AiModelRegistryService.ProviderHealth.UNKNOWN
                    : catalog.getProviderHealth(descriptor.id());
            item.setText(4, health.name());
            item.setText(5, descriptor.id().equals(defaultId) ? "selected" : "");
            if (!descriptor.enabled()) {
                item.setForeground(4, color(SWT.COLOR_DARK_GRAY));
            } else {
                applyStatusColor(item, 4, health.name());
            }
        }
    }

    private void refreshModels() {
        if (modelTable == null || modelTable.isDisposed()) {
            return;
        }
        AiModelRegistryService catalog = TrystUiServices.getAiModelRegistryService();
        publishedModels = catalog == null ? List.of()
                : new ArrayList<>(catalog.getAllModels());
        modelTable.removeAll();
        for (AiModelRegistryService.PublishedModel model : publishedModels) {
            TableItem item = new TableItem(modelTable, SWT.NONE);
            item.setText(0, model.modelIdentifier());
            item.setText(1, model.providerId());
            item.setText(2, model.available() ? "yes" : "no");
            item.setText(3, model.published() ? "yes" : "no");
            item.setText(4, model.available() ? "ONLINE" : "OFFLINE");
            if (model.available() && model.published()) {
                item.setForeground(4, color(SWT.COLOR_DARK_GREEN));
            } else if (model.available()) {
                item.setForeground(4, color(SWT.COLOR_DARK_YELLOW));
            } else {
                item.setForeground(4, color(SWT.COLOR_DARK_RED));
            }
        }
        boolean hasModel = !publishedModels.isEmpty();
        if (btnLlmLoad != null && !btnLlmLoad.isDisposed()) {
            btnLlmLoad.setEnabled(hasModel);
            btnLlmUnload.setEnabled(hasModel);
            btnLlmRemove.setEnabled(hasModel);
        }
    }

    private void refreshSessions() {
        if (cmbSession == null || cmbSession.isDisposed()) {
            return;
        }
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        sessions = service == null ? List.of() : new ArrayList<>(service.listSessions());
        String current = cmbSession.getText();
        cmbSession.removeAll();
        for (ExecutionConfigurationService.EnvironmentSession session : sessions) {
            cmbSession.add(session.name());
        }
        if (current != null && !current.isBlank()) {
            cmbSession.setText(current);
        }
    }

    private static String statusText(String status, String message) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        if (message == null || message.isBlank()) {
            return status;
        }
        return status + " (" + message + ")";
    }

    private org.eclipse.swt.graphics.Color color(int swtColor) {
        return getControl().getDisplay().getSystemColor(swtColor);
    }

    private void applyStatusColor(TableItem item, int column, String status) {
        if (status == null) {
            item.setForeground(column, color(SWT.COLOR_DARK_YELLOW));
            return;
        }
        switch (status.toUpperCase()) {
            case "ONLINE", "CONNECTED", "AVAILABLE", "ACTIVE", "RUNNING" ->
                    item.setForeground(column, color(SWT.COLOR_DARK_GREEN));
            case "DEGRADED", "UNKNOWN", "PAUSED", "PENDING" ->
                    item.setForeground(column, color(SWT.COLOR_DARK_YELLOW));
            default -> item.setForeground(column, color(SWT.COLOR_DARK_RED));
        }
    }

    private void refreshAgents() {
        if (agentTable == null || agentTable.isDisposed()) {
            return;
        }
        AgentRepository repository = TrystUiServices.getAgentRepository();
        agents = repository == null ? List.of() : new ArrayList<>(repository.findAll());
        agentTable.removeAll();
        for (AgentConfiguration agent : agents) {
            AgentProfileSupport.AgentProfileView view = AgentProfileSupport.view(agent);
            TableItem item = new TableItem(agentTable, SWT.NONE);
            item.setText(0, agent.getName());
            item.setText(1, agent.getModelName());
            item.setText(2, view.preferredHarness());
            item.setText(3, view.preferredProvider());
            item.setText(4, view.preferredModel());
        }
    }

    private void refreshModelCombo() {
        if (cmbModel == null || cmbModel.isDisposed()) {
            return;
        }
        AiModelRegistryService catalog = TrystUiServices.getAiModelRegistryService();
        String current = cmbModel.getText();
        cmbModel.removeAll();
        if (catalog != null) {
            for (AiModelRegistryService.PublishedModel model : catalog.getAllModels()) {
                if (model.available() && model.published()) {
                    cmbModel.add(model.modelIdentifier());
                }
            }
        }
        ApplicationConfiguration cfg = TrystUiServices.getConfiguration();
        String fallback = cfg == null ? "codellama" : cfg.defaultModel();
        if (!current.isBlank()) {
            fallback = current;
        }
        int index = -1;
        for (int i = 0; i < cmbModel.getItemCount(); i++) {
            if (cmbModel.getItem(i).equalsIgnoreCase(fallback)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            cmbModel.select(index);
        } else {
            cmbModel.add(fallback);
            cmbModel.select(cmbModel.getItemCount() - 1);
        }
    }

    private void refreshEnvironmentCombos() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        ExecutionConfiguration global = service == null ? null : service.getGlobal().orElse(null);
        String harnessId = global == null ? "" : global.harnessId();
        String providerId = global == null ? "" : global.providerId();
        String modelId = global == null ? "" : global.modelId();
        String agentId = global == null ? "" : global.agentProfileId();
        populateCombo(cmbHarness, harnesses, "(none — built-in local agent)", harnessId,
                ConnectedApplicationDescriptor::getId,
                d -> d.getName() + " (" + d.getApplicationType() + ")");
        populateCombo(cmbProvider, providers, "(none — provider default)", providerId,
                AiProviderDescriptor::id, d -> d.name() + " (" + d.type() + ")");
        populateExecModelCombo(modelId);
        populateCombo(cmbAgent, agents, "(none — agent default)", agentId,
                AgentConfiguration::getId, AgentConfiguration::getName);
    }

    private void populateExecModelCombo(String selectedModelId) {
        if (cmbExecModel == null || cmbExecModel.isDisposed()) {
            return;
        }
        String current = cmbExecModel.getText();
        cmbExecModel.removeAll();
        cmbExecModel.add("(none — model default)");
        List<String> modelIds = new ArrayList<>();
        for (AiModelRegistryService.PublishedModel model : publishedModels) {
            if (model.available() && model.published()
                    && !modelIds.contains(model.modelIdentifier())) {
                modelIds.add(model.modelIdentifier());
            }
        }
        int select = 0;
        for (int i = 0; i < modelIds.size(); i++) {
            cmbExecModel.add(modelIds.get(i));
            if (modelIds.get(i).equals(selectedModelId)) {
                select = i + 1;
            }
            if (modelIds.get(i).equals(current)) {
                select = i + 1;
            }
        }
        if (select == 0 && selectedModelId != null && !selectedModelId.isBlank()) {
            cmbExecModel.add(selectedModelId);
            select = cmbExecModel.getItemCount() - 1;
        }
        cmbExecModel.select(select);
    }
}
