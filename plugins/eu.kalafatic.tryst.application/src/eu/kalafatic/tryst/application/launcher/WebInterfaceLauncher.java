package eu.kalafatic.tryst.application.launcher;

import eu.kalafatic.tryst.application.ApplicationWorkbenchAdvisor;
import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.config.ApplicationConfiguration;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Shell;

/**
 * Single entry point for opening the TRYST web interface in the external browser
 * (Family UX standard v1.0). All menu actions and context contributions share
 * {@link #open()} / {@link #open(Shell)} so the runtime endpoint resolution
 * (authoritative {@code RuntimeEndpointRegistry} with port-registry fallbacks) exists
 * in exactly one place; the HTTP port is never hardcoded.
 */
public final class WebInterfaceLauncher {

    private WebInterfaceLauncher() {
    }

    /**
     * Opens the web interface in the default browser. UI-less variant returning success;
     * failures are only logged.
     */
    public static boolean open() {
        return open(null);
    }

    /**
     * Opens the web interface in the default browser, reporting failures on the given
     * shell (when available).
     */
    public static boolean open(Shell shell) {
        String url = resolveUrl();
        boolean success = Program.launch(url);
        if (!success) {
            TrystLog.warn(WebInterfaceLauncher.class, "Failed to launch external browser for: " + url);
            if (shell != null && !shell.isDisposed()) {
                MessageDialog.openInformation(shell, "TRYST Web", "Server running at: " + url);
            }
        }
        return success;
    }

    /**
     * Opens the given web-interface URL (relative path such as {@code /help.html#top} or
     * {@code /api/status}) in the default browser, reporting failures on the given shell.
     */
    public static boolean openPath(Shell shell, String relativePath) {
        String url = resolveUrl() + (relativePath != null ? relativePath : "");
        boolean success = Program.launch(url);
        if (!success) {
            TrystLog.warn(WebInterfaceLauncher.class, "Failed to launch external browser for: " + url);
            if (shell != null && !shell.isDisposed()) {
                MessageDialog.openInformation(shell, "TRYST Web", "Page available at: " + url);
            }
        }
        return success;
    }

    /**
     * Resolves the live base URL of the TRYST web interface without hardcoding a port:
     * authoritative runtime endpoint registry first (bound socket), then the running HTTP
     * listener, then the coherent port registry, then configuration fallbacks.
     */
    public static String resolveUrl() {
        ApplicationLifecycleCoordinator coord = ApplicationWorkbenchAdvisor.resolveCoordinator();
        if (coord != null) {
            if (coord.getConfigurationService() != null) {
                var reg = coord.getConfigurationService().getEndpointRegistry();
                if (reg != null && reg.getCurrentEndpoint() != null) {
                    var ep = reg.getCurrentEndpoint();
                    if (ep.externalUrl() != null && !ep.externalUrl().isBlank()) return ep.externalUrl();
                    if (ep.internalUrl() != null && !ep.internalUrl().isBlank()) return ep.internalUrl();
                    if (ep.canonicalUrl() != null && !ep.canonicalUrl().isBlank()) return ep.canonicalUrl();
                }
                if (coord.getConfiguration() != null) {
                    ApplicationConfiguration cfg = coord.getConfiguration();
                    if (coord.getHttpService() != null && coord.getHttpService().isRunning()) {
                        return cfg.resolveEffectiveExternalUrl(coord.getHttpService().getPort());
                    }
                    // Live port from the authoritative runtime port registry (bound socket
                    // or coherent allocation) instead of a hardcoded listener port.
                    var portRegistry = coord.getConfigurationService().getPortRegistry();
                    if (portRegistry != null) {
                        return "http://127.0.0.1:" + portRegistry.getEffectivePort(
                                eu.kalafatic.tryst.core.port.ServicePortIdentifier.HTTP_SERVICE)
                                + cfg.contextPath();
                    }
                    if (cfg.canonicalUrl() != null) {
                        return cfg.canonicalUrl();
                    }
                }
            }
        }
        return "http://127.0.0.1:" + eu.kalafatic.tryst.core.port.ServicePortIdentifier.DEFAULT_HTTP_PORT + "/tryst";
    }
}
