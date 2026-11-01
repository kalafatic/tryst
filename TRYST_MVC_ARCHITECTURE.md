# TRYST MVC Architecture

## Principles

TRYST enforces a strict Model-View-Controller (MVC) architectural separation:
1. **Model** contains entities, domain logic, and state. Models never depend on SWT, JFace, or Controllers.
2. **View** contains SWT/JFace presentation logic and viewers. Views never execute business logic directly or invoke domain mutation rules.
3. **Controller** coordinates user actions from views, invokes domain/application services, and dispatches updates to views. Controllers do not directly create or couple to SWT widgets.

## Communication Pattern

```
  USER
    |
    v
  VIEW (SWT / JFace)
    |   [Invokes controller action handler]
    v
  CONTROLLER
    |   [Coordinates business action]
    v
  APPLICATION / DOMAIN SERVICE (WorkspaceService / AsyncExecutionService)
    |   [Mutates entity state / runs async task]
    v
  MODEL (Workspace, Project, AgentTask)
    |
    v   [State change notification]
  LISTENER / CALLBACK (WorkspaceChangeListener / ViewCallback)
    |
    v   [Async UI thread dispatch Display.asyncExec]
  VIEW UPDATE
```

## OSGi Decoupling

In PROMPT 02/20, `WorkspaceService` ownership was moved out of `TrystUiPlugin` into the application layer's `ApplicationLifecycleCoordinator`.
- `WorkspaceService` is registered in the OSGi Service Registry during the `CORE_SERVICE_INITIALIZATION` lifecycle stage.
- Views retrieve `WorkspaceService` via `TrystUiPlugin`'s dynamic `ServiceTracker<WorkspaceService, WorkspaceService>`, preserving loose coupling and clean OSGi lifecycle.
- Long-running asynchronous operations are executed off the UI thread via `AsyncExecutionService`, updating UI widgets strictly on the SWT thread using `Display.asyncExec()`.

## Implemented MVC Components

### Models (`eu.kalafatic.tryst.domain.model`)
- `Project`: Project metadata, path location, and active status.
- `Workspace`: Active workspace directory, collection of projects, and task collection.
- `AgentTask`: Task entity with status machine (`IDLE` -> `RUNNING` -> `COMPLETED`/`FAILED`/`CANCELLED`).
- `ConsoleMessage`: Immutable record for system, info, warning, and error messages.
- `ExecutionStatus`: Lifecycle enumeration.

### Services (`eu.kalafatic.tryst.domain.service` & `eu.kalafatic.tryst.core.concurrent`)
- `WorkspaceService`: Manages workspace state, project registrations, task creations, and broadcast events.
- `WorkspaceChangeListener`: Observer interface for domain model state notifications.
- `AsyncExecutionService`: Managed daemon worker execution off the SWT thread.

### Controllers (`eu.kalafatic.tryst.ui.controller`)
- `ProjectExplorerController`:
  - Retrieves active project list.
  - Handles project selection actions.
  - Coordinates adding new projects via domain service.
- `WorkspaceController`:
  - Retrieves active project and development tasks.
  - Coordinates creating tasks via domain service.
- `ConsoleOutputController`:
  - Coordinates console message logging and dispatches updates to the view.

### Views (`eu.kalafatic.tryst.ui.view`)
- `ProjectExplorerView`:
  - Displays project hierarchy via JFace `TableViewer`.
  - Contributes toolbar and menu actions for adding projects.
  - Forwards user selections and inputs to `ProjectExplorerController`.
- `MainWorkspaceView`:
  - Displays active project details and tasks via JFace `TableViewer`.
  - Contributes actions for task creation.
  - Forwards user actions to `WorkspaceController`.
- `ConsoleOutputView`:
  - Displays real-time console messages and log entries in read-only `StyledText`.
  - Contributes clear console action.
  - Obtains updates via `ConsoleOutputController`.
