# TRYST Architecture

## Overview
TRYST is an AI-powered autonomous software development environment desktop application inspired by Google Jules, built as a standalone Eclipse Rich Client Platform (RCP) application. It is developed entirely in Java 21 using standard Eclipse OSGi, Workbench, SWT, and JFace frameworks, built with Maven and Tycho.

TRYST is completely independent of EVO.

## Logical Architectural Layers

```
TRYST APPLICATION
│
├── DOMAIN (eu.kalafatic.tryst.domain)
│   ├── Model (Project, Workspace, AgentTask, ConsoleMessage, ExecutionStatus)
│   └── Domain Services (WorkspaceService, WorkspaceChangeListener)
│
├── APPLICATION (eu.kalafatic.tryst.application)
│   ├── Application Services (ApplicationLifecycleCoordinator)
│   ├── Configuration & Concurrency (eu.kalafatic.tryst.core)
│   └── Workbench Advisors (WorkbenchAdvisor, WindowAdvisor, ActionBarAdvisor)
│
├── INFRASTRUCTURE (Future - prompt 03+)
│   ├── Persistence
│   ├── AI Runtime (Ollama)
│   └── External Integrations
│
└── PRESENTATION (eu.kalafatic.tryst.ui)
    ├── Views (ProjectExplorerView, MainWorkspaceView, ConsoleOutputView)
    └── Controllers (ProjectExplorerController, WorkspaceController, ConsoleOutputController)
```

## OSGi Plugin Architecture & Dependency Graph

```
               [ eu.kalafatic.tryst.core ]
                       ^       ^
                      /         \
                     /           \
  [ eu.kalafatic.tryst.domain ]   \
             ^                     \
             |                      \
  [ eu.kalafatic.tryst.application ] \
             ^                        \
             |                         \
    [ eu.kalafatic.tryst.ui ] <---------+
```

Note: Notice that `eu.kalafatic.tryst.application` does not depend on `eu.kalafatic.tryst.ui`. The application bundle hosts the entry point and lifecycle coordination, registering domain and application services into the OSGi Service Registry. `eu.kalafatic.tryst.ui` depends on `domain` and `core`, discovering services cleanly via OSGi `ServiceTracker`.

### Bundle Responsibilities

1. **`eu.kalafatic.tryst.core`**:
   - Fundamental application abstractions, contracts, and error hierarchy (`TrystException`, `TrystDomainException`, `TrystApplicationException`, `TrystInfrastructureException`).
   - Immutable lifecycle model (`ApplicationLifecycleEvent`, `ApplicationLifecycleStage`).
   - Managed background concurrency (`AsyncExecutionService`, `ManagedAsyncExecutionService`).
   - Authoritative configuration baseline (`ApplicationConfiguration`).
   - Platform unified diagnostic logging helper (`TrystLog`).
   - Zero UI or Workbench dependencies.

2. **`eu.kalafatic.tryst.domain`**:
   - Core domain models: `Workspace`, `Project`, `AgentTask`, `ConsoleMessage`, `ExecutionStatus`.
   - Domain lifecycle state transitions and rules (task execution validation, project activation).
   - Domain services: `WorkspaceService` managing active workspace state and event dispatching (`WorkspaceChangeListener`).
   - Zero UI, SWT, or Workbench dependencies.

3. **`eu.kalafatic.tryst.application`**:
   - Eclipse application entry point (`TrystApplication` implementing `IApplication`).
   - Lifecycle coordination: `ApplicationLifecycleCoordinator` driving deterministic startup, service registration, and disposal.
   - Workbench lifecycle advisors (`ApplicationWorkbenchAdvisor`, `ApplicationWorkbenchWindowAdvisor`, `ApplicationActionBarAdvisor`).
   - Equinox application and product extensions (`eu.kalafatic.tryst.application.application`, `eu.kalafatic.tryst.application.product`).
   - Application window title: "TRYST – AI Development Environment".

4. **`eu.kalafatic.tryst.ui`**:
   - User interface components and workbench contributions.
   - Perspective definition (`TrystMainPerspective`).
   - Views (`ProjectExplorerView`, `MainWorkspaceView`, `ConsoleOutputView`).
   - Focused controllers (`ProjectExplorerController`, `WorkspaceController`, `ConsoleOutputController`).
   - UI plugin activator (`TrystUiPlugin`) consuming registered OSGi services.

5. **`eu.kalafatic.tryst.feature`**:
   - Feature definition grouping TRYST plugins with required RCP runtime dependencies.

6. **`eu.kalafatic.tryst.product`**:
   - Materialization of the standalone desktop product (`tryst.product`) via Tycho `tycho-p2-director-plugin`.

7. **`eu.kalafatic.tryst.tests`**:
   - Unit and integration tests using JUnit 5 verifying domain models, lifecycle coordination, OSGi service registration, concurrency, error handling, and MVC decoupling.
