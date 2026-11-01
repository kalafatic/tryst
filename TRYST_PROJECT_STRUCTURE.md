# TRYST Project Structure

## Root Project Layout

```
.
├── pom.xml                                   # Root Tycho Parent POM (Java 21, Tycho 4.0.8, Eclipse 2024-03)
├── README.md                                 # High-level overview and quick start guide
├── TRYST_ARCHITECTURE.md                     # Architectural design and bundle responsibilities
├── TRYST_APPLICATION_LIFECYCLE.md            # Detailed application lifecycle and transitions
├── TRYST_SERVICE_ARCHITECTURE.md             # OSGi service registration, discovery, concurrency & errors
├── TRYST_MVC_ARCHITECTURE.md                 # Strict Model-View-Controller pattern documentation
├── TRYST_BUILD.md                            # Build and execution guide
├── features/
│   └── eu.kalafatic.tryst.feature/           # Main feature grouping TRYST bundles
├── plugins/
│   ├── eu.kalafatic.tryst.core/              # Core abstractions, lifecycle, config, concurrency & errors
│   ├── eu.kalafatic.tryst.domain/            # Domain models and domain services
│   ├── eu.kalafatic.tryst.application/       # Eclipse RCP application entry, lifecycle coordinator, advisors
│   └── eu.kalafatic.tryst.ui/                # Workbench perspectives, views, and controllers
├── product/
│   └── eu.kalafatic.tryst.product/           # Standalone RCP product definition & materialization
└── tests/
    └── eu.kalafatic.tryst.tests/             # Automated JUnit 5 integration and unit test suite
```

## Plugin Overview

### `eu.kalafatic.tryst.core`
- **`eu.kalafatic.tryst.core`**: Generic application event model (`ApplicationEvent`, `ApplicationEventListener`), base exception (`TrystException`).
- **`eu.kalafatic.tryst.core.error`**: Layered exception hierarchy (`TrystDomainException`, `TrystApplicationException`, `TrystInfrastructureException`).
- **`eu.kalafatic.tryst.core.lifecycle`**: Lifecycle definitions (`ApplicationLifecycleStage`, `ApplicationLifecycleEvent`, `ApplicationLifecycleListener`).
- **`eu.kalafatic.tryst.core.config`**: Authoritative configuration record (`ApplicationConfiguration`).
- **`eu.kalafatic.tryst.core.concurrent`**: Asynchronous task executor (`AsyncExecutionService`, `ManagedAsyncExecutionService`).
- **`eu.kalafatic.tryst.core.log`**: Standard platform logging helper (`TrystLog`).

### `eu.kalafatic.tryst.domain`
- **`eu.kalafatic.tryst.domain.model`**: Domain entities: `Workspace`, `Project`, `AgentTask`, `ConsoleMessage`, `ExecutionStatus`.
- **`eu.kalafatic.tryst.domain.service`**: Domain service: `WorkspaceService`, `WorkspaceChangeListener`.

### `eu.kalafatic.tryst.application`
- **`eu.kalafatic.tryst.application`**:
  - `TrystApplication`: Application entry point implementing Eclipse `IApplication`.
  - `ApplicationWorkbenchAdvisor`: Orchestrates Workbench startup, shutdown, and perspective.
  - `ApplicationWorkbenchWindowAdvisor`: Window geometry, title, status line.
  - `ApplicationActionBarAdvisor`: Desktop menus (File, Project, Window, Help).
- **`eu.kalafatic.tryst.application.lifecycle`**:
  - `ApplicationLifecycleCoordinator`: Manages deterministic 9-stage lifecycle transitions, registers and unregisters core OSGi services.

### `eu.kalafatic.tryst.ui`
- **`eu.kalafatic.tryst.ui`**: `TrystUiPlugin` managing UI activator and OSGi `ServiceTracker` instances.
- **`eu.kalafatic.tryst.ui.perspective`**: `TrystMainPerspective` configuring 3-pane workbench layout.
- **`eu.kalafatic.tryst.ui.controller`**: `ProjectExplorerController`, `WorkspaceController`, `ConsoleOutputController`.
- **`eu.kalafatic.tryst.ui.view`**: `ProjectExplorerView`, `MainWorkspaceView`, `ConsoleOutputView`.

### `eu.kalafatic.tryst.tests`
- **`eu.kalafatic.tryst.tests`**: JUnit 5 test classes verifying:
  - `ApplicationLifecycleTest`: Sequential transitions, invalid transition rejections, resource disposal.
  - `OsgiServiceRegistrationTest`: Service registration and unregistration in the OSGi runtime.
  - `AsyncExecutionServiceTest`: Off-UI execution, error handling, worker shutdown.
  - `ConfigurationTest`: Safe defaults and validation.
  - `ErrorHandlingTest`: Exception preservation and diagnostic messages.
  - `DomainModelTest`: State machine and entity invariants.
  - `WorkspaceServiceTest`: Domain service event notifications.
  - `MvcSeparationTest`: Controller and View loose coupling.
