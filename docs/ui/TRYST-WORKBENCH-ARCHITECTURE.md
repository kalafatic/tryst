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

The main perspective also hosts the native Eclipse `org.eclipse.ui.console.ConsoleView`
(`org.eclipse.ui.console` bundle ships with the TRYST product) into which `TrystLog`
mirrors a "TRYST Console" `MessageConsole`.

---

## 7. Main Menu Architecture

The TRYST main menu bar is provided by `eu.kalafatic.tryst.application.ApplicationActionBarAdvisor`
(`org.eclipse.ui.application.ActionBarAdvisor`) using the native Eclipse RCP action/command
infrastructure. No menu is simulated in HTML or custom SWT controls.

| Menu | Contents | Mechanism |
|---|---|---|
| File | New Task..., Open TRYST Web Client, Refresh System Status, Exit | TRYST actions + `ActionFactory.QUIT` |
| Edit | Undo, Redo, Cut, Copy, Paste, Select All, Preferences | Standard `ActionFactory` commands with focus-based enablement |
| Navigate | Back, Forward, TRYST Navigator, TRYST Console, Open Central Workspace, Open TRYST Web Client | `ActionFactory.BACK/FORWARD` + TRYST view/editor-page actions |
| Project / Tasks / Database / TEIP / Web / Observability | Existing TRYST domain functionality | TRYST actions (unchanged) |
| Window | Open Perspective, Show View, Reset Perspective, Customize Perspective..., Preferences | TRYST actions, `ActionFactory.SHOW_VIEW_MENU`, `ActionFactory.RESET_PERSPECTIVE`, native command `org.eclipse.ui.window.customizePerspective` |
| Help | Help Contents, About TRYST, Installation Details, Check for Updates, About Installation | `eu.kalafatic.tryst.application.help.TrystHelpMenuSupport` |

Help menu details:
- **About TRYST** executes the native `org.eclipse.ui.help.aboutAction` command (Eclipse About
  dialog) using the product branding properties (`aboutImage`, `aboutText` with
  `about.mappings`, `windowImages`) from `eu.kalafatic.tryst.application`.
- **Installation Details** opens the native `org.eclipse.ui.help.installationDialog` command
  (standard Eclipse Installation Details dialog).
- **Help Contents** uses `IWorkbenchHelpSystem.displayHelp()` when TOC content is registered
  (`org.eclipse.help.toc`); otherwise it reports explicitly that TRYST documentation is not yet
  available. The help system ships via `org.eclipse.help.feature`.
- **Check for Updates** performs a real p2 `UpdateOperation` against the update repositories of
  the provisioning profile (feature `org.eclipse.equinox.p2.rcp.feature`). When no update
  repositories are configured, or the p2 UI is missing, the action reports this explicitly and
  never fakes an update check.
- **About Installation** reports the real installation location, configuration/instance areas,
  Java runtime, Eclipse platform version and installed bundle count.

Menu actions that would be dead in the TRYST context (document save/import/export, IDE resource
navigation) are intentionally not presented; unsupported platform actions such as Back/Forward
are contributed through standard `ActionFactory` instances so their enabled state reflects the
real workbench state.

