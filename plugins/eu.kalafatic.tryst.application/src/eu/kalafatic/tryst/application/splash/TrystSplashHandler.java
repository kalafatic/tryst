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
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.branding.IProductConstants;
import org.eclipse.ui.internal.StartupThreading.StartupRunnable;
import org.eclipse.ui.splash.BasicSplashHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

/**
 * TRYST splash handler following the established Eclipse/EVO splash design principles:
 * <ul>
 *   <li>Version rendered dynamically from the product/plugin manifest in the upper-left corner
 *       via a paint listener (no hardcoded version, transparent background).</li>
 *   <li>Real startup progress connected to {@link ApplicationLifecycleCoordinator} milestones;
 *       every update is driven by an actual lifecycle transition, never by an artificial timer.</li>
 *   <li>Thread-safe updates: direct execution on the SWT UI thread, deferred execution (with a
 *       {@link StartupRunnable}) from other threads, plus a forced repaint so progress is
 *       visible even while the workbench initialization blocks the event loop.</li>
 *   <li>The splash closes itself as soon as the workbench is running; on fatal startup errors
 *       {@link #closeSplashIfVisible()} prevents a frozen splash window.</li>
 * </ul>
 */
public class TrystSplashHandler extends BasicSplashHandler implements ApplicationLifecycleListener {

    private static volatile TrystSplashHandler activeInstance;

    private ApplicationLifecycleCoordinator coordinator;
    private IProgressMonitor monitor;
    private Font versionFont;
    private Color foregroundColor;
    private int lastPercent = 0;
    private boolean finished = false;

    @Override
    public void init(Shell splash) {
        super.init(splash);
        activeInstance = this;

        configureRectsAndForeground();
        renderVersionLabel();
        monitor = getBundleProgressMonitor();
        if (monitor != null) {
            monitor.beginTask("Initializing TRYST...", 100);
        }

        connectLifecycleCoordinator();
    }

    private void configureRectsAndForeground() {
        IProduct product = Platform.getProduct();
        Rectangle progressRect = new Rectangle(40, 240, 420, 15);
        Rectangle messageRect = new Rectangle(40, 220, 420, 20);
        String foregroundColorString = "94A3B8";
        if (product != null) {
            String pr = product.getProperty(IProductConstants.STARTUP_PROGRESS_RECT);
            if (pr != null) {
                progressRect = StringConverter.asRectangle(pr, progressRect);
            }
            String mr = product.getProperty(IProductConstants.STARTUP_MESSAGE_RECT);
            if (mr != null) {
                messageRect = StringConverter.asRectangle(mr, messageRect);
            }
            String fg = product.getProperty(IProductConstants.STARTUP_FOREGROUND_COLOR);
            if (fg != null) {
                foregroundColorString = fg;
            }
        }
        setProgressRect(progressRect);
        setMessageRect(messageRect);

        int fgInt;
        try {
            fgInt = Integer.parseInt(foregroundColorString, 16);
        } catch (Exception ex) {
            fgInt = 0x94A3B8;
        }
        RGB rgb = new RGB((fgInt >> 16) & 0xFF, (fgInt >> 8) & 0xFF, fgInt & 0xFF);
        setForeground(rgb);
        foregroundColor = new Color(getSplash().getDisplay(), rgb);
    }

    /**
     * Resolves the TRYST application version dynamically, following the EVO resolution chain:
     * product property, product defining bundle manifest, own bundle manifest, then fallback.
     */
    public static String resolveApplicationVersion() {
        String version = null;
        try {
            IProduct product = Platform.getProduct();
            if (product != null) {
                version = product.getProperty("version");
                if (version == null || version.isBlank()) {
                    Bundle defining = product.getDefiningBundle();
                    if (defining != null) {
                        version = defining.getHeaders().get("Bundle-Version");
                        if (version == null || version.isBlank()) {
                            Version v = defining.getVersion();
                            if (v != null) {
                                version = v.toString();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Platform lookups can fail in non-OSGi test environments
        }
        if (version == null || version.isBlank()) {
            try {
                Bundle bundle = FrameworkUtil.getBundle(TrystSplashHandler.class);
                if (bundle != null) {
                    version = bundle.getHeaders().get("Bundle-Version");
                    if (version == null || version.isBlank()) {
                        Version v = bundle.getVersion();
                        if (v != null) {
                            version = v.toString();
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Framework lookups can fail outside OSGi
            }
        }
        if (version == null || version.isBlank()) {
            version = "2.6.11";
        }
        if (version.endsWith(".qualifier")) {
            version = version.substring(0, version.length() - ".qualifier".length());
        }
        return version;
    }

    /**
     * Draws "v X.Y.Z" in the upper-left corner of the splash content using a paint listener,
     * which guarantees a transparent background over the splash artwork (EVO pattern).
     */
    private void renderVersionLabel() {
        try {
            Shell splash = getSplash();
            if (splash == null || splash.isDisposed()) {
                return;
            }
            final String version = resolveApplicationVersion();

            final FontData[] fontData = splash.getFont().getFontData();
            for (FontData fd : fontData) {
                fd.setHeight(8);
                fd.setStyle(SWT.BOLD);
            }
            versionFont = new Font(splash.getDisplay(), fontData);
            final Font paintFont = versionFont;

            Composite content = getContent();
            if (content != null && !content.isDisposed()) {
                content.addPaintListener(new PaintListener() {
                    @Override
                    public void paintControl(PaintEvent e) {
                        Color fg = foregroundColor;
                        if (fg == null || fg.isDisposed()) {
                            fg = e.display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
                        }
                        e.gc.setForeground(fg);
                        e.gc.setFont(paintFont);
                        e.gc.drawText("v " + version, 14, 12, true);
                    }
                });
                content.redraw();
            }
        } catch (Throwable t) {
            TrystLog.warn(TrystSplashHandler.class, "Failed to render version label on splash: " + t.getMessage());
        }
    }

    private void connectLifecycleCoordinator() {
        try {
            coordinator = eu.kalafatic.tryst.application.TrystApplication.getCoordinator();
            if (coordinator != null) {
                coordinator.addLifecycleListener(this);
                // Catch up honestly with the current stage (e.g. relaunch or late binding).
                applyStage(coordinator.getCurrentStage());
            }
        } catch (Throwable t) {
            TrystLog.warn(TrystSplashHandler.class, "Could not hook lifecycle listener on splash: " + t.getMessage());
        }
    }

    @Override
    public void onLifecycleEvent(ApplicationLifecycleEvent event) {
        if (event == null || event.stage() == null) {
            return;
        }
        final ApplicationLifecycleStage stage = event.stage();
        final String description = event.description();

        Shell splash = getSplash();
        if (splash == null || splash.isDisposed()) {
            return;
        }
        Display display = splash.getDisplay();
        if (display.isDisposed()) {
            return;
        }

        Runnable update = () -> applyStage(stage, description);
        if (Thread.currentThread() == display.getThread()) {
            update.run();
        } else {
            // StartupRunnable ensures the update executes before the UI is fully initialized.
            try {
                display.asyncExec(new StartupRunnable() {
                    @Override
                    public void runWithException() {
                        update.run();
                    }
                });
            } catch (Throwable t) {
                display.asyncExec(update);
            }
        }
    }

    private void applyStage(ApplicationLifecycleStage stage) {
        applyStage(stage, null);
    }

    private void applyStage(ApplicationLifecycleStage stage, String description) {
        if (finished || monitor == null) {
            return;
        }

        int targetPercent;
        String message;
        switch (stage) {
            case PLATFORM_INITIALIZATION, APPLICATION_START -> {
                targetPercent = 10;
                message = "Initializing TRYST...";
            }
            case CONFIGURATION_INITIALIZATION -> {
                targetPercent = 25;
                message = "Loading application modules...";
            }
            case CORE_SERVICE_INITIALIZATION -> {
                targetPercent = 50;
                message = "Initializing database...";
            }
            case APPLICATION_READY -> {
                targetPercent = 80;
                message = "Embedded services running.";
            }
            case WORKBENCH_RUNNING -> {
                targetPercent = 100;
                message = "Ready.";
            }
            default -> {
                return;
            }
        }

        // Sub-milestone notifications carry a custom description with the current stage and
        // advance the bar by one step for each real initialization activity.
        boolean customDescription = description != null && !description.isBlank()
                && !description.equals(stage.name());
        if (customDescription) {
            message = description;
            if (stage != ApplicationLifecycleStage.WORKBENCH_RUNNING) {
                targetPercent = Math.min(lastPercent + 10, 95);
            }
        }

        Shell splash = getSplash();
        if (splash == null || splash.isDisposed()) {
            return;
        }

        try {
            if (targetPercent > lastPercent) {
                monitor.worked(targetPercent - lastPercent);
                lastPercent = targetPercent;
            }
            monitor.subTask(message);

            Composite content = getContent();
            if (content != null && !content.isDisposed()) {
                // Force a synchronous repaint so the update is visible even while the
                // workbench initialization is blocking the SWT event loop.
                content.update();
            }
        } catch (Throwable t) {
            TrystLog.debug(TrystSplashHandler.class, "Splash progress update skipped: " + t.getMessage());
        }

        if (stage == ApplicationLifecycleStage.WORKBENCH_RUNNING) {
            finished = true;
            try {
                monitor.done();
            } catch (Throwable ignored) {
            }
            closeSplash();
        }
    }

    private void closeSplash() {
        Shell splash = getSplash();
        if (splash == null || splash.isDisposed()) {
            return;
        }
        Display display = splash.getDisplay();
        Runnable close = () -> {
            if (splash != null && !splash.isDisposed()) {
                try {
                    splash.close();
                } catch (Throwable ignored) {
                }
            }
        };
        if (Thread.currentThread() == display.getThread()) {
            close.run();
        } else {
            display.asyncExec(close);
        }
    }

    /**
     * Force-closes the splash window from any thread. Called on fatal startup failures so the
     * application never exits leaving a frozen splash screen behind.
     */
    public static void closeSplashIfVisible() {
        TrystSplashHandler handler = activeInstance;
        if (handler != null) {
            handler.closeSplash();
        }
    }

    @Override
    public void dispose() {
        activeInstance = null;
        if (coordinator != null) {
            coordinator.removeLifecycleListener(this);
            coordinator = null;
        }
        if (versionFont != null && !versionFont.isDisposed()) {
            versionFont.dispose();
            versionFont = null;
        }
        if (foregroundColor != null && !foregroundColor.isDisposed()) {
            foregroundColor.dispose();
            foregroundColor = null;
        }
        super.dispose();
    }
}
