# TRYST Service Architecture & Management

## Overview

TRYST relies on native OSGi Service Management capabilities for application and domain services, adhering strictly to OSGi best practices:
- No static mutable service registries.
- No duplicate dependency injection frameworks (e.g. Spring/Guice).
- No global service locators or uncontrolled singletons.
- Explicit lifecycle with guaranteed disposal.

## Registered OSGi Services

The following core services are registered in the OSGi Service Registry during the `CORE_SERVICE_INITIALIZATION` lifecycle stage:

1. **`eu.kalafatic.tryst.core.config.ApplicationConfiguration`**:
   - Authoritative configuration snapshot with validated paths (`~/.tryst`, default workspace).
2. **`eu.kalafatic.tryst.domain.service.WorkspaceService`**:
   - Domain service managing workspace state, projects, tasks, and change listeners.
3. **`eu.kalafatic.tryst.core.concurrent.AsyncExecutionService`**:
   - Managed asynchronous task execution service using identifiable daemon thread workers with controlled shutdown.

## Service Discovery Pattern

- Consumers (such as UI views and controllers via `TrystUiPlugin`) discover services using OSGi `ServiceTracker` or standard OSGi `BundleContext` service lookups:
  ```java
  ServiceTracker<WorkspaceService, WorkspaceService> tracker =
      new ServiceTracker<>(context, WorkspaceService.class, null);
  tracker.open();
  WorkspaceService service = tracker.getService();
  ```
- Controllers receive service dependencies upon construction or lookup, maintaining clean testability without requiring a live OSGi runtime for unit tests.

## Threading & Execution Rules

1. **UI Execution**:
   - All SWT widget creation, modifications, and UI listeners must execute exclusively on the SWT UI thread.
   - Controllers invoke view update callbacks, and views wrap updates in `Display.asyncExec()` or `Display.syncExec()`.
2. **Background Execution**:
   - Long-running tasks, file operations, external processes, and future AI invocations run asynchronously via `AsyncExecutionService.runAsync()` or `supplyAsync()`.
   - Never invoke heavy compute or blocking I/O directly in UI event handlers.
3. **Disposal & Shutdown**:
   - Background threads run as managed daemon threads in a controlled thread pool.
   - During `SERVICE_DISPOSAL`, `AsyncExecutionService.shutdown()` terminates worker threads with a graceful await timeout, ensuring no orphaned processes or runaway threads remain.

## Error Handling Architecture

TRYST categorizes exceptions cleanly into domain, application, and infrastructure layers:

```
                  [ TrystException ] (Root Unchecked RuntimeException)
                          ^
         +----------------+----------------+
         |                                 |
 [ TrystDomainException ]      [ TrystApplicationException ]      [ TrystInfrastructureException ]
   (Domain rule violations)      (Lifecycle/workflow failures)       (I/O, process, OS failures)
```

- **Cause Preservation**: All exceptions preserve original underlying causes (`Throwable cause`).
- **User Safety**: UI views and controllers display user-meaningful diagnostic messages without dumping raw internal stack traces into the workbench UI.
