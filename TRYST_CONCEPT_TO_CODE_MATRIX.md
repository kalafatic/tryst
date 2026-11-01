# TRYST – Concept-to-Code Traceability Matrix

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Baseline Git Commit:** `539768259664f6695bf44f7efce06615bca6fbeb`

---

## 1. Overview

This matrix maps each core product concept of the intended **TRYST** platform (inspired by Google Jules: a self-hosted, asynchronous autonomous software development server with a dedicated web interface) to its actual implementation in the codebase.

It identifies what is implemented, what works in execution, what is incomplete or missing, and the exact gaps requiring remediation across the upcoming implementation phases.

---

## 2. Concept-to-Code Mapping Matrix

| # | Product Concept / Capability | Intended Behavior | Existing Implementation | Relevant Classes & Modules | Integration Status | Runtime Verification | Identified Gaps | Target Phase |
|---|---|---|---|---|---|---|---|---|
| **1** | **Headless RCP / OSGi Server Runtime** | Server boots as a headless daemon under Equinox OSGi, loading config, database, and background services without requiring X11, Display, or Workbench. | Boots desktop Workbench window via `PlatformUI.createAndRunWorkbench(...)`. | `TrystApplication.java`, `ApplicationWorkbenchAdvisor.java`, `ApplicationLifecycleCoordinator.java` | ⚠️ Partially Integrated | ⚠️ Fails without `$DISPLAY` / `xvfb-run` | Requires headless `IApplication` entry point that runs coordinator and waits without SWT Display. | Phase 1 (PROMPT 02/04) |
| **2** | **Embedded HTTP & Web Server** | Long-running HTTP server delivering HTML/CSS/JS frontend and REST APIs on configurable port. | None. No HTTP server is started. | None (JDK `com.sun.net.httpserver.HttpServer` available in JRE). | ❌ Not Implemented | ❌ No HTTP listener | HTTP server bundle, request routing, static file handler, and port configuration needed. | Phase 1 (PROMPT 02/04) |
| **3** | **Web Application Delivery** | Single-page web application providing Workbench, Execution Center, Review Center, and Administration. | Desktop SWT/JFace views (`MainWorkspaceView`, `ProjectExplorerView`, `ConsoleOutputView`). | `eu.kalafatic.tryst.ui` bundle | ⚠️ Desktop Only | 🟢 Desktop views work via Xvfb | Web application frontend assets (HTML/CSS/JS) and API integration needed. | Phase 1 (Core Web) & Phase 2/3 |
| **4** | **Multi-User Authentication & Security** | User login, PBKDF2/SHA-256 password hashing, bearer auth tokens, session revocation, and RBAC (Admin, Developer, Viewer). | Single-user global state. No user tables, credentials, or login logic. | `PermissionManager.java` (path containment only). | ❌ Not Implemented | ❌ None | Add `User`, `AuthToken` models, password hashing, `users`/`auth_sessions` tables, and auth filter. | Phase 1 (PROMPT 02/04) |
| **5** | **Multi-Tenant Workspace Isolation** | Workspaces and project directories isolated per user/tenant with strict path traversal boundaries. | Single `Workspace` root check in memory. Path traversal check is functional for that single workspace. | `WorkspaceService.java`, `PermissionManager.java` | ⚠️ Single Workspace | 🟢 Path traversal tests pass | Bind workspaces to users/projects; isolate directories; persist audit logs. | Phase 2 (PROMPT 03/04) |
| **6** | **Durable Asynchronous Task Queue** | User submits task, task is persisted to database, queued, and processed independently of client connection. | Task record saved to SQLite, but execution is an ephemeral `CompletableFuture.supplyAsync`. | `SessionTaskOrchestrator.java`, `SqliteTaskRepository.java` | ⚠️ Partially Integrated | 🟢 Local execution works | Add durable queue state machine, heartbeat, and crash recovery reconciler. | Phase 1 (PROMPT 02/04) |
| **7** | **Autonomous Agent Development Loop** | Agent iteratively reads context, queries Ollama, executes tools (files, git), verifies with tests, and produces output. | Iterative loop calling `OllamaClient`, executing `read_file`, `write_file`, `git_diff`. | `SessionTaskOrchestrator.java`, `AutonomousDevelopmentService.java`, `OllamaClient.java` | 🟢 Functional | 🟢 Verified in `EndToEndAutonomousWorkflowIntegrationTest` | Fragile string parsing for tools; needs JSON-based tool calls and streaming token persistence. | Phase 2 (PROMPT 03/04) |
| **8** | **Editable Web Git Review Center** | User inspects generated diff in browser, edits file modifications inline, re-validates, and approves or rejects before commit. | Static rule check (`CodeReviewService`) scanning diffs for secrets/errors. CLI git operations in `GitService`. | `CodeReviewService.java`, `GitService.java`, `GitTools.java` | ⚠️ Backend Rules Only | 🟢 Rule checks and Git operations pass tests | Review snapshot model, web diff editor, per-file/hunk editing, and approval gating before commit. | Phase 2 (PROMPT 03/04) |
| **9** | **Human-in-the-Loop Explicit Commit** | Git commits are never executed automatically without explicit human sign-off and valid review snapshot. | `GitService.commit()` exists; `AutonomousDevelopmentService` does not enforce user approval checkpoint. | `AutonomousDevelopmentService.java`, `GitService.java` | ⚠️ Missing Gate | 🟢 Raw commit method works | Enforce `TaskStatus.PENDING_REVIEW` -> user approval -> commit workflow. | Phase 2 (PROMPT 03/04) |
| **10** | **Build & Test Engine** | Asynchronous compilation and test execution capturing logs, exit codes, and durations. | Subprocess execution of `mvn clean compile` / `mvn test` via `ProcessBuilder`. | `BuildAndTestService.java` | 🟢 Functional | 🟢 Pass/fail parsing verified | Add `./mvnw` wrapper detection and real-time streaming output capture. | Phase 2 (PROMPT 03/04) |
| **11** | **Server Administration & Observability** | Web dashboard monitoring active sessions, tasks, background workers, JVM metrics, Ollama model latency, and audit logs. | None. In-memory `auditLog` in `PermissionManager`. | None. | ❌ Not Implemented | ❌ None | Administration service, system/inference metric collectors, persistent audit table, and admin UI. | Phase 3 (PROMPT 04/04) |
| **12** | **Persistence & Schema Migrations** | SQLite with WAL mode, foreign keys, and versioned migration tracking surviving crashes and restarts. | Versioned migration engine with `schema_version` table and V1 migration. `SqliteDatabaseManager` with transactions. | `SchemaMigrationService.java`, `SqliteDatabaseManager.java`, `Sqlite*Repository.java` | 🟢 Functional | 🟢 Verified in 9 persistence/migration tests | Add V2 migration for users, auth, reviews, and audit logs. | Phase 1 (V2 Schema) & Phase 2 (V3) |
| **13** | **Event-Driven Communication & Streaming** | Decoupled event publication and real-time streaming updates to web clients (SSE / WebSocket). | In-memory `EventBus` with immutable typed events. | `EventBus.java`, `TrystTypedEvent.java`, `ApplicationEvents.java` | 🟡 Internal Only | 🟢 Unit tests pass | Expose event stream over HTTP Server-Sent Events (SSE) `/api/events`. | Phase 1 (PROMPT 02/04) |
| **14** | **Configuration Management** | Strongly-validated configuration loading from disk/env, with defaults and dynamic updates. | Strongly-validated Java record with file persistence in `~/.tryst/tryst.properties`. | `ApplicationConfiguration.java`, `ConfigurationService.java` | 🟢 Functional | 🟢 Verified in `ConfigurationTest` | Add server port, host, and security settings to configuration schema. | Phase 1 (PROMPT 02/04) |

---

## 3. Detailed Gap Analysis by Architecture Layer

### 3.1 Server Layer
- **Current:** Only desktop lifecycle exists (`ApplicationWorkbenchAdvisor`, `ApplicationActionBarAdvisor`, `TrystMainPerspective`).
- **Required:** Introduce `eu.kalafatic.tryst.server` bundle or package containing `TrystServerApplication` implementing `IApplication` in headless mode, managing the HTTP server and dispatch lifecycle.

### 3.2 Web & API Layer
- **Current:** 100% missing. Zero HTTP servlets or handlers.
- **Required:** Pure Java 21 `HttpServer` routing:
  - Static handler: `/` (serves index.html, JS, CSS).
  - Auth handler: `/api/auth/login`, `/api/auth/logout`, `/api/auth/me`.
  - Workspace/Project handler: `/api/projects`, `/api/workspaces`.
  - Task & Orchestration handler: `/api/tasks`, `/api/tasks/{id}`, `/api/tasks/{id}/execute`, `/api/tasks/{id}/cancel`.
  - Review handler: `/api/reviews/{id}`, `/api/reviews/{id}/diff`, `/api/reviews/{id}/edit`, `/api/reviews/{id}/approve`, `/api/reviews/{id}/reject`.
  - Events SSE handler: `/api/events` (subscribes to `EventBus` and streams JSON events to browser).
  - Admin handler: `/api/admin/metrics`, `/api/admin/users`, `/api/admin/sessions`, `/api/admin/audit`.

### 3.3 Domain Layer
- **Current:** Clean models for projects, workspaces, sessions, tasks, and results.
- **Required:**
  - Add `User`, `Role`, `AuthenticationSession`.
  - Add `CodeReviewSnapshot`, `ReviewFileEdit`, `ReviewApproval`.
  - Enhance `AgentTask` with statuses `QUEUED`, `PENDING_REVIEW`, `RECONCILING`.

### 3.4 Persistence Layer
- **Current:** Schema V1 covering 7 core domain tables.
- **Required:** Schema V2 and V3:
  - V2: `users`, `auth_sessions`, `audit_logs`.
  - V3: `code_reviews`, `review_file_edits`, `server_metrics`.

---

## 4. Verification Baseline

All 55 existing tests in `tests/eu.kalafatic.tryst.tests` pass cleanly:
- `ApplicationLifecycleTest` (4 tests)
- `DomainModelTest` (8 tests)
- `SqliteRepositoryTest` (4 tests)
- `DatabaseInitializationTest` (2 tests)
- `SchemaMigrationTest` (1 test)
- `ConcurrencyTest` (1 test)
- `TransactionRollbackTest` (2 tests)
- `DatabaseBackupTest` (1 test)
- `ApplicationRestartRecoveryTest` (1 test)
- `EndToEndAutonomousWorkflowIntegrationTest` (1 test)
- `OllamaClientTest` (3 tests)
- `GitAndCodeReviewTest` (2 tests)
- `ToolAndWorkspaceSecurityTest` (2 tests)
- `AsyncExecutionServiceTest` (4 tests)
- `WorkspaceServiceTest` (3 tests)
- `ConfigurationTest` (2 tests)
- `EventBusTest` (2 tests)
- `ErrorHandlingTest` (3 tests)
- `MvcSeparationTest` (3 tests)
- `OsgiServiceRegistrationTest` (1 test)

This baseline establishes that the internal domain and infrastructure layers are mathematically sound. The remaining work is strictly additive: adding the server runtime, web tier, multi-user authentication, interactive review, and administrative observability.
