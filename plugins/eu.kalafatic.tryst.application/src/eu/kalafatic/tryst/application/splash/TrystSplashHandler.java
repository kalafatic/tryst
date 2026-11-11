package eu.kalafatic.tryst.application.splash;

import eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleEvent;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleListener;
import eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage;
import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.branding.IProductConstants;
import org.eclipse.ui.splash.BasicSplashHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

/**
 * Enhanced TRYST Splash Handler providing:
 * 1. Visible startup progress bar and dynamic stage updates linked to real lifecycle events.
 * 2. Application version dynamically retrieved from OSGi bundle metadata in the top-right corner.
 */
public class TrystSplashHandler extends BasicSplashHandler implements ApplicationLifecycleListener {

    private Label versionLabel;
    private Font versionFont;
    private IProgressMonitor monitor;
    private ApplicationLifecycleCoordinator coordinator;

    @Override
    public void init(Shell splash) {
        super.init(splash);

        // Configure message & progress coordinates
        String progressRectString = "40,240,420,14";
        String messageRectString = "40,218,420,18";
        String foregroundColorString = "94A3B8";

        IProduct product = Platform.getProduct();
        if (product != null) {
            String pr = product.getProperty(IProductConstants.STARTUP_PROGRESS_RECT);
            if (pr != null) progressRectString = pr;
            String mr = product.getProperty(IProductConstants.STARTUP_MESSAGE_RECT);
            if (mr != null) messageRectString = mr;
            String fg = product.getProperty(IProductConstants.STARTUP_FOREGROUND_COLOR);
            if (fg != null) foregroundColorString = fg;
        }

        setProgressRect(StringConverter.asRectangle(progressRectString, new Rectangle(40, 240, 420, 14)));
        setMessageRect(StringConverter.asRectangle(messageRectString, new Rectangle(40, 218, 420, 18)));

        int fgInt;
        try {
            fgInt = Integer.parseInt(foregroundColorString, 16);
        } catch (Exception ex) {
            fgInt = 0x94A3B8;
        }
        setForeground(new RGB((fgInt >> 16) & 0xFF, (fgInt >> 8) & 0xFF, fgInt & 0xFF));

        this.monitor = getBundleProgressMonitor();
        if (monitor != null) {
            monitor.beginTask("Initializing TRYST Platform...", 100);
            monitor.worked(10);
        }

        // Render dynamic OSGi version in top-right corner
        createVersionLabel(splash);

        // Register to lifecycle coordinator for real progress updates
        connectLifecycleCoordinator();
    }

    private void createVersionLabel(Shell splash) {
        try {
            String versionStr = resolveBundleVersion();
            versionLabel = new Label(getContent(), SWT.RIGHT);
            versionLabel.setText("v" + versionStr);
            versionLabel.setForeground(getForeground());

            Display display = splash.getDisplay();
            FontData[] fontData = splash.getFont().getFontData();
            for (FontData fd : fontData) {
                fd.setHeight(8);
                fd.setStyle(SWT.BOLD);
            }
            versionFont = new Font(display, fontData);
            versionLabel.setFont(versionFont);

            // Position at top-right corner of splash
            Rectangle splashBounds = splash.getBounds();
            int width = 120;
            int height = 18;
            int x = Math.max(10, splashBounds.width - width - 15);
            int y = 12;
            versionLabel.setBounds(x, y, width, height);
        } catch (Throwable t) {
            TrystLog.warn(TrystSplashHandler.class, "Failed to render version label on splash: " + t.getMessage());
        }
    }

    private String resolveBundleVersion() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(TrystSplashHandler.class);
            if (bundle != null) {
                Version ver = bundle.getVersion();
                if (ver != null) {
                    return ver.getMajor() + "." + ver.getMinor() + "." + ver.getMicro();
                }
            }
        } catch (Throwable ignored) {
        }
        return "0.1.0";
    }

    private void connectLifecycleCoordinator() {
        try {
            coordinator = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
            if (coordinator != null) {
                coordinator.addLifecycleListener(this);
                // Sync current state
                onLifecycleEvent(ApplicationLifecycleEvent.of(coordinator.getCurrentStage()));
            }
        } catch (Throwable t) {
            TrystLog.warn(TrystSplashHandler.class, "Could not hook lifecycle listener on splash: " + t.getMessage());
        }
    }

    @Override
    public void onLifecycleEvent(ApplicationLifecycleEvent event) {
        if (monitor == null || event == null || event.stage() == null) return;

        ApplicationLifecycleStage toStage = event.stage();
        int targetWork = switch (toStage) {
            case PLATFORM_INITIALIZATION -> 20;
            case CONFIGURATION_INITIALIZATION -> 40;
            case CORE_SERVICE_INITIALIZATION -> 65;
            case APPLICATION_READY -> 85;
            case WORKBENCH_RUNNING -> 100;
            default -> 10;
        };

        String message = switch (toStage) {
            case PLATFORM_INITIALIZATION -> "Initializing platform and target configurations...";
            case CONFIGURATION_INITIALIZATION -> "Loading preferences and network configurations...";
            case CORE_SERVICE_INITIALIZATION -> "Starting core background services and database...";
            case APPLICATION_READY -> "Starting embedded HTTP server & API listeners...";
            case WORKBENCH_RUNNING -> "Opening central workspace and layout...";
            default -> "Starting application...";
        };

        Shell splash = getSplash();
        if (splash != null && !splash.isDisposed()) {
            Display display = splash.getDisplay();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (monitor != null && !splash.isDisposed()) {
                        monitor.subTask(message);
                        monitor.internalWorked(targetWork);
                        if (toStage == ApplicationLifecycleStage.WORKBENCH_RUNNING) {
                            monitor.done();
                        }
                    }
                });
            }
        }
    }

    @Override
    public void dispose() {
        if (coordinator != null) {
            coordinator.removeLifecycleListener(this);
            coordinator = null;
        }
        if (versionFont != null && !versionFont.isDisposed()) {
            versionFont.dispose();
            versionFont = null;
        }
        super.dispose();
    }
}
