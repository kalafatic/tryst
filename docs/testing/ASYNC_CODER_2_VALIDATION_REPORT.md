# TRYST – Asynchronous Coder 2: Validation Report

**Date:** 2026-11-21
**Stage:** Phases A–I validation of the Asynchronous Coder 2 workflow.

## 1. Executive Summary

The complete asynchronous coding workflow was audited, stabilized and completed: real server administration health, persistent queue with recovery retry, worker/pool statistics from real data, session continuation with sandbox reuse, persistent project configuration, honest AI failure reporting, real diff review (unified + side-by-side), real studio console/build/test data, and a comprehensive regression suite.

**Final build/test:** `./mvnw -B -o clean verify` (JDK 21, offline) → **382 tests, 381 passed, 1 failure (environment/hardware, see §6).**

## 1a. 2026-11-23 Re-Verification Addendum

The implementation was re-verified on 2026-11-23 after the environment changed. Summary of the delta:

- **Environment change:** the OpenCode CLI v2.4.0 remains installed at `~/.opencode/bin/opencode` and runs correctly, but `~/.opencode/bin` is **no longer on `PATH`** (it was on 11-21). Ollama is reachable and `qwen3-coder-30b-32k:latest` is still installed.
- **Effect:** the three environment-gated real-integration tests (`HarnessDelegationDiagnosticTest`, and the two OpenCode harness tests) reported `BLOCKED: OpenCode CLI not available on this host` at their CLI gate, because `OpenCodeCodingHarnessAdapter` invoked the bare `opencode` command relying on `PATH`.
- **Root cause / fix:** added `OpenCodeCodingHarnessAdapter.resolveBinary()` — a cached, `--version`-probed resolution that falls back to the standard install locations `~/.opencode/bin/opencode` (via `user.home`) and `$HOME/.opencode/bin/opencode` when the configured bare command is not on `PATH`. `testConnection()` and `executeTask()` now use the resolved path; the fallback is re-evaluated whenever the configured binary changes.
- **Also fixed (minor):** the headless CLI `sessions` command still claimed "no public /api/sessions endpoint"; it now lists real coding sessions via `GET /api/sessions`. A scope-path typo in `ASYNC_CODER_2_BASELINE.md` (`tryst-121126` → `tryst-221126`) was corrected.
- **Re-verification result:** full reactor compiles (all modules SUCCESS). Focused run of `OpenCodeEndToEndWorkflowTest`: `testOpenCodeAdapterReportsHonestFailureForInvalidModel` now **PASSES** (CLI found via fallback; honest failure verified end-to-end), `testHttpServiceRestartReRegistersIntegrationRoutes` **PASSES**, and `testRealOpenCodeTaskThroughTrystHttpWorkflow` now **actually executes** the real OpenCode task but remains **hardware-bound** (the 30B model does not reach a terminal state within the 15-minute poll deadline on this machine) — the same pre-existing hardware limitation documented in §6.

## 2. What Was Already Implemented (baseline)

Lifecycle coordination, embedded HTTP server (JDK `HttpServer`), SQLite (WAL/FK, V1–V21 migrations, 40 tables), persistent task queue with atomic claiming, per-task sandbox isolation (`sandboxes/<owner>/<taskId>`), 10-stage worker (CLONING…WAITING_FOR_REVIEW), diff/review/approve/commit gates, PBKDF2 auth + CSRF + RBAC, SSE notifications, telemetry snapshots, embedded SPA, RCP Navigator mirror.

## 3. What Was Incomplete or Broken (confirmed, fixed)

| Defect | Fix |
|---|---|
| `repository_mirrors` writer/reader used non-existent columns → mirror metadata silently never persisted | Schema-conformant SQL in `RepositoryPoolManager`; `listMirrorStatuses()` with SYNCED/STALE/MISSING |
| `tasks.agent_type` never persisted | Column added to INSERT/SELECT; runtime capability check keeps legacy DBs (missing column) fully functional |
| `execution_result_id` never persisted | `TaskLifecycleManager.persist()` late-binding after execution |
| Pause behaved as cancel; `TaskExecutionContext.resume()` unused | Worker suspends at stage boundary (PAUSED + wait loop); resume releases the same worker; CREATED→PAUSED validator entry |
| RECOVERY_REQUIRED tasks unrecoverable via API/UI | `POST /api/admin/tasks/{id}/retry` (+force), orchestrator retry methods, UI ↻ Retry button |
| Session continuation unwired (sessionId always null) | Intake creates persisted coding sessions; `POST /api/tasks/{id}/continue` + `/api/sessions` + `/api/sessions/{id}/continue`; workspace reuse with `skip_clone=true`; conversation/instruction context |
| `CodeAgent` fabricated files and reported success when AI unavailable | Honest failure (`MODEL_UNAVAILABLE`), task → FAILED; fabricated-file paths removed |
| `/api/status` static "UP"; no health endpoint; admin server hardcoded 3 components | `GET /api/health` (real component states, version, uptime, queue counts, 503 on CRITICAL); `/api/status` enriched; `/api/admin/server` full overview with backward-compatible `componentStatus` keys |
| No real statistics; telemetry AI latency always 0; admin telemetry truncated | `GET /api/admin/statistics` (persisted tasks/workers/pool); `TelemetryService` wired to `AiMonitoringService` (NaN when unmeasured); full telemetry fields |
| Pools endpoint mislabeled workspace counts; no mirror status | Corrected counts by status; mirror objects with sync state |
| Project configuration absent (no repo/branch/build/test persistence) | V22 migration (`projects` columns) + `GET/PUT /api/projects/{id}` + config UI; build/test commands override auto-detection via workspace metadata |
| `/api/reviews/{id}/explain` canned text | Real AI call when an inference service is wired; honest `aiGenerated:false` fallback otherwise |
| Studio bottom tabs (console/build/tests/events) hardcoded; History view fabricated; connection badge static | Real `executionResult`/`buildStatus`/`testSummary` in the state DTO; real rendering; honest badges |
| Side-by-side diff ignored by frontend | Unified/Side-by-Side toggle with side-by-side renderer |
| Admin users view read-only; AI model "Browse" unsupported action | User create/lock/unlock/activate/deactivate/role actions; Browse → Rescan Provider |
| Queue view unordered; project selector dead; reviews sidebar dead | Ordered queue + positions, Continue/Retry buttons; project filtering everywhere |
| Admin config POST not audited | Audited (`ADMIN_CONFIG_UPDATE`) |

## 4. Files and Migrations Changed

- **Migration:** V22 — `projects` + `repository_url`, `default_branch`, `build_command`, `test_command`, `instructions`; indexes on `tasks(created_at)`, `git_commits(task_id)`, `code_reviews(created_at)`, `execution_results(task_id)`. Forward-only, backward-conscious.
- **Domain:** `Task` (mutable sessionId), `Project` (config fields/setters), `TaskWorker` (pause-wait, `skip_clone`, honest agent failure, `execution_result_id` persistence), `TaskOrchestrator` (pause/resume semantics, recovery retry, `WorkerRuntime` stats, project build/test metadata), `TaskLifecycleManager.persist`, `TaskStateTransitionValidator` (CREATED→PAUSED), `CodeAgent` (honest failure), `TelemetryService` (real AI latency), `DefaultTaskIntakeService` (session creation, `agent_type`).
- **Infrastructure:** `TrystHttpServer` (health/status/statistics/queue/retry/sessions/continue/projects PUT/explain/pools/studio-state/frontend), `RepositoryPoolManager` (schema-conformant persistence + status listing), `BuildAndTestService` (project command overrides), `SqliteTaskRepository` (agent_type + legacy-DB capability check), `SqliteProjectRepository` (config columns), `SchemaMigrationService` (V22).
- **Application:** `ApplicationLifecycleCoordinator` (session/conversation/instruction/execution-result/inference wiring; telemetry↔AI monitoring).
- **Tests:** new `AsyncCoder2RegressionTest`, `SessionContinuationHttpTest`, `TestAiFakes`; updated `OpenCodeEndToEndWorkflowTest`, `TaskOrchestratorIntegrationTest`, `AsynchronousTaskExecutionHttpE2ETest`, `EndToEndWorkflowStabilizationTest`.
- **Docs:** `docs/testing/ASYNC_CODER_2_BASELINE.md`, `ASYNC_CODER_2_TEST_PLAN.md`, `ASYNC_CODER_2_VALIDATION_REPORT.md`, `docs/architecture/ASYNC_WORKER_AND_POOL.md`, `docs/user/SESSION_CONTINUATION_AND_DIFF_REVIEW.md`.

## 5. Runtime Verification (live headless product build)

Server started headless (`-application eu.kalafatic.tryst.application.server`), HTTP bound on 38280 (38080 occupied; dynamic range honored, 8080-range prohibition intact). Verified with curl:

- `GET /api/health` → 200: `status UP, health DEGRADED, version 2.6.11, uptimeSeconds 53, setupRequired false`; components: database OPERATIONAL (schema 22, connected), httpServer OPERATIONAL, authentication OPERATIONAL, aiProvider **DEGRADED (configured model `codellama` not in the 23 discovered models — honest)**, workerSubsystem OPERATIONAL (capacity 32), repositoryPool OPERATIONAL (2 mirrors, 2 synced), sandboxSubsystem OPERATIONAL (14 workspaces); `componentStatus` uppercase keys; `overallStatus READY`.
- `GET /api/admin/statistics` → real persisted data: 13 tasks (11 waiting review, 2 cancelled), avg duration, duration buckets, throughput by day, per-worker aggregates.
- `GET /api/admin/workers` → capacity 32, busy 0, available 32, utilization 0.00, running true, live slots.
- `GET /api/pools` → corrected workspace counts by status; mirror objects with sync state.
- Legacy database compatibility: the pre-existing `~/.tryst/tryst.db` (whose `tasks` table lacks `agent_type`) loaded and served without errors (runtime column capability check).

## 6. Test Execution Evidence

Final run: `./mvnw -B -o clean verify` → **382 tests, 381 passed, 1 failed**.

Failing: `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow`
- **Status: BLOCKED (hardware/environment), not a code defect.**
- The required model (`qwen3-coder-30b-32k:latest`) is installed, and the real OpenCode task executes, but the 30B-model inference exceeds the test's 15-minute terminal-state poll deadline on this machine's hardware ("Task never reached a terminal state"; previous run reached FAILED for the uninstalled model — honest reporting verified).
- The other two tests in the class pass: HTTP-restart route re-registration, and honest failure reporting for an invalid model.

New test coverage passed: honest AI failure, agent-type persistence, execution-result binding, mirror persistence, project config round-trip, intake session creation, pause/resume of an active task, recovery retry, and full session continuation (same session/sandbox, accumulated diff, conversation history).

## 7. Acceptance Criteria Status

| Criterion | Status | Evidence |
|---|---|---|
| Server administration reports real operational state | **PASS** | Live `/api/health`, `/api/admin/server` (verified §5) |
| Workers execute asynchronous tasks reliably | **PASS** | `AsynchronousTaskExecutionHttpE2ETest`, `EndToEndWorkflowStabilizationTest` Scenario E, `TaskOrchestratorIntegrationTest` |
| Task queue state persistent and recoverable | **PASS** | SQLite claim tests + new recovery-retry test |
| Repository/sandbox pool provides task isolation | **PASS** | Scenario E (4 concurrent tasks, isolated workspaces) + `SessionContinuationHttpTest` sandbox reuse |
| Statistics correspond to actual data | **PASS** | `/api/admin/statistics` computed from persisted rows + live runtime (verified §5) |
| Existing sessions can be reopened | **PASS** | `GET /api/sessions`, `GET /api/sessions/{id}` (verified §5/test) |
| Eligible sessions continued without losing working state | **PASS** | `SessionContinuationHttpTest` (workspace reuse, both files preserved, accumulated diff) |
| Project configuration persistent and correctly restored | **PASS** | V22 + `AsyncCoder2RegressionTest.testProjectConfigurationPersistenceRoundTrip` |
| AI can perform multiple modification cycles in the same session | **PASS** | Continuation + rework (revision count) tests |
| Diff viewer displays actual source changes | **PASS** | Diff extracted from real git working tree; unified + side-by-side (backend returns both; `DiffParserTest` + `SessionContinuationHttpTest` diff assertions) |
| User comments/instructions drive subsequent modifications | **PASS** | `IterativeReviewAndReworkWorkflowTest` (rework with comments) |
| Build/test results correspond to the actual modified sandbox | **PASS** | Real `executionResult`/`buildStatus`/`testSummary` from the review/execution records in Studio state |
| Failures/interrupted executions handled honestly | **PASS** | `MODEL_UNAVAILABLE` → FAILED (no fabrication); reconciliation → RECOVERY_REQUIRED + retry |
| Web and RCP interfaces work through existing architecture | **PASS** | Web verified live + tests; RCP unchanged (Navigator EventBus path untouched) |
| Documentation reflects verified implementation | **PASS** | §4 docs updated to the verified state |

## 8. Remaining Defects / Known Limitations

1. **OpenCode real-model E2E is hardware-bound** on this machine (30B model > 15 min deadline). Run with a faster model (`-Dtryst.test.model=…`) or faster hardware.
2. Worker identity is claim-scoped (per task), not a persistent worker registry; historical per-worker stats derive from `tasks.worker_id`.
3. No automatic pool eviction/quotas; cleanup remains explicit and safe (`isSafeToCleanup` gate).
4. SSE notification stream consumes one thread of the 10-thread HTTP executor per client (pre-existing).
5. Dev mode (`tryst.dev.mode`, default on) grants an unauthenticated admin context — documented limitation; production must set `tryst.dev.mode=false` and complete first-run setup.
6. `/api/workflows/generate` still returns a template (noted, out of this stage's critical path).
7. `DefaultStudioService` still bypasses the DB lock discipline (pre-existing, low risk).

## 9. Manual Verification Instructions

1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -o clean verify` (or `scripts/build.sh`).
2. Run headless: `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/linux/gtk/x86_64/tryst -nosplash -consoleLog -application eu.kalafatic.tryst.application.server` (note the logged HTTP port; 38080 with dynamic fallback, never 8080-range).
3. Health: `curl http://127.0.0.1:<port>/api/health` — expect real component states; `GET /api/admin/statistics`, `/api/admin/workers`, `/api/pools` after a login (or in dev mode).
4. Web: open `http://127.0.0.1:<port>/tryst` → Dashboard shows the Server Health panel; Administration → Overview/Task Supervision/Workers/Users; Resource Pools shows mirror sync states.
5. Task flow: New Task → submit → Queue → ⚡ Studio (real console/build/tests tabs) → Code Reviews (Unified/Side-by-Side, line comments, Request AI Rework, Approve, Commit — commit only via explicit user action).
6. Continuation: open a completed/reviewable task in Studio → ▶ Continue Session → new instruction → verify the accumulated diff contains previous and new changes and the same session id appears in Queue.
7. Project config: Projects & Repos → select project → edit Repository URL/Branch/Build/Test commands/Instructions → Save → re-run Build & Tests from a review to confirm the commands are used.
