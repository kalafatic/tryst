# TRYST – Asynchronous Worker and Pool Architecture

**Date:** 2026-11-21
**Status:** Documents the verified implementation (post Asynchronous Coder 2 stage).

## 1. Component Ownership

| Component | Class | Bundle |
|---|---|---|
| Task queue (persistent, priority-ordered, atomic claim) | `TaskQueue` + `SqliteTaskRepository.claimNextQueuedTask` | domain / infrastructure |
| Worker pool & dispatch | `TaskOrchestrator` (fixed pool = `max(1, CPU/2)`, single dispatcher thread) | domain |
| Per-task execution engine | `TaskWorker` (CLONING → ANALYZING → PLANNING → IMPLEMENTING → BUILDING → TESTING → WAITING_FOR_REVIEW) | domain |
| Lifecycle state machine | `TaskStatus`, `TaskStateTransitionValidator`, `TaskLifecycleManager` | domain |
| Recovery | `TaskRecoveryService` (reconciliation → `RECOVERY_REQUIRED`; retry/force-retry) | domain |
| Workspace/sandbox allocation | `WorkspaceService` (`sandboxes/<owner>/<taskId>`, persisted in `workspaces`) | domain |
| Repository mirror pool | `RepositoryPoolManager` (bare mirrors under `<workspace>/mirrors`, metadata in `repository_mirrors`) | infrastructure |
| Worker runtime accounting | `TaskOrchestrator.WorkerRuntime` (live per-claim stats; historical stats derived from `tasks.worker_id`) | domain |

## 2. Execution Lifecycle (verified)

1. Intake (`POST /api/tasks/intake/submit`) validates, auto-provisions a project, creates a `Task` (status CREATED, `agent_type=TRYST`, **bound to a persisted coding `Session`**), allocates the isolated workspace **before** enqueue, persists instructions, then enqueues.
2. `TaskQueue.enqueue` → CREATED→QUEUED via `TaskLifecycleManager` (row update + `task_events` row + EventBus).
3. Dispatcher claims QUEUED tasks (priority URGENT>HIGH>NORMAL>LOW, then `created_at` ASC) with a conditional atomic UPDATE to CLAIMED (`worker_id` = claim id).
4. `TaskWorker.call()` runs the stages, persisting recovery checkpoints per stage; the implementation stage delegates to the OpenCode harness (`ExternalAiDelegator`) or `CodeAgent`; **a failed or unavailable AI now fails the task honestly** (`MODEL_UNAVAILABLE`/`TIMEOUT`/`UNCLASSIFIED_ERROR` → FAILED) — file changes are never fabricated.
5. Build/test verification uses `BuildAndTestService` with technology auto-detection, overridable by project-level `build_command`/`test_command` (carried in workspace metadata).
6. Diff extraction (`git add -N .` + `git diff`), `CodeReview` + `ReviewRevision` creation, WAITING_FOR_REVIEW + notifications.
7. `ExecutionResult` is saved and **`tasks.execution_result_id` is persisted** (late binding after the final transition).

## 3. Queue, Pause, Cancellation, Recovery

- **Queue** is persistent (derived from `tasks.status='QUEUED'`); ordered listing via `GET /api/admin/queue` with positions.
- **Cancellation**: running tasks → context flag + `Future.cancel(true)` + QUEUED/active→CANCELLED transition. Queued tasks → direct transition.
- **Pause**: active tasks suspend **at the next stage boundary** — the worker transitions to PAUSED and waits (500 ms poll) until resumed or cancelled; `resumeTask` releases the same worker thread (no duplicate claim). Pause of CREATED/QUEUED tasks transitions them to PAUSED (CREATED→PAUSED added to the validator).
- **Restart reconciliation**: active/QUEUED tasks are parked in `RECOVERY_REQUIRED` with a checkpoint and recovery decision. **New:** `POST /api/admin/tasks/{id}/retry` (`force` flag) re-queues them through `TaskOrchestrator.retryRecoveredTask`/`forceRetryRecoveredTask`; the UI exposes ↻ Retry.

## 4. Repository Pool & Sandbox Isolation

- Mirrors: `<workspace>/mirrors/<sanitized-url>.git` (bare clone; `git remote update` on reuse; per-URL in-process lock).
- **Mirror metadata is persisted** in `repository_mirrors` (schema V14 columns) and exposed with live status (`SYNCED`/`STALE`/`MISSING`) via `listMirrorStatuses()`.
- Workspaces: `workspaces` rows with `owner_id`, `task_id`, `repository_url`, `branch`, `status`, `metadata_json`. A sandbox is reused only by the same task; **session continuation reuses the previous task's sandbox directory with `skip_clone=true`** so uncommitted changes are preserved and the diff accumulates.
- Cleanup: `WorkspaceService.cleanupWorkspace` only for `isSafeToCleanup()` statuses (COMPLETED/ARCHIVED/CLEANUP_PENDING/FAILED/CLOSED); never deletes active or reviewable work.

## 5. API Surface

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | Public real operational health: per-component status (database, httpServer, authentication, aiProvider, workerSubsystem, repositoryPool, sandboxSubsystem), version, uptime, queue counts; HTTP 503 when CRITICAL |
| `GET /api/status` | Liveness + the same component overview (compat fields preserved) |
| `GET /api/admin/server` | Full overview incl. `componentStatus` uppercase keys (backward compatible) + `overallStatus` |
| `GET /api/admin/workers` | Capacity, busy/available, utilization, live per-worker slots |
| `GET /api/admin/queue` | Ordered queue with positions |
| `GET /api/admin/statistics` | Task stats (totals by status, avg duration, duration buckets, success/failure ratio, throughput by day), worker stats (live + historical per `worker_id`), pool stats (mirror sync states, sandbox counts) |
| `POST /api/admin/tasks/{id}/retry` | Recovery retry (`{force}`) — audited |
| `GET /api/pools` | Corrected real pool data (workspace status counts, mirror objects with sync state) |
| `GET/POST/PUT /api/projects`, `GET/PUT /api/projects/{id}` | Project configuration incl. `repositoryUrl`, `defaultBranch`, `buildCommand`, `testCommand`, `instructions` |
| `GET /api/sessions`, `GET /api/sessions/{id}`, `POST /api/sessions/{id}/continue` | Coding-session browsing and continuation |
| `POST /api/tasks/{id}/continue` | Continuation from a task (session backfilled for legacy tasks) |

## 6. Database Changes (V22, forward-only)

`ALTER TABLE projects ADD COLUMN repository_url/default_branch/build_command/test_command/instructions` + indexes on `tasks(created_at)`, `git_commits(task_id)`, `code_reviews(created_at)`, `execution_results(task_id)`. Legacy databases whose `tasks` table predates the V7 `agent_type` column remain fully functional (`SqliteTaskRepository` adapts its column list at runtime).

## 7. Known Limitations

- Worker identity is claim-scoped (per-task), not a persistent registry; per-worker historical aggregates are derived from `tasks.worker_id`.
- Pool cleanup is explicit (no automatic eviction/quotas yet); `RepositoryLockManager` remains unused.
- The SSE notification stream still occupies one thread of the 10-thread HTTP executor per client.
- Dev-mode (`tryst.dev.mode`, default on) grants unauthenticated admin context; documented as a limitation — production deployments must set `tryst.dev.mode=false` and complete setup.
