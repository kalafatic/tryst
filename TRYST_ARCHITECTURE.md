# TRYST Architecture

## Overview
TRYST is an AI-powered autonomous software development environment desktop application inspired by Google Jules, built as a standalone Eclipse Rich Client Platform (RCP) application. It is developed entirely in Java 21 using standard Eclipse OSGi, Workbench, SWT, and JFace frameworks, built with Maven and Tycho.

TRYST is completely independent of EVO.

## OSGi Plugin Architecture

TRYST is composed of focused, decoupled OSGi bundles and features under the root namespace `eu.kalafatic.tryst`:

```
               [ eu.kalafatic.tryst.core ]
                           ^
                           |
               [ eu.kalafatic.tryst.domain ]
                      ^          ^
                     /            \
                    /              \
  [ eu.kalafatic.tryst.ui ]       [ eu.kalafatic.tryst.tests ]
             ^                             ^
             |                             |
  [ eu.kalafatic.tryst.application ]-------+
```

### Bundle Responsibilities

1. **`eu.kalafatic.tryst.core`**:
   - Fundamental application abstractions, contracts, and core exceptions (`TrystException`).
   - Immutable application event model (`ApplicationEvent`) and listeners (`ApplicationEventListener`).
   - Zero UI or Workbench dependencies.

2. **`eu.kalafatic.tryst.domain`**:
   - Core domain models: `Workspace`, `Project`, `AgentTask`, `ConsoleMessage`, `ExecutionStatus`.
   - Domain lifecycle state transitions and rules (task execution validation, project activation).
   - Domain services: `WorkspaceService` managing active workspace state and event dispatching (`WorkspaceChangeListener`).
   - Zero UI, SWT, or Workbench dependencies.

3. **`eu.kalafatic.tryst.application`**:
   - Eclipse application entry point (`TrystApplication` implementing `IApplication`).
   - Workbench lifecycle advisors (`ApplicationWorkbenchAdvisor`, `ApplicationWorkbenchWindowAdvisor`, `ApplicationActionBarAdvisor`).
   - Equinox application and product extensions (`eu.kalafatic.tryst.application.application`, `eu.kalafatic.tryst.application.product`).
   - Application window title: "TRYST – AI Development Environment".

4. **`eu.kalafatic.tryst.ui`**:
   - User interface components and workbench contributions.
   - Perspective definition (`TrystMainPerspective`).
   - Views (`ProjectExplorerView`, `MainWorkspaceView`, `ConsoleOutputView`).
   - Focused controllers (`ProjectExplorerController`, `WorkspaceController`, `ConsoleOutputController`).
   - UI plugin activator (`TrystUiPlugin`).

5. **`eu.kalafatic.tryst.feature`**:
   - Feature definition grouping TRYST plugins with required RCP runtime dependencies.

6. **`eu.kalafatic.tryst.product`**:
   - Materialization of the standalone desktop product (`tryst.product`) via Tycho `tycho-p2-director-plugin`.

7. **`eu.kalafatic.tryst.tests`**:
   - Unit and integration tests using JUnit 5 verifying domain models, services, and MVC decoupling.

## Application Startup Lifecycle

1. OSGi framework boots Equinox runtime.
2. `eu.kalafatic.tryst.application` is launched via `org.eclipse.equinox.applications` extension point.
3. `TrystApplication.start(IApplicationContext)` initializes SWT Display and creates Workbench using `ApplicationWorkbenchAdvisor`.
4. `ApplicationWorkbenchAdvisor` configures window advisors and initial perspective `eu.kalafatic.tryst.ui.perspective.main`.
5. `ApplicationWorkbenchWindowAdvisor.preWindowOpen` sets window dimensions (1280x800), title, and status line.
6. `ApplicationActionBarAdvisor` configures top-level menus: File, Project, Window, Help.
7. `TrystMainPerspective` arranges layout:
   - Left: Project Explorer view
   - Center: Main Workspace view
   - Bottom: Console / Output / Status view
8. On workbench shutdown, `TrystApplication.stop()` safely closes workbench and disposes display.
