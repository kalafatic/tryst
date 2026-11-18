package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.ai.AgentProfileSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Create/edit dialog for TRYST agent profiles (name, model, system instructions, role,
 * tool permissions, restrictions and preferred harness/provider/model). The result is a
 * {@link AgentProfileSupport.AgentProfileView}; persistence stays with the shared
 * AgentRepository.
 */
public class AgentProfileDialog extends Dialog {

    private final AgentProfileSupport.AgentProfileView existing;
    private Text nameText;
    private Text modelText;
    private Text instructionsText;
    private Text roleText;
    private Text permissionsText;
    private Text restrictionsText;
    private Text harnessText;
    private Text providerText;
    private Text preferredModelText;
    private Text parametersText;
    private AgentProfileSupport.AgentProfileView result;

    public AgentProfileDialog(Shell parent, AgentProfileSupport.AgentProfileView existing) {
        super(parent);
        this.existing = existing;
    }

    public AgentProfileSupport.AgentProfileView getResult() {
        return result;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        getShell().setText(existing == null ? "Create Agent Profile" : "Edit Agent Profile");
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(container);

        nameText = field(container, "Name:",
                existing == null ? "" : existing.name());
        modelText = field(container, "Model:",
                existing == null ? "" : existing.modelName());
        instructionsText = field(container, "System Instructions:",
                existing == null ? "" : existing.systemPrompt(), true);
        roleText = field(container, "Role:",
                existing == null ? "" : existing.role());
        permissionsText = field(container, "Tool Permissions (comma separated):",
                existing == null ? "" : String.join(",", existing.toolPermissions()));
        restrictionsText = field(container, "Restrictions (comma separated):",
                existing == null ? "" : String.join(",", existing.restrictions()));
        harnessText = field(container, "Preferred Harness (id, empty = any):",
                existing == null ? "" : existing.preferredHarness());
        providerText = field(container, "Preferred Provider (id, empty = any):",
                existing == null ? "" : existing.preferredProvider());
        preferredModelText = field(container, "Preferred Model (empty = any):",
                existing == null ? "" : existing.preferredModel());
        parametersText = field(container, "Execution Parameters (key=value, comma separated):",
                existing == null ? "" : joinParameters(existing.executionParameters()));
        return container;
    }

    private Text field(Composite parent, String label, String initial) {
        return field(parent, label, initial, false);
    }

    private Text field(Composite parent, String label, String initial, boolean multiline) {
        Label labelControl = new Label(parent, SWT.NONE);
        labelControl.setText(label);
        Text text = new Text(parent, multiline
                ? (SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL) : SWT.BORDER);
        text.setText(initial == null ? "" : initial);
        GridDataFactory.fillDefaults().grab(true, false).hint(380,
                multiline ? 90 : SWT.DEFAULT).applyTo(text);
        return text;
    }

    private static String joinParameters(Map<String, String> parameters) {
        List<String> parts = new ArrayList<>();
        parameters.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(",", parts);
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        if (name.isBlank()) {
            org.eclipse.jface.dialogs.MessageDialog.openWarning(getShell(),
                    "Agent Profile", "A profile name is required.");
            return;
        }
        String id = existing == null ? "" : existing.id();
        result = new AgentProfileSupport.AgentProfileView(id, name,
                modelText.getText().trim(), instructionsText.getText().trim(),
                roleText.getText().trim(), "", splitList(permissionsText.getText()),
                splitList(restrictionsText.getText()), harnessText.getText().trim(),
                providerText.getText().trim(), preferredModelText.getText().trim(),
                parseParameters(parametersText.getText()));
        super.okPressed();
    }

    private static List<String> splitList(String text) {
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> result = new LinkedHashMap<>();
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

    @Override
    protected boolean isResizable() {
        return true;
    }
}
