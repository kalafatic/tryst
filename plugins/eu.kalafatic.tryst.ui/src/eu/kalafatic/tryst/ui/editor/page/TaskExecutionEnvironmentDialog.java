package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.ai.AiProviderDescriptor;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.ai.ExecutionConfiguration;
import eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.AgentConfiguration;
import eu.kalafatic.tryst.domain.repository.AgentRepository;
import eu.kalafatic.tryst.ui.TrystUiServices;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Task-level execution environment selection dialog: harness, provider, model and agent
 * profile per task. Saves the selection as a TASK-scope override through the shared
 * {@link ExecutionConfigurationService}; the resolved values are snapshotted when the
 * task executes.
 */
public class TaskExecutionEnvironmentDialog extends Dialog {

    private final String taskId;
    private final String projectId;
    private Combo harnessCombo;
    private Combo providerCombo;
    private Combo modelCombo;
    private Combo agentCombo;
    private final List<ConnectedApplicationDescriptor> harnesses = new ArrayList<>();
    private final List<AiProviderDescriptor> providers = new ArrayList<>();
    private final List<AgentConfiguration> agents = new ArrayList<>();
    private final List<String> models = new ArrayList<>();

    public TaskExecutionEnvironmentDialog(Shell parent, String taskId, String projectId) {
        super(parent);
        this.taskId = taskId;
        this.projectId = projectId;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Execution Environment — Task " + taskId);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        container.setLayout(new GridLayout(2, false));

        ConnectedApplicationRegistry harnessRegistry =
                TrystUiServices.getConnectedApplicationRegistry();
        AiProviderRegistry providerRegistry = TrystUiServices.getAiProviderRegistry();
        AgentRepository agentRepository = TrystUiServices.getAgentRepository();
        var modelCatalog = TrystUiServices.getAiModelRegistryService();
        if (harnessRegistry != null) {
            harnesses.addAll(harnessRegistry.listApplications());
        }
        if (providerRegistry != null) {
            providers.addAll(providerRegistry.listProviders());
        }
        if (agentRepository != null) {
            agents.addAll(agentRepository.findAll());
        }
        if (modelCatalog != null) {
            modelCatalog.getAllModels().stream()
                    .filter(m -> m.available() && m.published())
                    .map(m -> m.modelIdentifier())
                    .distinct()
                    .forEach(models::add);
        }
        ExecutionConfiguration existing = null;
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service != null) {
            ExecutionConfiguration resolved = service.resolve(projectId, taskId);
            existing = resolved;
        }

        harnessCombo = combo(container, "Harness:", harnesses,
                d -> d.getName() + " (" + d.getApplicationType() + ")",
                existing == null ? "" : existing.harnessId(),
                d -> d.getId());
        providerCombo = combo(container, "Provider:", providers,
                d -> d.name() + " (" + d.type() + ")",
                existing == null ? "" : existing.providerId(),
                d -> d.id());
        Label modelLabel = new Label(container, SWT.NONE);
        modelLabel.setText("Model:");
        modelCombo = new Combo(container, SWT.BORDER);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        for (String model : models) {
            modelCombo.add(model);
        }
        String currentModel = existing == null ? "" : existing.modelId();
        if (!currentModel.isBlank() && !models.contains(currentModel)) {
            modelCombo.add(currentModel, 0);
        }
        modelCombo.setText(currentModel);
        agentCombo = combo(container, "Agent Profile:", agents,
                AgentConfiguration::getName,
                existing == null ? "" : existing.agentProfileId(),
                AgentConfiguration::getId);
        return container;
    }

    private <T> Combo combo(Composite parent, String label, List<T> items,
            java.util.function.Function<T, String> labelExtractor, String selectedId,
            java.util.function.Function<T, String> idExtractor) {
        Label labelControl = new Label(parent, SWT.NONE);
        labelControl.setText(label);
        Combo combo = new Combo(parent, SWT.READ_ONLY);
        combo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        combo.add("(inherited default)");
        int select = 0;
        for (int i = 0; i < items.size(); i++) {
            combo.add(labelExtractor.apply(items.get(i)));
            if (idExtractor.apply(items.get(i)).equals(selectedId)) {
                select = i + 1;
            }
        }
        combo.select(select);
        return combo;
    }

    private static String selectedValue(Combo combo) {
        return combo.getSelectionIndex() > 0 ? combo.getText() : "";
    }

    @Override
    protected void okPressed() {
        ExecutionConfigurationService service = TrystUiServices.getExecutionConfigurationService();
        if (service != null) {
            String harnessId = resolveId(harnessCombo, harnesses,
                    ConnectedApplicationDescriptor::getId);
            String providerId = resolveId(providerCombo, providers, AiProviderDescriptor::id);
            String agentId = resolveId(agentCombo, agents, AgentConfiguration::getId);
            service.saveTaskOverride(taskId, new ExecutionConfiguration(harnessId,
                    providerId, modelCombo.getText().trim(), agentId, java.util.Map.of(),
                    java.util.List.of(), java.util.Map.of(), java.util.Map.of(),
                    ExecutionConfiguration.SOURCE_TASK));
        }
        super.okPressed();
    }

    private static <T> String resolveId(Combo combo, List<T> items,
            java.util.function.Function<T, String> idExtractor) {
        int index = combo.getSelectionIndex() - 1;
        if (index >= 0 && index < items.size()) {
            return idExtractor.apply(items.get(index));
        }
        return "";
    }
}
