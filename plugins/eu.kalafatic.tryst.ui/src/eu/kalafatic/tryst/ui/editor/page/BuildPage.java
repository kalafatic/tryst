package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.build.BuildSystemType;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.build.DefaultBuildTechnologyDetector;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
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

import java.nio.file.Files;

/**
 * Editor page for Build configuration, build system detection, and build triggering.
 */
public class BuildPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.build";

    private Label lblBuildSystem;
    private Label lblConfigFile;
    private Label lblStatus;
    private Text txtOutput;
    private Project currentProject;

    public BuildPage(TrystWorkbenchEditor editor) {
        super(ID, "Build", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        Group infoGroup = new Group(parent, SWT.NONE);
        infoGroup.setText("Build System Configuration");
        infoGroup.setLayout(new GridLayout(2, false));
        infoGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createRow(infoGroup, "Detected Build System:");
        lblBuildSystem = createValueLabel(infoGroup, "Detecting...");

        createRow(infoGroup, "Configuration File:");
        lblConfigFile = createValueLabel(infoGroup, "-");

        createRow(infoGroup, "Last Build Status:");
        lblStatus = createValueLabel(infoGroup, "IDLE");

        Group logGroup = new Group(parent, SWT.NONE);
        logGroup.setText("Build Output");
        logGroup.setLayout(new GridLayout(1, false));
        logGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        txtOutput = new Text(logGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        txtOutput.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        txtOutput.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
        txtOutput.setText("No active build execution in progress.\n");

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh Build Status");
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
    public void onContextChanged(WorkbenchContext context) {
        this.currentProject = context.project();
        refresh();
    }

    @Override
    public void refresh() {
        if (lblBuildSystem == null || lblBuildSystem.isDisposed()) return;
        if (currentProject == null) {
            WorkspaceService ws = TrystUiServices.getWorkspaceService();
            if (ws != null && ws.getCurrentWorkspace() != null) {
                currentProject = ws.getCurrentWorkspace().getActiveProject().orElse(null);
            }
        }

        if (currentProject != null && currentProject.getLocation() != null && Files.exists(currentProject.getLocation())) {
            DefaultBuildTechnologyDetector detector = new DefaultBuildTechnologyDetector();
            BuildSystemType buildType = detector.detectBuildSystem(currentProject.getLocation());
            lblBuildSystem.setText(buildType != null ? buildType.name() : "UNKNOWN");
            var config = detector.loadConfiguration(currentProject.getLocation());
            if (config != null && config.buildCommand() != null && !config.buildCommand().isEmpty()) {
                lblConfigFile.setText(String.join(" ", config.buildCommand()));
            } else {
                lblConfigFile.setText(buildType != null && buildType != BuildSystemType.UNKNOWN ? "Standard (" + buildType.name() + ")" : "None");
            }
        } else {
            lblBuildSystem.setText("No active project");
            lblConfigFile.setText("-");
        }
    }
}
