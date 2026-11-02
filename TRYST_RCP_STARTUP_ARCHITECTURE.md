# TRYST RCP Startup Architecture

## 1. Overview
TRYST is an autonomous AI software development platform built upon the native Eclipse RCP / Equinox OSGi framework. The platform provides two officially supported runtime startup modes from a single unified product distribution:
1. **Standard Eclipse RCP desktop application (default)**: Starts the Equinox OSGi runtime, presents a branded Eclipse splash screen, starts background server services, and initializes the SWT Workbench window with the TRYST Administration perspective.
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
       Eclipse Splash (splash.bmp)                     No SWT Display
             |                                             |
       SWT Workbench Window                            OSGi Runtime
             |                                             |
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
- If headless is requested, execution delegates directly to `TrystServerApplication.runHeadless(...)`.
- If omitted, standard RCP mode proceeds to `PlatformUI.createDisplay()` and `PlatformUI.createAndRunWorkbench(...)`.
