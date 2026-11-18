# TRYST RCP Startup Architecture

## 1. Overview
TRYST is an autonomous AI software development platform built upon the native Eclipse RCP / Equinox OSGi framework. The platform provides two officially supported runtime startup modes from a single unified product distribution:
1. **Standard Eclipse RCP desktop application (default)**: Starts the Equinox OSGi runtime, presents a branded Eclipse splash screen with live startup progress, starts background server services, and initializes the SWT Workbench window with the TRYST Main perspective.
2. **Optional Headless server mode (`-headless` or `tryst.headless=true`)**: Starts the Equinox OSGi runtime and identical background server services, persistence, authentication, and HTTP/TCP networking without creating an SWT `Display`, `Shell`, or Workbench window.

## 2. Architectural Convergence

```text
                 TRYST Product Launcher (tryst / tryst.exe)
                                    |
                         Startup Mode Selection
                                    |
             +----------------------+----------------------+
             |                                             |
       STANDARD RCP                                    HEADLESS
       (default)                                       (-headless)
             |                                             |
    Eclipse Splash (splash.bmp)                      No SWT Display
    + splash handler with live                       OSGi Runtime
      startup progress                                |
             |                                        |
    WorkbenchAdvisor.initialize()                     |
    (lifecycle stages with visible                    |
     milestone progress)                              |
             |                                        |
       SWT Workbench Window                           |
             |                                        |
             +----------------------+----------------------+
                                    |
                       TRYST Application Lifecycle
                     (ApplicationLifecycleCoordinator)
                                    |
       +----------------------------+----------------------------+
       |                            |                            |
   Persistence                Authentication                 Networking
  (tryst.db SQLite)          (PBKDF2 / RBAC / Sessions)     (HTTP & TCP Servers)
       |                            |                            |
       +----------------------------+----------------------------+
                                    |
                           ServerReadinessService
                                    |
                           RuntimeEndpointRegistry
                                    |
                         TRYST Ready (http://localhost/tryst)
```

## 3. Mode Selection Mechanism
The primary application entry point `eu.kalafatic.tryst.application.TrystApplication` implements `org.eclipse.equinox.app.IApplication`.
Upon invocation of `start(IApplicationContext context)`:
- System properties are inspected for `tryst.headless=true`.
- Program arguments from `context.getArguments().get(IApplicationContext.APPLICATION_ARGS)` are scanned for `-headless` or `--headless`.
- If headless is requested, execution delegates directly to `TrystServerApplication.runHeadless(...)`, which performs the lifecycle transitions and stops on a shutdown latch.
- In standard RCP mode, `PlatformUI.createDisplay()` and `PlatformUI.createAndRunWorkbench(...)` are invoked.

## 4. Standard RCP Startup Sequence (Splash-visible progress)
1. The native launcher shows `splash.bmp` while the OSGi runtime resolves bundles and starts the application.
2. `TrystApplication.start()` resolves the `ApplicationLifecycleCoordinator` from the plugin activator and creates the SWT Display.
3. `PlatformUI.createAndRunWorkbench(...)` runs the 3.x-compat workbench:
   a. `Workbench.init()` creates the splash wrapper and the product-bound `TrystSplashHandler` and calls `TrystSplashHandler.init(shell)` – the version is painted in the upper-left corner and the progress monitor is attached to the lifecycle coordinator.
   b. `ApplicationWorkbenchAdvisor.initialize()` advances the lifecycle deterministically (PLATFORM_INITIALIZATION → CONFIGURATION_INITIALIZATION → CORE_SERVICE_INITIALIZATION → APPLICATION_READY). Every transition and the sub-milestone notifications (`Loading application modules...`, `Starting embedded services...`) are painted on the splash as real progress.
   c. The first workbench window is created; `ApplicationWorkbenchWindowAdvisor.postWindowCreate()` publishes `Initializing workbench...`.
   d. `postStartup()` transitions to `WORKBENCH_RUNNING`, the splash shows "Ready." and closes.
4. On a fatal core initialization error, the advisor logs the failure, closes the splash (`TrystSplashHandler.closeSplashIfVisible()`) and calls `configurer.emergencyClose()` so the application never continues with half-initialized services or a frozen splash.

The headless path (`TrystServerApplication.runHeadless`) performs the identical lifecycle transitions without any SWT dependency and is not affected by the splash integration.

## 5. Main Menu
The workbench main menu is provided by `ApplicationActionBarAdvisor` using the standard Eclipse RCP action/command infrastructure:
- **File** – New Task..., Open TRYST Web Client, Refresh System Status, Exit (only actions that are functional in the TRYST context).
- **Edit** – Undo, Redo, Cut, Copy, Paste, Select All, Preferences (standard `ActionFactory` commands with focus-based enablement).
- **Navigate** – Back, Forward (workbench navigation history), TRYST Navigator/Console views, Open Central Workspace, Open TRYST Web Client.
- **Project / Tasks / Database / TEIP / Web / Observability** – existing TRYST domain menus (unchanged).
- **Window** – Open Perspective, Show View, Reset Perspective, Customize Perspective (native command), Preferences.
- **Help** – Help Contents (native help system with explicit "documentation not yet available" reporting), About TRYST (native Eclipse About dialog), Installation Details (native dialog), Check for Updates (real p2 update check with explicit "no update repositories configured" reporting), About Installation.

The Help menu relies on the native Eclipse infrastructure: the About and Installation Details commands (`org.eclipse.ui.help.aboutAction`, `org.eclipse.ui.help.installationDialog`) with their platform handlers, the Eclipse help system (`org.eclipse.help.feature`), and the p2 provisioning UI (`org.eclipse.equinox.p2.rcp.feature`).
