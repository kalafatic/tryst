package eu.kalafatic.tryst.application.help;

import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.net.URI;
import java.util.Arrays;

/**
 * Native Eclipse help/about/update support for the TRYST main menu.
 *
 * <p>All actions use standard Eclipse mechanisms:
 * <ul>
 *   <li>Help Contents opens the TRYST web help page served by the embedded HTTP server
 *       at the live runtime endpoint ({@link HelpLauncher}).</li>
 *   <li>About opens the native Eclipse About dialog (command
 *       {@code org.eclipse.ui.help.aboutAction}).</li>
 *   <li>Installation Details opens the native Eclipse Installation Details dialog (command
 *       {@code org.eclipse.ui.help.installationDialog}).</li>
 *   <li>Check for Updates performs a real p2 {@link UpdateOperation} against the configured
 *       update repositories of the provisioning profile and reports explicitly when no update
 *       repositories are configured.</li>
 *   <li>About Installation reports the real installation, configuration and runtime
 *       information of the running platform.</li>
 * </ul>
 */
public final class TrystHelpMenuSupport {

    public static final String COMMAND_ABOUT = "org.eclipse.ui.help.aboutAction";
    public static final String COMMAND_INSTALLATION_DETAILS = "org.eclipse.ui.help.installationDialog";
    public static final String COMMAND_CUSTOMIZE_PERSPECTIVE = "org.eclipse.ui.window.customizePerspective";
    public static final String COMMAND_INSTALL_NEW_SOFTWARE = "org.eclipse.equinox.p2.ui.sdk.install";
    public static final String SITES_PREFERENCE_PAGE = "org.eclipse.equinox.internal.p2.ui.sdk.SitesPreferencePage";

    private TrystHelpMenuSupport() {
    }

    /**
     * Creates the standard "About TRYST" action backed by the native Eclipse About dialog.
     */
    public static IAction createAboutAction(IWorkbenchWindow window) {
        Action action = new Action("&About TRYST") {
            @Override
            public void run() {
                runCommand(window, COMMAND_ABOUT, "About TRYST");
            }
        };
        action.setId("eu.kalafatic.tryst.application.action.aboutTryst");
        return action;
    }

    /**
     * Creates the "Installation Details" contribution backed by the native Eclipse
     * Installation Details dialog.
     */
    public static CommandContributionItem createInstallationDetailsItem(IWorkbenchWindow window) {
        CommandContributionItemParameter parameter = new CommandContributionItemParameter(
                window, null, COMMAND_INSTALLATION_DETAILS, CommandContributionItem.STYLE_PUSH);
        parameter.label = "&Installation Details";
        return new CommandContributionItem(parameter);
    }

    /**
     * Creates the "Install New Software..." contribution backed by the standard Eclipse p2
     * install wizard (org.eclipse.equinox.p2.ui.sdk).
     */
    public static CommandContributionItem createInstallNewSoftwareItem(IWorkbenchWindow window) {
        CommandContributionItemParameter parameter = new CommandContributionItemParameter(
                window, null, COMMAND_INSTALL_NEW_SOFTWARE, CommandContributionItem.STYLE_PUSH);
        parameter.label = "Install New &Software...";
        return new CommandContributionItem(parameter);
    }

    /**
     * Creates the "Manage Software Sites..." action opening the standard Eclipse p2
     * "Available Software Sites" preference page.
     */
    public static IAction createManageSoftwareSitesAction(IWorkbenchWindow window) {
        Action action = new Action("Manage Software &Sites...") {
            @Override
            public void run() {
                Shell shell = window.getShell();
                if (shell == null || shell.isDisposed()) {
                    return;
                }
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                        shell, SITES_PREFERENCE_PAGE, null, null).open();
            }
        };
        action.setId("eu.kalafatic.tryst.application.action.manageSoftwareSites");
        return action;
    }

    /**
     * Creates the "Customize Perspective..." contribution backed by the native Eclipse
     * perspective customization dialog.
     */
    public static CommandContributionItem createCustomizePerspectiveItem(IWorkbenchWindow window) {
        CommandContributionItemParameter parameter = new CommandContributionItemParameter(
                window, null, COMMAND_CUSTOMIZE_PERSPECTIVE, CommandContributionItem.STYLE_PUSH);
        parameter.label = "&Customize Perspective...";
        return new CommandContributionItem(parameter);
    }

    /**
     * Creates the "Help Contents" action. Opens the TRYST web help page
     * ({@code <context>/help.html#top}) served by the embedded HTTP server, using the
     * live runtime endpoint so no HTTP port is ever hardcoded.
     */
    public static IAction createHelpContentsAction(IWorkbenchWindow window) {
        Action action = new Action("&Help Contents") {
            @Override
            public void run() {
                Shell shell = window.getShell();
                if (shell == null || shell.isDisposed()) {
                    return;
                }
                try {
                    HelpLauncher.openTopic(window, HelpLauncher.ANCHOR_TOP);
                } catch (Throwable t) {
                    TrystLog.warn(TrystHelpMenuSupport.class, "Failed to open help contents: " + t.getMessage());
                    MessageDialog.openInformation(shell, "Help Contents",
                            "TRYST help could not be opened:\n\n" + t.getMessage());
                }
            }
        };
        action.setId("eu.kalafatic.tryst.application.action.helpContents");
        return action;
    }

    /**
     * Creates the "Check for Updates" action. Performs a real p2 update check against the
     * update repositories of the provisioning profile; reports explicitly when no update
     * repositories are configured or when the p2 UI is not part of the installation.
     */
    public static IAction createCheckForUpdatesAction(IWorkbenchWindow window) {
        Action action = new Action("Check for &Updates") {
            @Override
            public void run() {
                Shell shell = window.getShell();
                if (shell == null || shell.isDisposed()) {
                    return;
                }
                try {
                    ProvisioningUI ui = ProvisioningUI.getDefaultUI();
                    if (ui == null) {
                        MessageDialog.openInformation(shell, "Check for Updates",
                                "The Eclipse provisioning (p2) user interface is not available "
                                        + "in this TRYST installation.\n\nUpdates cannot be checked.");
                        return;
                    }
                    URI[] repositories = ui.getRepositoryTracker().getKnownRepositories(ui.getSession());
                    if (repositories == null || repositories.length == 0) {
                        MessageDialog.openInformation(shell, "Check for Updates",
                                "No update repositories are configured for this TRYST installation.\n\n"
                                        + "Updates cannot be checked. Update repositories are managed "
                                        + "through the p2 provisioning configuration of the installation.");
                        return;
                    }
                    UpdateOperation operation = ui.getUpdateOperation(null, repositories);
                    IStatus status = operation.resolveModal(null);
                    if (status.getSeverity() == IStatus.ERROR) {
                        MessageDialog.openError(shell, "Check for Updates",
                                "The update check failed:\n\n" + status.getMessage());
                        return;
                    }
                    if (operation.getSelectedUpdates() != null && operation.getSelectedUpdates().length > 0) {
                        ui.openUpdateWizard(false, operation, null);
                    } else {
                        MessageDialog.openInformation(shell, "Check for Updates",
                                "There are no updates available.");
                    }
                } catch (Throwable t) {
                    TrystLog.error(TrystHelpMenuSupport.class, "Update check failed: " + t.getMessage(), t);
                    MessageDialog.openError(shell, "Check for Updates",
                            "Checking for updates failed:\n\n" + t.getMessage());
                }
            }
        };
        action.setId("eu.kalafatic.tryst.application.action.checkForUpdates");
        return action;
    }

    /**
     * Creates the "About Installation" action reporting the real installation, configuration
     * and runtime information of the running TRYST platform.
     */
    public static IAction createAboutInstallationAction(IWorkbenchWindow window) {
        Action action = new Action("&About Installation") {
            @Override
            public void run() {
                Shell shell = window.getShell();
                if (shell == null || shell.isDisposed()) {
                    return;
                }
                MessageDialog.openInformation(shell, "About Installation", collectInstallationInfo());
            }
        };
        action.setId("eu.kalafatic.tryst.application.action.aboutInstallation");
        return action;
    }

    private static String collectInstallationInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            org.eclipse.core.runtime.IProduct product = Platform.getProduct();
            if (product != null) {
                sb.append("Product: ").append(product.getName()).append('\n');
                Bundle defining = product.getDefiningBundle();
                if (defining != null && defining.getVersion() != null) {
                    sb.append("Version: ").append(formatVersion(defining.getVersion().toString())).append('\n');
                }
            }
            sb.append('\n');
            sb.append("Install location: ").append(Platform.getInstallLocation().getURL().getPath()).append('\n');
            sb.append("Configuration area: ").append(Platform.getConfigurationLocation().getURL().getPath()).append('\n');
            try {
                sb.append("Instance area: ").append(Platform.getInstanceLocation().getURL().getPath()).append('\n');
            } catch (IllegalStateException noInstanceArea) {
                sb.append("Instance area: not set\n");
            }
            sb.append('\n');
            sb.append("Java runtime: ").append(System.getProperty("java.version"))
                    .append(" (").append(System.getProperty("java.vendor")).append(")\n");
            Bundle runtime = Platform.getBundle("org.eclipse.core.runtime");
            sb.append("Eclipse platform: ").append(runtime != null && runtime.getVersion() != null
                    ? runtime.getVersion().toString() : "unknown").append('\n');
            sb.append("Operating system: ").append(System.getProperty("os.name"))
                    .append(' ').append(System.getProperty("os.version"))
                    .append(" (").append(System.getProperty("os.arch")).append(")\n");
            try {
                Bundle self = FrameworkUtil.getBundle(TrystHelpMenuSupport.class);
                if (self != null && self.getBundleContext() != null) {
                    sb.append("Bundles installed: ").append(self.getBundleContext().getBundles().length).append('\n');
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            sb.append("(Installation information unavailable: ").append(t.getMessage()).append(')');
        }
        return sb.toString();
    }

    private static String formatVersion(String version) {
        if (version != null && version.endsWith(".qualifier")) {
            return version.substring(0, version.length() - ".qualifier".length());
        }
        return version;
    }

    /**
     * Executes a workbench command by id, reporting any failure explicitly.
     */
    public static void runCommand(IWorkbenchWindow window, String commandId, String displayName) {
        try {
            org.eclipse.ui.commands.ICommandService commandService = window.getService(org.eclipse.ui.commands.ICommandService.class);
            if (commandService == null) {
                MessageDialog.openError(window.getShell(), displayName,
                        "The command service is not available in this workbench.");
                return;
            }
            org.eclipse.core.commands.Command command = commandService.getCommand(commandId);
            if (command == null || !command.isDefined()) {
                MessageDialog.openError(window.getShell(), displayName,
                        "The requested command is not available in this installation: " + commandId);
                return;
            }
            org.eclipse.core.expressions.IEvaluationContext context = null;
            org.eclipse.ui.services.IEvaluationService evaluationService =
                    window.getService(org.eclipse.ui.services.IEvaluationService.class);
            if (evaluationService != null) {
                context = evaluationService.getCurrentState();
            }
            command.executeWithChecks(new org.eclipse.core.commands.ExecutionEvent(
                    null, java.util.Collections.emptyMap(), null, context));
        } catch (Throwable t) {
            TrystLog.error(TrystHelpMenuSupport.class, "Command execution failed for " + commandId + ": " + t.getMessage(), t);
            MessageDialog.openError(window.getShell(), displayName,
                    "The requested operation could not be performed:\n\n" + t.getMessage());
        }
    }

    /**
     * Reports whether the provisioning session has any configured update repositories.
     */
    public static boolean hasConfiguredUpdateRepositories() {
        try {
            ProvisioningUI ui = ProvisioningUI.getDefaultUI();
            if (ui == null) {
                return false;
            }
            URI[] repositories = ui.getRepositoryTracker().getKnownRepositories(ui.getSession());
            return repositories != null && repositories.length > 0
                    && Arrays.stream(repositories).anyMatch(java.util.Objects::nonNull);
        } catch (Throwable t) {
            return false;
        }
    }
}
