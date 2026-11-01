# TRYST Application Lifecycle

## Overview

TRYST establishes an explicit, predictable, deterministic lifecycle sequence for the standalone Eclipse RCP application. The lifecycle is coordinated by `ApplicationLifecycleCoordinator` (in `eu.kalafatic.tryst.application.lifecycle`) and hooked into Eclipse's `IApplication` and `WorkbenchAdvisor` lifecycle callbacks.

## Lifecycle Transition Sequence

```
       [ APPLICATION_START ]
                 |
                 v
    [ PLATFORM_INITIALIZATION ]
                 |
                 v
  [ CONFIGURATION_INITIALIZATION ]
                 |
                 v
   [ CORE_SERVICE_INITIALIZATION ]
                 |
                 v
       [ APPLICATION_READY ]
                 |
                 v
       [ WORKBENCH_RUNNING ]
                 |
                 v
      [ APPLICATION_SHUTDOWN ]
                 |
                 v
       [ SERVICE_DISPOSAL ]
                 |
                 v
     [ APPLICATION_TERMINATED ]
```

## Lifecycle Stages and Actions

1. **`APPLICATION_START`**: Initial state upon OSGi bundle activation and application bootstrap.
2. **`PLATFORM_INITIALIZATION`**: Triggered in `TrystApplication.start(IApplicationContext)`. Validates platform environment and display prerequisites.
3. **`CONFIGURATION_INITIALIZATION`**: Authoritative configuration baseline (`ApplicationConfiguration`) initialized with safe defaults and paths.
4. **`CORE_SERVICE_INITIALIZATION`**: Triggered during `ApplicationWorkbenchAdvisor.initialize(IWorkbenchConfigurer)`.
   - Instantiates application and domain services (`WorkspaceService`, `ManagedAsyncExecutionService`).
   - Registers services in the OSGi Service Registry via `BundleContext.registerService()`.
5. **`APPLICATION_READY`**: Core services and workbench window configurations are prepared.
6. **`WORKBENCH_RUNNING`**: Triggered in `ApplicationWorkbenchAdvisor.postStartup()`. Workbench event loop is running and user interactions are serviced.
7. **`APPLICATION_SHUTDOWN`**: Triggered in `ApplicationWorkbenchAdvisor.preShutdown()`. Workbench shutdown begins; long-running operations are requested to stop.
8. **`SERVICE_DISPOSAL`**: Triggered in `ApplicationWorkbenchAdvisor.postShutdown()`.
   - Core OSGi service registrations are unregistered in reverse order.
   - Background worker pools and threads (`AsyncExecutionService`) are gracefully shut down.
   - Domain references are released.
9. **`APPLICATION_TERMINATED`**: Workbench window and display resources are completely disposed and process exits cleanly with `IApplication.EXIT_OK`.

## Failure and Diagnostic Handling

- Lifecycle transitions are strictly monotonic and sequential. Illegal backward or skipped transitions throw `TrystApplicationException`.
- Every transition is logged with severity, timestamp, and calling component using `TrystLog` (Eclipse Platform `ILog`).
- Critical startup errors abort initialization cleanly and log full diagnostics without swallowing original causes.
