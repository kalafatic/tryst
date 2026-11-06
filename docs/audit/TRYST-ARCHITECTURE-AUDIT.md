# TRYST Architecture Audit & Subsystem Responsibilities

## 1. Executive Summary & Architecture Scope

TRYST is an asynchronous, Git-based autonomous AI programming platform built on pure Java 21, Eclipse RCP (Equinox / OSGi), Eclipse Tycho, SQLite persistence, and an integrated HTTP server with a vanilla HTML5/JS web interface.

This document presents a comprehensive, method- and class-level architectural audit of the entire repository as of the baseline stabilization phase.

---

## 2. Component Responsibility Mapping

| Domain / Responsibility | Primary Component(s) | OSGi Plugin | Exact Methods / Roles |
|---|---|---|---|
| **1. Task Lifecycle Ownership** | `TaskLifecycleManager`<br>`TaskStateTransitionValidator` | `eu.kalafatic.tryst.domain` | `transition(task, targetStatus, message, details)` enforces valid transitions, emits `TaskEvent`s, and persists changes atomically. |
| **2. Session Lifecycle Ownership** | `AuthenticationService`<br>`UserSessionRepository` | `eu.kalafatic.tryst.domain`<br>`eu.kalafatic.tryst.infrastructure` | Manages login, session expiration, sliding timeouts, token generation (`UUID`), and persistence in `user_sessions`. |
| **3. Background Work Execution** | `TaskOrchestrator`<br>`TaskQueue`<br>`TaskWorker` | `eu.kalafatic.tryst.domain` | `dispatchLoop()` claims queued tasks atomically via SQLite; `TaskWorker.call()` executes the 10-step autonomous workflow. |
| **4. Git Operations Control** | `GitService`<br>`GitTools`<br>`GitHubService` | `eu.kalafatic.tryst.infrastructure`<br>`eu.kalafatic.tryst.domain` | `cloneOrFetch()`, `getDiff()`, `getStatus()`, `commitAuthorizedReview()`, `pushAuthorized()` with strict user gating. |
| **5. Workspace & Sandbox Management** | `WorkspaceService`<br>`WorkspaceRecoveryService`<br>`PermissionManager` | `eu.kalafatic.tryst.domain` | `allocateTaskWorkspace()`, `initializeWorkspace()`, `cleanupWorkspace()`. Prevents path traversal and symlink escapes. |
| **6. AI Communication & Orchestration** | `AIProvider`<br>`OllamaProvider`<br>`CodeAgent`<br>`ModelSelectionService` | `eu.kalafatic.tryst.domain`<br>`eu.kalafatic.tryst.infrastructure` | Streams prompt responses via Java 21 `HttpClient`, handles timeouts, retries, and token limits. |
| **7. MCP Tool Registration & Execution** | `ScopedMcpTools`<br>`McpServer`<br>`ToolRegistry` | `eu.kalafatic.tryst.evo.integration`<br>`eu.kalafatic.tryst.domain` | JSON-RPC 2.0 endpoint at `/api/mcp` exposing scoped workspace tools (`read_file`, `write_file`, `propose_patch`, etc.). |
| **8. Authorization & RBAC** | `PermissionManager`<br>`AuthenticationService` | `eu.kalafatic.tryst.domain` | Evaluates `PermissionDecision` (`DENY` strictly takes precedence), enforces project and workspace boundaries. |
| **9. Persistence & Migrations** | `SqliteDatabaseManager`<br>`SchemaMigrationService`<br>Repository classes | `eu.kalafatic.tryst.infrastructure` | Executes numbered schema migrations (V1 through V13) with PRAGMA integrity check and WAL checkpoints. |
| **10. UI & User Notifications** | `NotificationService`<br>`TrystHttpServer` (SSE)<br>`ConsoleOutputController` | `eu.kalafatic.tryst.domain`<br>`eu.kalafatic.tryst.infrastructure`<br>`eu.kalafatic.tryst.ui` | Emits 12 task lifecycle notification events via `/api/notifications/stream` and SSE push. |
| **11. Interrupted Work Recovery** | `TaskRecoveryService`<br>`TaskRecoveryCheckpointRepository` | `eu.kalafatic.tryst.domain` | Assesses crashed tasks, determines recovery strategy (`SAFE_TO_RESUME`, `RECONCILE_AND_RETRY`, `REQUIRES_USER_INTERVENTION`). |
| **12. Review & Approval Ownership** | `CodeReviewService`<br>`CodeReview`<br>`TrystHttpServer$ReviewsHandler` | `eu.kalafatic.tryst.domain`<br>`eu.kalafatic.tryst.infrastructure` | Human-in-the-loop review gating; enforces exact revision approval and SHA-256 fingerprint matching prior to commit. |

---

## 3. Discovered Defects and Architectural Improvements

1. **Schema Migration Version Assertion Drift**:
   - `SchemaMigrationService` defines 13 sequential migrations ending with V13 (TEIP 1.0 EVO integration: `evo_configurations`, `evo_executions`, `evo_execution_events`).
   - `DatabaseInitializationTest.java` and `ServerBootstrapAndNetworkingTest.java` still asserted version 12 from pre-EVO development. Updated assertions to version 13.

2. **Task Workspace Allocation Scoping**:
   - In `TaskOrchestrator.executeTaskAsync()`, if `task.getProjectId()` was provided, it resolved `workspaceService.getWorkspaceForProject(projectId)`.
   - When multiple tasks ran against the same project concurrently, this shared a single root workspace rather than allocating task-isolated sandboxes (`allocateTaskWorkspace`).
   - Fixing this ensures complete task sandbox isolation so parallel tasks don't overwrite each other's working trees.

3. **Untracked File Git Diff Extraction**:
   - In `TaskWorker.extractWorkspaceDiff()` and `GitTools.GitDiffTool`, only `git diff` was run. Files created newly by the AI agent were untracked and therefore not included in the review diff unless added to index or tracked.
   - Ensuring `git add -N .` (intent-to-add) or staging before generating diffs makes new files cleanly visible in side-by-side diffs and reviews.

4. **Build and Quality Assurance Subsystem Integration**:
   - `BuildAndQualityService` (`BuildAndTestService`) is capable of multi-technology detection (Maven, Gradle wrapper, npm, Cargo, Python) and subprocess management.
   - Verified that `TaskOrchestrator` wires `BuildAndQualityService` into `ResultValidator` and `TaskWorker` so that actual build and test execution results are captured accurately without false success reports.
