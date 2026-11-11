package eu.kalafatic.tryst.ui.editor.page;

import eu.kalafatic.tryst.domain.build.BuildAndQualityService;
import eu.kalafatic.tryst.domain.build.BuildSystemType;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.build.DefaultBuildTechnologyDetector;
import eu.kalafatic.tryst.ui.TrystUiServices;
import eu.kalafatic.tryst.ui.context.WorkbenchContext;
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

import java.nio.file.Files;
import java.time.Duration;

/**
 * Editor page for Build configuration, build system detection, and build triggering.
 * Enhanced with toolbar controls for run build, clean output, copy log, and test quality navigation.
 */
public class BuildPage extends AbstractTrystEditorPage {

    public static final String ID = "eu.kalafatic.tryst.ui.editor.page.build";

    private Label lblBuildSystem;
    private Label lblConfigFile;
    private Label lblStatus;
    private Text txtOutput;
    private Project currentProject;
    private Button btnRunBuild;
    private Button btnCleanOutput;
    private Button btnCopyOutput;

    public BuildPage(TrystWorkbenchEditor editor) {
        super(ID, "Build", editor);
    }

    @Override
    protected void createPageContent(Composite parent) {
        // Top Toolbar
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(6, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnRefresh = new Button(toolbar, SWT.PUSH);
        btnRefresh.setText("🔄 Refresh Build Status");
        btnRefresh.addListener(SWT.Selection, e -> refresh());

        btnRunBuild = new Button(toolbar, SWT.PUSH);
        btnRunBuild.setText("⚡ Run Project Build");
        btnRunBuild.setToolTipText("Trigger build execution for currently detected project");
        btnRunBuild.addListener(SWT.Selection, e -> onRunBuild());

        btnCleanOutput = new Button(toolbar, SWT.PUSH);
        btnCleanOutput.setText("🗑 Clear Log");
        btnCleanOutput.addListener(SWT.Selection, e -> txtOutput.setText(""));

        btnCopyOutput = new Button(toolbar, SWT.PUSH);
        btnCopyOutput.setText("📋 Copy Log");
        btnCopyOutput.addListener(SWT.Selection, e -> {
            Clipboard cb = new Clipboard(getControl().getDisplay());
            try {
                cb.setContents(new Object[]{txtOutput.getText()}, new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        });

        Button btnQuality = new Button(toolbar, SWT.PUSH);
        btnQuality.setText("🧪 Test Quality Reports");
        btnQuality.addListener(SWT.Selection, e -> editor.selectPage(TestQualityPage.ID));

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

        refresh();
    }

    private void onRunBuild() {
        if (currentProject != null && currentProject.getLocation() != null) {
            txtOutput.append("Executing build for project: " + currentProject.getName() + " at " + currentProject.getLocation() + "...\n");
            lblStatus.setText("RUNNING");
            BuildAndQualityService buildService = TrystUiServices.getService(BuildAndQualityService.class);
            WorkspaceService ws = TrystUiServices.getWorkspaceService();
            Workspace workspace = (ws != null) ? ws.getCurrentWorkspace() : null;

            if (buildService != null && workspace != null) {
                Thread.ofVirtual().start(() -> {
                    try {
                        var res = buildService.executeBuild(workspace, "manual-build", Duration.ofMinutes(5), () -> false, line -> {
                            if (!txtOutput.isDisposed()) {
                                txtOutput.getDisplay().asyncExec(() -> txtOutput.append(line + "\n"));
                            }
                        });
                        if (!txtOutput.isDisposed()) {
                            txtOutput.getDisplay().asyncExec(() -> {
                                lblStatus.setText(res.status().name());
                                txtOutput.append("Build Completed: " + res.status() + " in " + res.durationMs() + "ms\n");
                            });
                        }
                    } catch (Exception ex) {
                        if (!txtOutput.isDisposed()) {
                            txtOutput.getDisplay().asyncExec(() -> {
                                lblStatus.setText("FAILED");
                                txtOutput.append("Build Exception: " + ex.getMessage() + "\n");
                            });
                        }
                    }
                });
            } else {
                txtOutput.append("BuildAndQualityService or Workspace not available.\n");
                lblStatus.setText("UNAVAILABLE");
            }
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

    @Override
    public void onActivated() {
        refresh();
    }

    @Override
    public void onContextChanged(WorkbenchContext context) {
        super.onContextChanged(context);
        this.currentProject = context.project();
        refresh();
    }

    @Override
    public void refresh() {
        super.refresh();
        if (lblBuildSystem == null || lblBuildSystem.isDisposed()) return;
        if (currentProject == null) {
            WorkspaceService ws = TrystUiServices.getWorkspaceService();
            if (ws != null && ws.getCurrentWorkspace() != null) {
                currentProject = ws.getCurrentWorkspace().getActiveProject().orElse(null);
            }
        }

        if (currentProject != null && currentProject.getLocation() != null && Files.exists(currentProject.getLocation())) {
            btnRunBuild.setEnabled(true);
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
            btnRunBuild.setEnabled(false);
            lblBuildSystem.setText("No active project");
            lblConfigFile.setText("-");
        }
    }
}
