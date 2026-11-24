# TRYST – Asynchronous Coder 2: Baseline Audit

**Date:** 2026-11-21
**Stage:** Phase A – Existing Implementation Audit (pre-modification baseline)
**Scope:** `/home/petr/git/tryst-221126`
**Baseline build:** `./mvnw -B -o verify` (JDK 21, offline)
**Baseline test result:** 373 tests run, 372 passed, 1 failure (environment-dependent, see §8)

---

## 1. Component Ownership Map

| Responsibility | Owner (verified) | Location |
|---|---|---|
| RCP entry / headless server entry | `TrystApplication` / `TrystServerApplication` | `plugins/eu.kalafatic.tryst.application/.../TrystApplication.java`, `TrystServerApplication.java` |
| Lifecycle coordination, service wiring, OSGi registration | `ApplicationLifecycleCoordinator` | `.../application/lifecycle/ApplicationLifecycleCoordinator.java` |
| HTTP server, routing, all REST handlers, embedded SPA | `TrystHttpServer` (8810 lines, inner handlers) | `plugins/eu.kalafatic.tryst.infrastructure/.../server/TrystHttpServer.java` |
| TCP text-protocol server | `TrystTcpServer` | `.../infrastructure/server/TrystTcpServer.java` |
| SQLite manager (WAL, FK, busy_timeout, single connection + global lock) | `SqliteDatabaseManager` | `.../infrastructure/persistence/SqliteDatabaseManager.java` |
| Schema migrations (forward-only, V1–V21, 40 tables) | `SchemaMigrationService` | `.../infrastructure/persistence/SchemaMigrationService.java` |
| Task queue (persistent; derived from `tasks.status='QUEUED'`; atomic claim) | `TaskQueue` + `SqliteTaskRepository.claimNextQueuedTask` | `.../domain/service/TaskQueue.java` |
| Task orchestration / worker pool (fixed pool = CPU/2, dispatcher thread) | `TaskOrchestrator` | `.../domain/service/TaskOrchestrator.java` |
| Per-task stage execution engine | `TaskWorker` | `.../domain/service/TaskWorker.java` |
| Task state machine | `TaskStatus`, `TaskStateTransitionValidator`, `TaskLifecycleManager` | `.../domain/model/TaskStatus.java`, `.../domain/service/` |
| Recovery of interrupted tasks | `TaskRecoveryService` | `.../domain/service/TaskRecoveryService.java` |
| Workspace / sandbox allocation & cleanup | `WorkspaceService` | `.../domain/service/WorkspaceService.java` |
| Repository mirror pool | `RepositoryPoolManager` | `.../infrastructure/vcs/RepositoryPoolManager.java` |
| Git operations (CLI, no JGit) | `GitService` | `.../infrastructure/vcs/GitService.java` |
| Sandbox process execution SPI | `SandboxProvider` / `DefaultProcessSandboxProvider` | `.../domain/sandbox/`, `.../infrastructure/sandbox/` |
| Diff generation & parsing | `GitTools.GitDiffTool`, `DiffParser`, `DiffModel` | `.../domain/tool/GitTools.java`, `.../domain/review/` |
| Code review / change sets / revisions / comments | `CodeReviewService`, `CodeReviewRepository` | `.../domain/review/`, `.../infrastructure/persistence/repository/SqliteCodeReviewRepository.java` |
| Build/test execution | `BuildAndTestService`, `DefaultBuildTechnologyDetector`, `ResultValidator` | `.../infrastructure/build/`, `.../domain/ai/ResultValidator.java` |
| Task intake / validation | `DefaultTaskIntakeService` | `.../domain/intake/DefaultTaskIntakeService.java` |
| AI providers & model registry | `OllamaProvider`, `EnrProvider`, `AiModelRegistryService`, `AiProviderRegistry`, `ModelSelectionService` | `.../infrastructure/ai/`, `.../domain/ai/` |
| Coding agent loop | `CodeAgent` (legacy path), `OpenCodeCodingHarnessAdapter` (primary harness) | `.../domain/ai/CodeAgent.java`, `.../infrastructure/cli/` |
| Monitoring / telemetry | `ServerMonitoringService`, `TelemetryService`, `AiMonitoringService` | `.../domain/service/` |
| Readiness registry | `ServerReadinessService` (OSGi-only, not exposed over HTTP) | `.../core/lifecycle/ServerReadinessService.java` |
| Web frontend | Embedded SPA (`APP_HTML` text block, vanilla JS, no framework) | `TrystHttpServer.java` lines 4617–8625 |
| RCP UI | Navigator + views + editor pages | `plugins/eu.kalafatic.tryst.ui/` |

## 2. Server Lifecycle & API Surface (as found)

- Headless daemon: `-application eu.kalafatic.tryst.application.server`; CLI/REPL commands exist.
- HTTP on configured port (default 38080, port-8080 prohibition enforced), fixed 10-thread executor, JDK `HttpServer`.
- Auth: bearer token + HttpOnly cookie; PBKDF2 users; role/permission model; CSRF for cookie auth; dev-mode `SecurityContext.DEV` (full admin) fallback is **on by default** (`tryst.dev.mode`).
- API inventory (existing): `/api/status`, `/api/setup`, `/api/auth/*`, `/api/models`, `/api/projects` (GET/POST), `/api/tasks` (GET/POST), `/api/tasks/intake/*`, `/api/tasks/{id}/instructions|events|cancel`, `/api/reviews` (+ diff, files/edit, approve, reject, commit, commits, push, build-test, details, comments, explain, rework), `/api/notifications/*` (SSE stream), `/api/github/*`, `/api/admin/*` (server, bundles, services, http, workers, tasks, workspaces, models, git, builds, overview, users, sessions, processes, audit, logs, telemetry, database, ai, harnesses, config), `/api/schedules/*`, `/api/workflows/*`, `/api/pools`, `/api/studio/*`, `/api/integration/evo/*`, `/api/integration/applications/*`, `/api/mcp`, `/api/contextual/*`.
- SSE exists for notifications only; no WebSocket server (port 38480 reserved but unbound).

## 3. Task/Worker/Queue Execution Flow (verified)

1. Web → `POST /api/tasks/intake/submit` → `DefaultTaskIntakeService.submitIntake`.
2. Project auto-provision from repo URL; `Task` created with **`sessionId = null`**; isolated workspace `sandboxes/<owner>/<taskId>` allocated **before** enqueue (race fix present).
3. `TaskOrchestrator.submitTask` → save → `TaskQueue.enqueue` (CREATED→QUEUED, task_events row, EventBus).
4. Dispatcher thread claims QUEUED tasks (priority DESC, created_at ASC, atomic conditional UPDATE → CLAIMED with `worker_id`).
5. `TaskWorker.call()`: CLONING → ANALYZING → PLANNING → IMPLEMENTING (OpenCode harness or `CodeAgent`) → BUILDING → TESTING → review creation (diff via `git add -N .` + `git diff`) → WAITING_FOR_REVIEW.
6. Review: approve (fingerprint + revision + blocking-comment gates) → commit (authorization gate, `git_commits` traceability) → COMPLETED. Rework re-queues same task with `rework_count++` and injects unresolved comments into prompt.
7. Failures → FAILED with `failure_reason` + `ExecutionResult`.

## 4. Database (verified)

- SQLite, WAL, FK ON, busy_timeout 5000, single connection + global monitor, `runInTransaction`/`supplyInTransaction`, integrity checks at startup.
- Migrations V1–V21, 40 tables + `schema_version`. Key tables: `projects`, `workspaces`, `sessions`, `tasks`, `task_events`, `task_instructions`, `task_recovery_checkpoints`, `execution_results`, `code_reviews`, `review_revisions`, `review_comments`, `review_file_edits`, `git_commits`, `users`, `user_sessions`, `audit_events`, `telemetry_snapshots`, `notifications`, `repository_mirrors`, `ai_providers`, `ai_models` (unused), `managed_processes` (unused), workflow/schedule/studio/harness tables.
- No dedicated worker registry table, no project build/test config columns.

## 5. Confirmed Defects Blocking the Asynchronous Coder 2 Workflow

| # | Defect | Evidence | Severity |
|---|---|---|---|
| D1 | `repository_mirrors` writer/reader use wrong column names (`repository_url`, `last_sync_at`, `status` vs schema `remote_url`, `last_synced_at`, NOT NULL `mirror_fingerprint`); exceptions swallowed → mirror metadata never persisted, DB-backed repo list dead | `RepositoryPoolManager.recordMirrorPersistence`, `TrystHttpServer` `/api/tasks/intake/repositories` | High |
| D2 | `tasks.agent_type` never persisted (not in INSERT/SELECT) → always null after reload | `SqliteTaskRepository` | Medium |
| D3 | `execution_result_id` set in memory after last save, never persisted | `TaskWorker.call()` end | Medium |
| D4 | Pause of a running task behaves as cancel (PAUSED→CANCELLED at catch); pause of CREATED task throws; `TaskExecutionContext.resume()` has no caller | `TaskWorker.checkCancelled`, `TaskOrchestrator.pauseTask` | High |
| D5 | Queued/active tasks parked in RECOVERY_REQUIRED at restart; `retryRecoveredTask`/`forceRetryRecoveredTask` have no HTTP/UI caller; no automatic re-queue | `TaskRecoveryService`, grep of callers | High |
| D6 | Session continuation unwired: `TaskWorker` loads history by `task.sessionId`, but every submission path passes `sessionId = null`; no continue endpoint | `DefaultTaskIntakeService.submitIntake`, `TasksHandler` | High |
| D7 | `CodeAgent` fabricates file changes ("autonomous fallback") and reports SUCCESS when the AI provider is down/unconfigured → false task success | `CodeAgent.applyAutonomousFallback` | High |
| D8 | `/api/status` always "UP"; no public health endpoint; `/api/admin/server` hardcodes 3 components; readiness registry not exposed over HTTP; no uptime/version in status | `StatusHandler`, `AdminHandler` | High |
| D9 | No real statistics: task stats computed by iterating all rows ad hoc; no per-worker stats; no queue-position API; `telemetry_snapshots.ai_latency_ms` always 0.0; `/api/admin/telemetry` omits half the persisted fields; `AiMonitoringService.recordInference` only fed by RCP test button | `TelemetryService.captureSnapshot`, `AdminHandler` | High |
| D10 | `PoolsHandler` mislabels `workspaces.allocated` (all workspaces incl. ARCHIVED); no mirror sync status/age; `listMirrors` is a raw fs listing | `PoolsHandler` | Medium |
| D11 | Frontend: side-by-side diff data returned by backend but ignored (unified only); Studio bottom tabs (console/build/tests/events) are hardcoded fake text; Studio "History" editor mode fabricated; `evoTab` unreachable; reviews sidebar project list items have no onclick; global project selector does not filter; project detail table hardcoded; admin users view has no action buttons; AI model "Browse" action (`show`) unsupported by backend | `APP_HTML` JS | High |
| D12 | `/api/reviews/{id}/explain` returns canned text, not AI | `ReviewsHandler` | Medium |
| D13 | Project configuration (repo URL, branch, build/test commands, instructions) has no persistence or UI; `.tryst-build.json` is the only build config and pollutes the repo if written | DB schema, `ProjectsHandler` (GET/POST only) | High |
| D14 | `POST /api/admin/config` not audited | `AdminHandler` | Low |
| D15 | `ensureMirror`/`cloneOrFetch` capped by fixed 30 s Git timeout → large clones killed; `git add -N` mutates index on read-only diff endpoints; default branch hardcoded `master` in several places | `GitService`, `RepositoryPoolManager` | Medium |
| D16 | `DefaultStudioService` bypasses the DB lock discipline (raw JDBC) | `DefaultStudioService` | Low |
| D17 | SSE stream consumes a fixed-pool HTTP thread indefinitely (10 concurrent streams exhaust executor) | `NotificationsHandler` stream loop | Medium |
| D18 | Dev-mode default grants unauthenticated admin access to all endpoints | `ApplicationConfiguration.isDevelopmentMode` | Documented limitation (not silently changed) |

## 6. Missing vs. Requested Capabilities (Phase B–G)

- **B (Server administration):** partially present (`/api/admin/*`). Missing: real health endpoint, uptime/version, readiness-based component states, pool/queue detail, honest component status, admin user-action UI.
- **C (Worker/pool):** worker pool exists (thread pool + claim), but no worker identity/registry, no queue visibility/positions, no recovery retry surface, no pool statistics from DB, no stale sandbox detection, no resource limits.
- **D (Statistics):** telemetry snapshots exist; task/worker/pool statistics missing.
- **E (Session continuation):** conversation history infrastructure exists but is unwired; no continue/resume endpoint; workspace reuse for continuation missing.
- **F (Project configuration):** minimal (name/location); no repo/branch/build/test/instruction persistence or UI.
- **G (Diff review):** unified diff + comments + rework + approve/commit/push exist; side-by-side UI missing; studio bottom tabs fake; explain endpoint stubbed.

## 7. Concurrency & Isolation Findings

- Per-task isolated sandbox dirs (`sandboxes/<owner>/<taskId>`), atomic claim, single dispatcher, fixed worker pool (`CPU/2`).
- Task→workspace mapping persists in DB; workspace allocation precedes enqueue.
- Cross-task leakage risks: `RepositoryLockManager` dead code (no caller), mirror locks never evicted, `--reference` clones share object store with mirror (read-only), `SessionTaskOrchestrator` legacy global cancel + `getCurrentWorkspace()` (RCP legacy path only).

## 8. Baseline Test Execution

Command: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -o verify`

Result: **373 tests, 372 passed, 1 failed.**

Failing: `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow`
- Root cause: environment-dependent — the test hardcodes Ollama model `qwen3-coder-30b-32k:latest` which is **not installed** on this host (installed: `smollm:135m`); the real OpenCode execution consequently fails and the task ends in FAILED instead of WAITING_FOR_REVIEW.
- Classification: **BLOCKED (environment)**, pre-existing, not caused by TRYST code defects.

Environment verified during baseline: OpenCode CLI present (`~/.opencode/bin/opencode`), Ollama reachable at `localhost:11434` with `smollm:135m`.

## 9. Baseline Conclusion

The platform skeleton is strong: real lifecycle coordination, persistent queue, atomic claiming, per-task isolation, diff/review/commit gates, auth, SSE notifications, admin endpoints, and a working SPA. The gaps are concentrated in: (1) broken persistence paths (D1–D3), (2) pause/recovery/session-continuation semantics (D4–D6), (3) honest failure reporting (D7), (4) real operational status/statistics (D8–D10), (5) frontend dead paths and fake data (D11), and (6) project configuration persistence (D13).
