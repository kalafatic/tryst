# TRYST – Adaptive Three-Phase Implementation Roadmap

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Baseline Git Commit:** `539768259664f6695bf44f7efce06615bca6fbeb`

---

## 1. Roadmap Architecture & Principles

This roadmap provides the direct blueprint for the subsequent three implementation phases:
- **PROMPT 02/04 – Implementation Phase 1:** Server Runtime, Embedded HTTP Delivery, Authentication & Durable Background Execution.
- **PROMPT 03/04 – Implementation Phase 2:** Multi-Tenant Workspaces, Robust Autonomous Loop, and Editable Web Git Review.
- **PROMPT 04/04 – Implementation Phase 3:** Server Administration, Full-Spectrum Observability, Notifications & Production Hardening.

### Allocation Rules:
1. Every confirmed finding (`FIND-001` through `FIND-018`) is assigned to exactly one implementation phase.
2. Foundational architectural requirements (headless Equinox daemon, HTTP server, database schema V2, auth tokens, durable task queue) must precede higher-level features that depend on them.
3. No external web frameworks (Spring, Node, Electron, Python) may be introduced. Standard Java 21, pure web standards (HTML5/CSS/ES6 JS), SQLite, and OSGi remain the exclusive architecture.

---

## 2. Phase 1: Server Runtime, Embedded HTTP Delivery, Authentication & Durable Execution (PROMPT 02/04)

### 2.1 Objectives
Transform TRYST from a desktop Eclipse Workbench application into an independent, headless Equinox OSGi server process that provides an embedded HTTP server, user authentication, REST/SSE APIs, durable asynchronous task processing, and the core single-page web application frontend.

### 2.2 Findings Addressed
- **`FIND-001`**: Desktop SWT Workbench Initialization Precludes Headless Server Mode (Critical)
- **`FIND-002`**: Total Absence of Embedded HTTP Server and Web Application Delivery (Critical)
- **`FIND-003`**: Task Execution Bound to Volatile Asynchronous Futures without Persistent Queue (High)
- **`FIND-004`**: Lack of User, Credential, and Authentication Domain Models and Persistence (High)
- **`FIND-007`**: PermissionManager Audit Log Stored Only In-Memory (Medium)
- **`FIND-013`**: Absence of Web Frontend Assets in Repository (High - Core Web App UI)
- **`FIND-014`**: UI Controller Callbacks Tied Directly to SWT Display Thread (Medium)
- **`FIND-015`**: Lack of Session Invalidation and Token Expiration Mechanism (Medium)
- **`FIND-016`**: Application Configuration Lacks HTTP Server Port and Host Settings (Medium)

### 2.3 Modules and Components Touched
- `eu.kalafatic.tryst.core`: Extend `ApplicationConfiguration` with server port/host; add `AuthenticationSession` and `User` contracts.
- `eu.kalafatic.tryst.domain`: Domain entities `User`, `Role`, `AuthenticationSession`; repository interfaces `UserRepository`, `AuthSessionRepository`, `AuditLogRepository`.
- `eu.kalafatic.tryst.infrastructure`: Schema migration V2 (`users`, `auth_sessions`, `audit_logs`); SQLite implementations of user and session repositories; durable `AuditLogRepository`.
- `eu.kalafatic.tryst.application`: Headless server `IApplication` (`TrystServerApplication`) in `plugin.xml`; HTTP server management in `ApplicationLifecycleCoordinator`.
- `eu.kalafatic.tryst.server` (or package within application/infrastructure): Pure Java 21 `HttpServer` service, REST request router, static asset handler, authentication filter, and SSE event streaming.
- Web Application Frontend: Static web assets (`index.html`, CSS, vanilla JS) embedded and served by HTTP server.

### 2.4 Implementation Scope & Exclusions
- **Included:** Headless boot without GUI display; HTTP server listening on configurable port (default 38080); `/api/auth/login`, `/api/auth/logout`, `/api/auth/me`; `/api/projects`, `/api/tasks`, `/api/events` (SSE); password hashing with PBKDF2; persistent task queueing with startup recovery reconciler; web Workbench and Execution Center views.
- **Excluded:** Inline diff editor (Phase 2); full administrative telemetry dashboard (Phase 3).

### 2.5 Acceptance Criteria
1. Server starts cleanly via CLI without `$DISPLAY` or `xvfb-run`.
2. HTTP server responds with HTTP 200 and serves SPA at `http://localhost:38080/tryst`.
3. Default admin user is created on first startup (`admin` / secure generated or configured password).
4. Web client logs in successfully via `/api/auth/login`, receives token, and fetches user details.
5. User submits a task via web UI; task is persisted in SQLite, executed in background, and logs stream via SSE `/api/events`.
6. Terminating browser and logging back in shows task running or completed with execution history preserved.
7. Killing and restarting server reconciles in-flight tasks cleanly.
8. All existing 55 unit/integration tests continue to pass; new tests added for server, auth, and HTTP endpoints.

---

## 3. Phase 2: Multi-Tenant Workspaces, Robust Autonomous Loop, and Editable Web Git Review (PROMPT 03/04)

### 3.1 Objectives
Deliver the critical human-in-the-loop product differentiator: the interactive **Review Center** in the web application with inline diff editing and explicit approval gating before Git commit, alongside multi-tenant workspace isolation and structured JSON tool handling.

### 3.2 Findings Addressed
- **`FIND-005`**: Workspace Service Bound to Single Global In-Memory Workspace (High)
- **`FIND-006`**: Editable Web Git Diff Review Workflow Missing (High)
- **`FIND-008`**: Fragile String-Based Tool Call Parsing in Agent Loop (Medium)
- **`FIND-010`**: Database Connection Pragmas Missing Connection Pool or Multi-Threaded Read Scaling (Low)
- **`FIND-011`**: Git CLI Execution Subprocess Lacks Streaming and Non-Zero Return Code Diagnostics (Low)
- **`FIND-012`**: Inability to Gracefully Cancel Long-Running Ollama HTTP Inferences (Medium)
- **`FIND-017`**: No Durable Storage for Agent Execution Output Chunks (Low)
- **`FIND-018`**: BuildAndTestService Assumes Local Maven Installation on System PATH (Low)

### 3.3 Modules and Components Touched
- `eu.kalafatic.tryst.domain`: Multi-tenant `WorkspaceService` resolving workspace by user/project; `CodeReviewSnapshot`, `ReviewFileEdit`, `ReviewApproval` domain models; JSON tool call parser in `SessionTaskOrchestrator`.
- `eu.kalafatic.tryst.infrastructure`: Schema migration V3 (`code_reviews`, `review_file_edits`, `execution_chunks`); Git diff generation and hunk patch application in `GitService`; Maven wrapper detection (`./mvnw`) in `BuildAndTestService`; Ollama HTTP request cancellation propagation.
- `eu.kalafatic.tryst.server`: Review REST endpoints (`/api/reviews/{id}`, `/api/reviews/{id}/diff`, `/api/reviews/{id}/edit`, `/api/reviews/{id}/approve`, `/api/reviews/{id}/reject`).
- Web Application Frontend: Review Center UI component with syntax-highlighted diff viewer, inline file editor, diff re-validation trigger, and approval commit dialog.

### 3.4 Implementation Scope & Exclusions
- **Included:** Workspace isolation per project/user; structured JSON tool block execution; cancellation token propagation to Ollama; syntax-highlighted diff view in web UI; inline editing of modified files; saving review edits back to workspace; explicit commit gate; incremental chunk persistence.
- **Excluded:** System metrics and administrative dashboard (Phase 3).

### 3.5 Acceptance Criteria
1. Agent completes code generation task; task status transitions to `PENDING_REVIEW` instead of automatically committing or finishing.
2. Web Review Center displays modified files and syntax-highlighted unified/split diff.
3. User opens a modified file in the web editor, makes an edit, and saves it. The workspace file on disk is updated with the user's edit.
4. User clicks "Approve & Commit". Server validates diff consistency, executes Git commit on designated branch, and transitions task to `COMPLETED`.
5. Multiple concurrent users execute tasks in isolated workspaces without cross-tenant path leakage.
6. User cancelling an in-flight task immediately halts Ollama GPU/CPU inference.

---

## 4. Phase 3: Server Administration, Full-Spectrum Observability, Notifications & Hardening (PROMPT 04/04)

### 4.1 Objectives
Complete the production-grade self-hosted server with a comprehensive administrative web dashboard, multi-dimensional observability metrics, persistent audit reporting, desktop/browser notifications, and final security hardening.

### 4.2 Findings Addressed
- **`FIND-009`**: Lack of Administrative and Observability Endpoints and Metrics (Medium)
- Remaining aspects of `FIND-007` & `FIND-013` (Admin UI, Audit Log Viewer, Toast/Push Notifications).

### 4.3 Modules and Components Touched
- `eu.kalafatic.tryst.core`: System telemetry collector gathering JVM heap/threads, OS CPU load, disk usage, and Ollama latency.
- `eu.kalafatic.tryst.domain`: Administrative service contracts (`ServerAdministrationService`).
- `eu.kalafatic.tryst.infrastructure`: Schema migration V4 (`server_metrics`); metrics recording service.
- `eu.kalafatic.tryst.server`: Administrative REST endpoints (`/api/admin/metrics`, `/api/admin/users`, `/api/admin/sessions`, `/api/admin/audit`, `/api/admin/config`).
- Web Application Frontend: Administration Dashboard tab (user management, session revocation, real-time telemetry graphs, audit log search); notification toast manager and HTML5 desktop push notifications.

### 4.4 Implementation Scope & Hardening
- **Included:** Admin user management (create, deactivate, change roles); live server health gauges (CPU, memory, disk, active background tasks); AI inference latency and token tracking; searchable security audit log table; HTML5 push notifications on task completion; CSRF protection, rate limiting on auth endpoints, and final production packaging.

### 4.5 Acceptance Criteria
1. Admin user logs into web interface and accesses Administration dashboard. Non-admin users are denied access (HTTP 403 Forbidden).
2. Admin dashboard displays live system gauges (JVM memory, CPU load average, active threads, SQLite DB size).
3. Admin views active user sessions and can terminate a session immediately.
4. Audit log viewer displays complete searchable history of user logins, path accesses, and tool executions.
5. Task completion triggers browser native desktop notification.
6. Complete end-to-end regression test suite passes cleanly, validating all scenarios A through K.

---

## 5. Dependency & Regression Risk Matrix

```
[Phase 1] Headless Server + Embedded HTTP + Auth + Durable Queue + Core Web UI
    │
    ▼
[Phase 2] Multi-Tenant Workspaces + JSON Tools + Editable Web Git Review + Approval Gate
    │
    ▼
[Phase 3] Server Administration + Observability + Notifications + Production Hardening
```

| Phase | Primary Regression Risks | Mitigation Strategy |
|---|---|---|
| **Phase 1** | Breaking existing desktop launcher or Tycho packaging when adding headless `IApplication`. | Keep desktop perspective/advisor intact while registering new headless server application ID in `plugin.xml`. Run tests in headless mode. |
| **Phase 1** | Schema migration V2 corrupting existing databases. | Use `PRAGMA foreign_keys = ON;`, execute migrations transactionally via `databaseManager.runInTransaction()`. |
| **Phase 2** | Discrepancy between web diff editor and actual Git working tree. | Use cryptographic SHA-256 fingerprinting of working tree state before and after user edits to guarantee consistency. |
| **Phase 2** | Concurrency conflicts in SQLite when multiple tasks write simultaneously. | Rely on existing monitor lock `dbLock` in `SqliteDatabaseManager` and WAL journal mode. |
| **Phase 3** | Telemetry collection adding CPU or memory overhead. | Run metric collection on low-frequency periodic daemon thread (e.g. every 10 seconds), caching results in memory. |
