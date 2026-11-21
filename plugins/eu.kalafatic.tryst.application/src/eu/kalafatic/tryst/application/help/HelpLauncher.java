package eu.kalafatic.tryst.application.help;

import eu.kalafatic.tryst.application.launcher.WebInterfaceLauncher;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web-based help navigation helper (Family UX standard v1.0).
 *
 * <p>The TRYST documentation is served by the embedded HTTP server as the static asset
 * {@code /web/help.html} (context path {@code /tryst/help.html}) and is opened in the
 * external browser. This launcher maps workbench parts to {@code #context-*} anchors and
 * exposes the shared entry points used by the Help menu and the F1 key binding.
 */
public final class HelpLauncher {

    /** Help anchors defined by the static {@code help.html} asset. */
    public static final String ANCHOR_TOP = "top";
    public static final String ANCHOR_GETTING_STARTED = "getting-started";
    public static final String ANCHOR_CONTEXT = "context";
    public static final String ANCHOR_SHORTCUTS = "shortcuts";
    public static final String ANCHOR_CLI = "cli";
    public static final String ANCHOR_DIAGNOSTICS = "diagnostics";

    private static final Map<String, String> PART_TO_ANCHOR = new LinkedHashMap<>();

    static {
        // Major views and editor pages documented in help.html#context-* subsections.
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.navigator.view", ANCHOR_CONTEXT + "-navigator");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.projectExplorer", ANCHOR_CONTEXT + "-navigator");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.consoleOutput", ANCHOR_CONTEXT + "-console");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.editor.workbenchEditor", ANCHOR_CONTEXT + "-studio");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.databaseAdmin", ANCHOR_CONTEXT + "-database");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.usersAndSessions", ANCHOR_CONTEXT + "-administration");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.userDetail", ANCHOR_CONTEXT + "-administration");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.sessionDetail", ANCHOR_CONTEXT + "-administration");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.auditAndLogs", ANCHOR_CONTEXT + "-administration");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.tasksAndAgents", ANCHOR_CONTEXT + "-administration");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.embeddedBrowser", ANCHOR_CONTEXT + "-browser");
        PART_TO_ANCHOR.put("eu.kalafatic.tryst.ui.view.serverOverview", ANCHOR_CONTEXT + "-browser");
    }

    private HelpLauncher() {
    }

    /**
     * Opens the help page at the given anchor in the external browser.
     */
    public static void openTopic(IWorkbenchWindow window, String anchor) {
        openTopic(window != null ? window.getShell() : null, anchor);
    }

    /**
     * Opens the help page at the given anchor in the external browser.
     */
    public static void openTopic(Shell shell, String anchor) {
        String topic = (anchor == null || anchor.isBlank()) ? ANCHOR_TOP : anchor;
        WebInterfaceLauncher.openPath(shell, "/help.html#" + topic);
    }

    /**
     * Resolves the help anchor for the active workbench part id. Unknown parts fall back
     * to the generic context section (same behavior as F1).
     */
    public static String contextHelpFor(String activePartId) {
        if (activePartId == null || activePartId.isBlank()) {
            return ANCHOR_CONTEXT;
        }
        String anchor = PART_TO_ANCHOR.get(activePartId);
        if (anchor == null && activePartId.startsWith("eu.kalafatic.tryst.ui.view.")) {
            anchor = ANCHOR_CONTEXT;
        }
        return anchor != null ? anchor : ANCHOR_CONTEXT;
    }

    /**
     * Opens context help for the currently active part (F1 / "Context Help" menu entry).
     */
    public static void openContextHelp(IWorkbenchWindow window) {
        String anchor = ANCHOR_CONTEXT;
        Shell shell = window != null ? window.getShell() : null;
        try {
            if (window != null) {
                IWorkbenchPage page = window.getActivePage();
                if (page != null) {
                    IWorkbenchPart part = page.getActivePart();
                    if (part != null && part.getSite() != null) {
                        anchor = contextHelpFor(part.getSite().getId());
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(HelpLauncher.class, "Could not resolve active part for context help: " + t.getMessage());
        }
        openTopic(shell, anchor);
    }
}
