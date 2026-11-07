# TRYST Workbench Architecture & Perspective Specification

## 1. Architectural Overview & Design Philosophy

The TRYST Eclipse RCP Workbench has been refactored into a unified, professional perspective inspired by the proven layout, interaction principles, and navigation model of the EVO platform.

### Target Layout (20/80 Split)
- **Left Area (~20-22%)**: Split vertically into two independently resizable areas:
  - **Upper-Left Dock**: `TrystNavigatorView` (`eu.kalafatic.tryst.ui.navigator.view`) provides hierarchical tree navigation across Projects, Git Repositories, Branches, Tasks, Execution Workspaces, Code Reviews, Build/Test results, and Integrations (AI, MCP, TEIP).
  - **Lower-Left Dock**: `ConsoleOutputView` (`eu.kalafatic.tryst.ui.view.consoleOutput`) centralizes live streaming logs, task lifecycle transitions, Git operations, and build/test output with severity filtering and search.
- **Right Area (~78-80%)**: Dominant `TrystWorkbenchEditor` (`eu.kalafatic.tryst.ui.editor.workbenchEditor`) based on `MultiPageEditorPart`, hosting tabbed pages for all operational domains of the platform.

```
+---------------------------+-------------------------------------------------------------+
| TRYST Navigator           | MultiPage Editor (TrystWorkbenchEditor)                    |
| [Projects, Tasks,         | [Dashboard | Project Explorer | Task Mgmt | Execution | ...] |
|  Repos, Workspaces,       |                                                             |
|  Reviews, Integrations]   |                                                             |
+---------------------------+                                                             |
| TRYST Console             |                                                             |
| [Streaming Logs, Filters, |                                                             |
|  Search, Status]          |                                                             |
+---------------------------+-------------------------------------------------------------+
```

---

## 2. OSGi Package Visibility Boundaries

To enforce high modularity and prevent cyclic dependencies:
- **Exported Public API Packages (`plugins/eu.kalafatic.tryst.ui/META-INF/MANIFEST.MF`)**:
  - `eu.kalafatic.tryst.ui`: Plugin activator and OSGi service resolution facade (`TrystUiServices`).
  - `eu.kalafatic.tryst.ui.context`: Shared workbench context model (`WorkbenchContext`, `WorkbenchContextManager`, `IWorkbenchContextListener`).
  - `eu.kalafatic.tryst.ui.controller`: Controllers coordinating views with domain services (`WorkspaceController`, `ProjectExplorerController`, `ConsoleOutputController`).
  - `eu.kalafatic.tryst.ui.editor`: Editor contract, descriptor registry, and base abstractions (`ITrystEditorPage`, `TrystEditorPageDescriptor`, `TrystEditorPageRegistry`, `AbstractTrystEditorPage`, `TrystWorkbenchEditor`, `TrystWorkbenchEditorInput`, `TrystEditorManager`).
  - `eu.kalafatic.tryst.ui.navigator`: Navigator node hierarchy and view (`NavigatorNode`, `TrystNavigatorView`).
  - `eu.kalafatic.tryst.ui.perspective`: Perspective factories and lifecycle listeners (`TrystMainPerspective`, `TrystAdministrationPerspective`, `TrystPerspectiveLifecycleListener`).
  - `eu.kalafatic.tryst.ui.view`: Existing standalone views preserved for backward compatibility and secondary perspectives.
- **Intentionally Internal Packages (Unexported)**:
  - `eu.kalafatic.tryst.ui.editor.page`: Concrete page implementations (`OverviewDashboardPage`, `ProjectExplorerPage`, `TaskManagementPage`, etc.). These are instantiated via `StandardEditorPagesBootstrap` and accessed strictly through the public `ITrystEditorPage` contract.

---

## 3. Shared Workbench Context & Concurrency Model

The workbench context coordinates selection state across the Navigator, MultiPage Editor, and Console without global mutable static state or project leakage:
- **`WorkbenchContext`**: Immutable record holding typed references to selected `Project`, Git repository, branch, `AgentTask`, `Session`, and `Workspace`.
- **`WorkbenchContextManager`**: Thread-safe manager using atomic references and monotonic sequence numbers.
- **Stale Async Protection**: `updateContextIfFresh(long sequenceToken, WorkbenchContext newContext)` rejects background updates if a newer user selection occurred while the async operation was executing.

---

## 4. MultiPage Editor Architecture & Page Registry

The central editor delegates page instantiation to `TrystEditorPageRegistry`:
- **`ITrystEditorPage`**: Defines page contract (`getId()`, `getTitle()`, `createControl()`, `onActivated()`, `onDeactivated()`, `onContextChanged()`, `refresh()`, `dispose()`).
- **Standard 13 Pages**:
  1. `OverviewDashboardPage` (`eu.kalafatic.tryst.ui.editor.page.dashboard`): Platform health, readiness, and active workspace overview.
  2. `ProjectExplorerPage` (`eu.kalafatic.tryst.ui.editor.page.projectExplorer`): Project and repository workspace management.
  3. `TaskManagementPage` (`eu.kalafatic.tryst.ui.editor.page.taskManagement`): Task intake, prioritization, and session selection.
  4. `TaskExecutionPage` (`eu.kalafatic.tryst.ui.editor.page.taskExecution`): Autonomous development runner and real-time streaming diagnostics.
  5. `CodeReviewPage` (`eu.kalafatic.tryst.ui.editor.page.codeReview`): Code review center, revision history, and human-in-the-loop approval gating.
  6. `GitPage` (`eu.kalafatic.tryst.ui.editor.page.git`): Git branch inspection and VCS status.
  7. `BuildPage` (`eu.kalafatic.tryst.ui.editor.page.build`): Build technology detection (Maven, Gradle, Cargo, NPM, Python) and configuration.
  8. `TestQualityPage` (`eu.kalafatic.tryst.ui.editor.page.testQuality`): Automated test execution results and QA reports.
  9. `AiIntegrationPage` (`eu.kalafatic.tryst.ui.editor.page.aiIntegration`): AI provider connectivity (Ollama) and Darwin reasoning model status.
  10. `McpPage` (`eu.kalafatic.tryst.ui.editor.page.mcp`): Model Context Protocol scoped tools and JSON-RPC endpoint.
  11. `TeipIntegrationPage` (`eu.kalafatic.tryst.ui.editor.page.teip`): TEIP 1.0 (TRYST-EVOLUTION Integration Protocol) bridge status and delegated execution.
  12. `ServerMonitoringPage` (`eu.kalafatic.tryst.ui.editor.page.serverMonitoring`): Live host metrics and managed OS processes.
  13. `ConfigurationPage` (`eu.kalafatic.tryst.ui.editor.page.configuration`): Dynamic network port mappings and SQLite maintenance.

---

## 5. Editor Lifecycle & Perspective Activation

- **Automatic Opening**: `TrystPerspectiveLifecycleListener` detects activation of `TrystMainPerspective` and calls `TrystEditorManager.ensureEditorOpen(page)`.
- **Duplicate Prevention**: If `TrystWorkbenchEditor` with `TrystWorkbenchEditorInput` is already open, it is activated rather than re-opened.
- **Manual Closure Respect**: Manually closing the editor does not trigger a reopening loop; the editor is only reopened if the perspective is explicitly re-activated or reset.
- **Decoupled Application Advisor**: `ApplicationWorkbenchAdvisor` safely binds the lifecycle listener via reflection to avoid OSGi bundle circularity.

---

## 6. Centralized Console Integration

`ConsoleOutputView` occupies the lower-left area of the perspective:
- Live streaming from `TrystLog` via `LogListener`.
- Severity filtering (`ALL`, `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
- Structured substring search (message, correlation ID, task ID).
- Pause/resume live streaming with bounded in-memory circular buffers.
- Synchronized with the SWT UI thread using batched async execution.
