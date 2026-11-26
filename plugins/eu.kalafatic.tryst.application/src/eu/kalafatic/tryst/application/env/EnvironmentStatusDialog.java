package eu.kalafatic.tryst.application.env;

import eu.kalafatic.tryst.application.TrystApplication;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.ai.AiModelRegistryService;
import eu.kalafatic.tryst.domain.ai.AiProviderRegistry;
import eu.kalafatic.tryst.domain.env.ExecutionEnvironmentMonitor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationDescriptor;
import eu.kalafatic.tryst.domain.harness.ConnectedApplicationRegistry;
import eu.kalafatic.tryst.domain.model.UserRole;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Operational execution environment status dialog (server RCP).
 *
 * <p>Renders the single authoritative {@link ExecutionEnvironmentMonitor} snapshot:
 * overall readiness, the four component indicators with status colors, current
 * problems, last successful/failed checks and the supported recovery actions. All
 * actions reuse the existing provider/harness/model management services — no parallel
 * health infrastructure is introduced.</p>
 */
public class EnvironmentStatusDialog extends Dialog {

    private static final Color COLOR_GREEN = new Color(0x2d, 0xa4, 0x4e);
    private static final Color COLOR_ORANGE = new Color(0xc2, 0x89, 0x00);
    private static final Color COLOR_RED = new Color(0xc2, 0x3b, 0x2e);
    private static final Color COLOR_GRAY = new Color(0x8a, 0x8f, 0x98);

    private final Map<ExecutionEnvironmentMonitor.ComponentId, Label> componentLabels = new LinkedHashMap<>();
    private Label readinessLabel;
    private Label problemsLabel;
    private Label checksLabel;

    public EnvironmentStatusDialog(Shell parent) {
        super(parent);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 16;
        layout.marginHeight = 12;
        layout.horizontalSpacing = 12;
        container.setLayout(layout);

        Label title = new Label(container, SWT.NONE);
        title.setText("EXECUTION ENVIRONMENT STATUS");
        title.setFont(container.getFont());
        GridData titleData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        titleData.verticalIndent = 4;
        title.setLayoutData(titleData);

        Label readinessCaption = new Label(container, SWT.NONE);
        readinessCaption.setText("Overall readiness:");
        readinessLabel = new Label(container, SWT.NONE);
        readinessLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label componentsCaption = new Label(container, SWT.NONE);
        componentsCaption.setText("Components:");
        componentsCaption.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

        for (ExecutionEnvironmentMonitor.ComponentId id : ExecutionEnvironmentMonitor.ComponentId.values()) {
            Label dot = new Label(container, SWT.NONE);
            dot.setText("\u25CF");
            dot.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
            Label value = new Label(container, SWT.WRAP);
            value.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            componentLabels.put(id, value);
        }

        Label problemsCaption = new Label(container, SWT.NONE);
        problemsCaption.setText("Problems:");
        problemsLabel = new Label(container, SWT.WRAP);
        problemsLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label checksCaption = new Label(container, SWT.NONE);
        checksCaption.setText("Checks:");
        checksLabel = new Label(container, SWT.WRAP);
        checksLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        refreshLabels(resolveMonitor().getSnapshot());
        return container;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, 100, "Refresh Now", false);
        createButton(parent, 101, "Select Harness...", false);
        createButton(parent, 102, "Select Provider...", false);
        createButton(parent, 103, "Select Model...", false);
        createButton(parent, 104, "Recovery Guidance", false);
        createButton(parent, Dialog.CANCEL, "Close", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        switch (buttonId) {
            case 100 -> refreshNow();
            case 101 -> selectHarness();
            case 102 -> selectProvider();
            case 103 -> selectModel();
            case 104 -> showRecoveryGuidance();
            default -> super.buttonPressed(buttonId);
        }
    }

    private void refreshNow() {
        getButton(100).setEnabled(false);
        try {
            getButton(100).setText("Checking...");
        } catch (Exception ignored) {}
        Thread.ofVirtual().start(() -> {
            try {
                resolveMonitor().refreshWithModelDiscovery();
            } catch (Exception e) {
                TrystLog.warn(EnvironmentStatusDialog.class, "Explicit environment refresh failed: " + e.getMessage());
            }
            Display display = getShell() == null || getShell().isDisposed() ? null : getShell().getDisplay();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> {
                    if (getShell() == null || getShell().isDisposed()) {
                        return;
                    }
                    refreshLabels(resolveMonitor().getSnapshot());
                    Button button = getButton(100);
                    if (button != null && !button.isDisposed()) {
                        button.setEnabled(true);
                        button.setText("Refresh Now");
                    }
                });
            }
        });
    }

    private void refreshLabels(ExecutionEnvironmentMonitor.EnvironmentSnapshot snapshot) {
        readinessLabel.setText(snapshot.ready() ? "READY" : "NOT READY");
        readinessLabel.setForeground(snapshot.ready() ? COLOR_GREEN : COLOR_RED);
        for (Map.Entry<ExecutionEnvironmentMonitor.ComponentId, Label> entry : componentLabels.entrySet()) {
            ExecutionEnvironmentMonitor.ComponentState state = snapshot.component(entry.getKey());
            String detail = state != null && !state.detail().isBlank() ? state.detail() : "No information";
            entry.getValue().setText(state != null
                    ? state.health().name() + " — " + detail
                    : "UNKNOWN");
            entry.getValue().setForeground(colorFor(state != null ? state.health() : null));
        }
        problemsLabel.setText(snapshot.problems().isEmpty()
                ? "None detected."
                : String.join("\n", snapshot.problems()));
        problemsLabel.setForeground(snapshot.problems().isEmpty() ? COLOR_GREEN : COLOR_RED);
        checksLabel.setText("Last successful check: "
                + (snapshot.lastSuccessfulCheck() != null ? snapshot.lastSuccessfulCheck() : "never")
                + "\nLast failed check: "
                + (snapshot.lastFailedCheck() != null ? snapshot.lastFailedCheck() : "never")
                + "\nRequired components: " + snapshot.requiredComponents());
        getShell().layout(true, true);
    }

    private static Color colorFor(ExecutionEnvironmentMonitor.Health health) {
        if (health == null) {
            return COLOR_GRAY;
        }
        return switch (health) {
            case OPERATIONAL -> COLOR_GREEN;
            case DEGRADED, STARTING -> COLOR_ORANGE;
            case UNAVAILABLE -> COLOR_RED;
            default -> COLOR_GRAY;
        };
    }

    private static ApplicationLifecycleCoordinator coordinator() {
        return TrystApplication.getCoordinator();
    }

    private static ExecutionEnvironmentMonitor resolveMonitor() {
        ApplicationLifecycleCoordinator coord = coordinator();
        if (coord != null && coord.getExecutionEnvironmentMonitor() != null) {
            return coord.getExecutionEnvironmentMonitor();
        }
        throw new IllegalStateException("ExecutionEnvironmentMonitor is not available in this runtime.");
    }

    private void selectHarness() {
        ApplicationLifecycleCoordinator coord = coordinator();
        ConnectedApplicationRegistry registry = coord != null ? coord.getConnectedApplicationRegistry() : null;
        if (registry == null) {
            MessageDialog.openWarning(getShell(), "Harness Selection", "Connected application registry is unavailable.");
            return;
        }
        java.util.List<ConnectedApplicationDescriptor> apps = new ArrayList<>();
        try {
            apps = registry.listApplications();
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Harness Selection", "Could not list connected applications: " + e.getMessage());
            return;
        }
        Optional<String> selected = registry.getSelectedApplicationId();
        SelectOptionDialog selection = new SelectOptionDialog(getShell(), "Select Coding Harness",
                "Choose the coding harness used for delegated execution (blank = built-in local agent).",
                apps.stream().map(a -> a.getId() + " — " + a.getName()
                        + (a.getId().equals(selected.orElse("")) ? " (selected)" : "")).toList(),
                apps.stream().map(ConnectedApplicationDescriptor::getId).toList());
        if (selection.open() == Dialog.OK) {
            String chosen = selection.getSelectedValue();
            if (chosen == null || chosen.isBlank()) {
                registry.selectApplication("");
            } else {
                registry.selectApplication(chosen);
            }
            refreshNow();
        }
    }

    private void selectProvider() {
        ApplicationLifecycleCoordinator coord = coordinator();
        AiProviderRegistry registry = coord != null ? coord.getAiProviderRegistry() : null;
        if (registry == null) {
            MessageDialog.openWarning(getShell(), "Provider Selection", "Provider registry is unavailable.");
            return;
        }
        java.util.List<eu.kalafatic.tryst.domain.ai.AiProviderDescriptor> providers;
        try {
            providers = registry.listProviders();
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Provider Selection", "Could not list providers: " + e.getMessage());
            return;
        }
        Optional<String> current = registry.getDefaultProviderId();
        SelectOptionDialog selection = new SelectOptionDialog(getShell(), "Select Inference Provider",
                "Choose the default inference provider for task execution.",
                providers.stream().map(p -> p.id() + " — " + p.name() + " (" + p.type() + ")"
                        + (p.id().equals(current.orElse("")) ? " (default)" : "")).toList(),
                providers.stream().map(eu.kalafatic.tryst.domain.ai.AiProviderDescriptor::id).toList());
        if (selection.open() == Dialog.OK && selection.getSelectedValue() != null) {
            registry.selectDefaultProvider(selection.getSelectedValue());
            try {
                registry.discoverModels(selection.getSelectedValue());
            } catch (Exception ignored) {}
            refreshNow();
        }
    }

    private void selectModel() {
        ApplicationLifecycleCoordinator coord = coordinator();
        AiModelRegistryService registry = coord != null ? coord.getAiModelRegistryService() : null;
        if (registry == null) {
            MessageDialog.openWarning(getShell(), "Model Selection", "Model registry is unavailable.");
            return;
        }
        java.util.List<AiModelRegistryService.PublishedModel> models;
        try {
            models = registry.getModelsForRole(UserRole.ADMIN);
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Model Selection", "Could not list models: " + e.getMessage());
            return;
        }
        String current = coord != null && coord.getAiExecutionResolver() != null
                ? coord.getAiExecutionResolver().resolve(null, null).modelId()
                : registry.getDefaultModel();
        SelectOptionDialog selection = new SelectOptionDialog(getShell(), "Select Model",
                "Choose the model used for task execution.",
                models.stream().map(m -> m.modelIdentifier() + " (" + m.providerId() + ")"
                        + (m.modelIdentifier().equals(current) ? " (selected)" : "")
                        + (m.available() ? "" : " — not available")).toList(),
                models.stream().map(AiModelRegistryService.PublishedModel::modelIdentifier).toList());
        if (selection.open() == Dialog.OK && selection.getSelectedValue() != null) {
            String selected = selection.getSelectedValue();
            // Single source of truth: persist through the unified execution configuration
            // and keep the catalog/legacy mirrors in sync (same contract as the AI
            // Environment page).
            eu.kalafatic.tryst.domain.ai.ExecutionConfigurationService execService =
                    coord != null ? coord.getExecutionConfigurationService() : null;
            if (execService != null) {
                try {
                    var resolved = coord.getAiExecutionResolver().resolve(null, null);
                    eu.kalafatic.tryst.domain.ai.ExecutionConfiguration existing =
                            execService.getGlobal().orElseGet(() ->
                                    eu.kalafatic.tryst.domain.ai.ExecutionConfiguration.empty(
                                            eu.kalafatic.tryst.domain.ai.ExecutionConfiguration.SOURCE_GLOBAL));
                    execService.saveGlobal(new eu.kalafatic.tryst.domain.ai.ExecutionConfiguration(
                            existing.harnessId(), resolved.providerId(), selected,
                            existing.agentProfileId(), existing.executionParameters(),
                            existing.toolPermissions(), existing.resourceLimits(),
                            existing.harnessSettings(),
                            eu.kalafatic.tryst.domain.ai.ExecutionConfiguration.SOURCE_GLOBAL));
                } catch (Exception e) {
                    TrystLog.warn(EnvironmentStatusDialog.class,
                            "Could not persist unified model selection: " + e.getMessage());
                }
            }
            registry.setDefaultModel(selected);
            if (coord != null && coord.getAiProviderRegistry() != null
                    && !resolvedProviderId(coord).isBlank()) {
                try {
                    coord.getAiProviderRegistry().updateDefaultModel(resolvedProviderId(coord), selected);
                } catch (Exception ignored) {}
            }
            refreshNow();
        }
    }

    private static String resolvedProviderId(ApplicationLifecycleCoordinator coord) {
        try {
            return coord.getAiExecutionResolver().resolve(null, null).providerId();
        } catch (Exception e) {
            return "";
        }
    }

    private void showRecoveryGuidance() {
        MessageDialog.openInformation(getShell(), "Environment Recovery Guidance",
                "1. Identify the unavailable components listed above.\n\n"
                        + "2. TRYST Server: start the web server from the Environment menu if it is stopped.\n\n"
                        + "3. Coding harness: make sure the selected connected coding application is running and reachable, "
                        + "or select a different harness.\n\n"
                        + "4. Inference provider: make sure the configured inference engine (e.g. Ollama/ENR) is running "
                        + "and reachable, or select a different provider.\n\n"
                        + "5. Model: select an available model (pull it first if needed, then re-scan models).\n\n"
                        + "6. Verify: press Refresh Now — readiness is re-evaluated automatically.\n\n"
                        + "7. On recovery, all blocked tasks are released into the execution queue automatically in queue order.");
    }

    /**
     * Small single-selection option dialog used for harness/provider/model selection.
     */
    private static final class SelectOptionDialog extends Dialog {
        private final String message;
        private final java.util.List<String> labels;
        private final java.util.List<String> values;
        private List list;
        private String selectedValue;

        SelectOptionDialog(Shell parent, String title, String message,
                           java.util.List<String> labels, java.util.List<String> values) {
            super(parent);
            setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
            getShell().setText(title);
            this.message = message;
            this.labels = labels;
            this.values = values;
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite container = (Composite) super.createDialogArea(parent);
            container.setLayout(new GridLayout(1, false));
            Label msg = new Label(container, SWT.WRAP);
            msg.setText(message);
            msg.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 1, 1));
            msg.setSize(360, msg.computeSize(360, SWT.DEFAULT).y);
            list = new List(container, SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
            GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
            data.widthHint = 420;
            data.heightHint = 180;
            list.setLayoutData(data);
            for (String label : labels) {
                list.add(label);
            }
            if (list.getItemCount() > 0) {
                list.select(0);
            }
            return container;
        }

        @Override
        protected void okPressed() {
            int index = list.getSelectionIndex();
            if (index >= 0 && index < values.size()) {
                selectedValue = values.get(index);
            }
            super.okPressed();
        }

        String getSelectedValue() {
            return selectedValue;
        }
    }
}
