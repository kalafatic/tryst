package eu.kalafatic.tryst.ui.view;

import eu.kalafatic.tryst.core.config.RuntimeEndpoint;
import eu.kalafatic.tryst.core.config.RuntimeEndpointRegistry;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.ui.TrystUiServices;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;

import java.util.function.Consumer;

/**
 * Dedicated Embedded SWT Web Browser administration view for TRYST.
 * Connects automatically to the active runtime endpoint without hardcoded port assumptions.
 */
public class EmbeddedBrowserView extends ViewPart {

    public static final String ID = "eu.kalafatic.tryst.ui.view.embeddedBrowser";

    private Browser browser;
    private Text urlText;
    private Label statusLabel;
    private Consumer<RuntimeEndpoint> endpointListener;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(4, false));

        Label lblUrl = new Label(parent, SWT.NONE);
        lblUrl.setText("Server URL:");

        urlText = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnNavigate = new Button(parent, SWT.PUSH);
        btnNavigate.setText("Go");
        btnNavigate.addListener(SWT.Selection, e -> navigateToUrl(urlText.getText()));

        Button btnRefresh = new Button(parent, SWT.PUSH);
        btnRefresh.setText("Refresh");
        btnRefresh.addListener(SWT.Selection, e -> {
            if (browser != null && !browser.isDisposed()) {
                browser.refresh();
            }
        });

        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 4, 1));
        statusLabel.setText("Initializing embedded browser...");

        try {
            browser = new Browser(parent, SWT.NONE);
            browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1));
        } catch (Throwable t) {
            TrystLog.warn(EmbeddedBrowserView.class, "SWT Browser widget creation unavailable: " + t.getMessage());
            Label errLabel = new Label(parent, SWT.WRAP);
            errLabel.setText("Embedded Web Browser could not be created in this environment: " + t.getMessage());
            errLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1));
        }

        resolveAndNavigate();
        listenToEndpointUpdates();
    }

    private void resolveAndNavigate() {
        RuntimeEndpointRegistry reg = TrystUiServices.getService(RuntimeEndpointRegistry.class);
        String targetUrl = "http://127.0.0.1:8080/tryst";
        if (reg != null && reg.getCurrentEndpoint() != null) {
            RuntimeEndpoint ep = reg.getCurrentEndpoint();
            targetUrl = resolveBestUrl(ep);
        }

        urlText.setText(targetUrl);
        navigateToUrl(targetUrl);
    }

    private String resolveBestUrl(RuntimeEndpoint ep) {
        if (ep == null) return "http://127.0.0.1:8080/tryst";
        // Prefer canonical or external URL when available, otherwise internal URL
        if (ep.canonicalUrl() != null && !ep.canonicalUrl().isBlank()) {
            return ep.canonicalUrl();
        }
        if (ep.externalUrl() != null && !ep.externalUrl().isBlank()) {
            return ep.externalUrl();
        }
        return ep.internalUrl();
    }

    private void navigateToUrl(String url) {
        if (url == null || url.isBlank()) return;
        statusLabel.setText("Connected to: " + url);
        if (browser != null && !browser.isDisposed()) {
            browser.setUrl(url);
        }
    }

    private void listenToEndpointUpdates() {
        RuntimeEndpointRegistry reg = TrystUiServices.getService(RuntimeEndpointRegistry.class);
        if (reg != null) {
            endpointListener = endpoint -> {
                if (urlText != null && !urlText.isDisposed()) {
                    urlText.getDisplay().asyncExec(() -> {
                        if (!urlText.isDisposed()) {
                            String bestUrl = resolveBestUrl(endpoint);
                            urlText.setText(bestUrl);
                            navigateToUrl(bestUrl);
                        }
                    });
                }
            };
            reg.addEndpointListener(endpointListener);
        }
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        } else if (urlText != null && !urlText.isDisposed()) {
            urlText.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (endpointListener != null) {
            RuntimeEndpointRegistry reg = TrystUiServices.getService(RuntimeEndpointRegistry.class);
            if (reg != null) {
                reg.removeEndpointListener(endpointListener);
            }
        }
        super.dispose();
    }
}
