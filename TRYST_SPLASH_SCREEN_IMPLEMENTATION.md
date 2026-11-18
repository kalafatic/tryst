# TRYST Splash Screen Implementation

## 1. Product Splash Configuration
The standard Eclipse RCP splash screen for TRYST is configured in `product/eu.kalafatic.tryst.product/tryst.product` and declared in `plugins/eu.kalafatic.tryst.application/plugin.xml`:

```xml
<splash
   location="eu.kalafatic.tryst.application"
   startupProgressRect="40,240,420,15"
   startupMessageRect="40,220,420,20"
   startupForegroundColor="94A3B8" />
```

The product extension `eu.kalafatic.tryst.application.product` binds the custom splash handler
`eu.kalafatic.tryst.application.splash.TrystSplashHandler` through the
`org.eclipse.ui.splashHandlers` extension point (`splashHandlerProductBinding`).

## 2. Asset Characteristics
- **File**: `splash.bmp` located in the root of bundle `eu.kalafatic.tryst.application`.
- **Dimensions**: 500x330 pixels.
- **Color Depth**: 24-bit RGB Windows Bitmap (BMP) compatible with native OS platform loaders.
- **Visual Design**: Dark sleek background (`#181c24`), blue/indigo gradient accent bar, stylized dual polygon logo, product branding ("TRYST – AI Development Environment"), subtitle and description lines. The bottom area (below y=210) is intentionally clear for the startup message and progress bar.

## 3. Version Display (Upper-Left Corner)
- The application version is rendered **dynamically** in the upper-left corner of the splash by a paint listener on the splash content composite (transparent background, EVO pattern).
- `TrystSplashHandler.resolveApplicationVersion()` resolves the version from the product property, the product defining bundle manifest (`Bundle-Version`), the handler's own bundle manifest, in that order, with a static fallback. The version is **never hardcoded** in the artwork or the handler; the `.qualifier` suffix is stripped for display.

## 4. Startup Progress & Messages
- Progress is rendered with the native Eclipse splash progress monitor (`BasicSplashHandler.getBundleProgressMonitor()`), positioned by the product-configured rectangles.
- Every progress update is driven by a **real lifecycle milestone** published by `ApplicationLifecycleCoordinator`; there is no artificial animation:
  1. `PLATFORM_INITIALIZATION` – "Initializing TRYST..." (10%)
  2. `CONFIGURATION_INITIALIZATION` – "Loading application modules..." (25%)
  3. `CORE_SERVICE_INITIALIZATION` – "Initializing database..." (50%)
  4. Sub-milestone `notifyStartupProgress("Loading application modules...")` (60%)
  5. Sub-milestone `notifyStartupProgress("Starting embedded services...")` (70%)
  6. `APPLICATION_READY` – "Embedded services running." (80%)
  7. Sub-milestone `notifyStartupProgress("Initializing workbench...")` published by `ApplicationWorkbenchWindowAdvisor.postWindowCreate()` (90%)
  8. `WORKBENCH_RUNNING` – "Ready." (100%), the progress monitor completes and the splash closes.
- Updates are thread-safe: direct execution on the SWT UI thread, deferred execution with a `StartupRunnable` from other threads, and a forced repaint (`Composite.update()`) so progress stays visible while workbench initialization blocks the event loop.
- Progress is delta-based (`monitor.worked(delta)`), so the bar moves monotonically to the milestone's real percentage.

## 5. Lifecycle Coordination
- `TrystApplication.start()` creates the Display and runs the workbench; it no longer performs the core initialization itself.
- The Workbench creates the splash handler before invoking `WorkbenchAdvisor.initialize()`; the deterministic lifecycle startup (PLATFORM → CONFIG → CORE → APPLICATION_READY) therefore runs in `ApplicationWorkbenchAdvisor.initialize()` while the splash is visible, reporting each milestone live.
- On fatal initialization errors the advisor calls `configurer.emergencyClose()` and `TrystSplashHandler.closeSplashIfVisible()` so the application never exits with a frozen splash screen.
- The native Eclipse startup monitor disposes the splash when the workbench is fully running; the handler also closes itself at `WORKBENCH_RUNNING`.

## 6. Packaging & Lifecycle
- `splash.bmp` (plus `about.png`, `about.mappings` and the `tryst16/32/48/64.png` window icons) is explicitly listed in `plugins/eu.kalafatic.tryst.application/build.properties` in `bin.includes`.
- During startup, the Equinox native launcher displays `splash.bmp` immediately. The handler attaches to the lifecycle coordinator and shows the milestone progress described above. Once `PlatformUI.createAndRunWorkbench(...)` completes window initialization, the splash closes automatically without blocking background threads.
