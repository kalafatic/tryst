# TRYST Execution Environment Monitoring

**Document Identifier:** `TRYST-ARCH-ENV-001`
**Status:** IMPLEMENTED
**Classification:** Operational Architecture Specification
**Related:** `TRYST_NOTIFICATION_ARCHITECTURE.md`, `docs/architecture/TRYST_TASK_LIFECYCLE.md`, `TRYST_SERVER_MONITORING_AND_TELEMETRY.md`

---

## 1. Objective

TRYST must always communicate whether it is **operationally capable of executing submitted tasks**. Task acceptance, execution readiness, actual execution, execution failure and successful completion are distinct states that are never conflated. A successfully accepted task is persisted even when the execution environment is unavailable — but it is never executed until all components required by the default execution pipeline are verified ready.

## 2. Authoritative Component: `ExecutionEnvironmentMonitor`

**Bundle:** `eu.kalafatic.tryst.domain`, package `eu.kalafatic.tryst.domain.env`
**Classes:** `ExecutionEnvironmentMonitor`, `EnvironmentEvents`
**OSGi service:** registered by `ApplicationLifecycleCoordinator` (both RCP and headless modes).

The monitor is the **single authoritative source** of execution environment state. No UI page, web client or scheduler computes its own health state.

```
ExecutionEnvironmentMonitor
    |
    +-- Server RCP static status menu (Environment menu + status line)
    |
    +-- Dashboard (OverviewDashboardPage Environment Health card, web Dashboard card)
    |
    +-- Navigator (Blocked section, environment diagnostics context menu)
    |
    +-- Task Scheduler / TaskOrchestrator (readiness gate, blocking, release)
    |
    +-- HTTP API (/api/environment/status, /api/environment/refresh,
    |    task submission acceptance fields)
    |
    +-- Web Client (Dashboard card, intake feedback)
    |
    +-- Notifications (NotificationService + EventBus)
```

### 2.1 Snapshot contract

`EnvironmentSnapshot` contains:

| Field | Meaning |
|---|---|
| `components` | SERVER / HARNESS / PROVIDER / MODEL component states |
| `health` per component | `UNKNOWN` (gray) · `STARTING`/`DEGRADED` (orange) · `OPERATIONAL` (green) · `UNAVAILABLE` (red) |
| `problems` | Readiness blockers in display order |
| `requiredComponents` | Components required by the default execution pipeline |
| `ready` | Authoritative overall readiness |
| `readyMessage` | Human-readable readiness message |
| `lastSuccessfulCheck` / `lastFailedCheck` | Last check outcomes (never fabricated) |
| `episodeKey` | Stable readiness-episode key for notification deduplication |

### 2.2 Check semantics

- **SERVER** (always required): application stage ≥ APPLICATION_READY, task orchestrator running, web server running unless explicitly disabled by configuration. Never derived from process existence alone.
- **HARNESS** (required only when a connected coding application is selected and enabled): live `testConnection()` against the selected harness. A selected-but-unreachable harness blocks readiness (no silent fallback). No artificial start/stop: TRYST does not control external harness platforms; the operator fixes the external service or selects a different harness.
- **PROVIDER** (always required): live connectivity test against the selected default provider (`AiProviderRegistry.testConnection`, 15 s bound). When no default provider is selected, any enabled provider that is ONLINE in the shared model catalog satisfies readiness and the indicator shows DEGRADED (configuration gap visible, working install not blocked).
- **MODEL** (always required): the selected model must be available in the model catalog. A missing selected model with at least one other available model shows DEGRADED (fallback execution) and does not block; no available model at all blocks readiness.

Each external check is bounded by a per-check timeout (`DEFAULT_CHECK_TIMEOUT = 10 s`) executed on a dedicated daemon executor; a hanging endpoint can never stall the monitoring thread. The periodic cycle runs on a single daemon scheduler thread (default 15 s, configurable via `tryst.environment.checkIntervalSeconds`, floor 5 s). Every 4th cycle (and every explicit refresh, and every provider reconnection) refreshes the provider model catalog.

### 2.3 Events

| Event | Published when | Consumers |
|---|---|---|
| `EnvironmentEvents.StatusChangedEvent` | Any component state, readiness or problem set changed | RCP status line, Dashboard page |
| `EnvironmentEvents.ReadinessChangedEvent` | Readiness changed, or blocking episode changed | `TaskOrchestrator` (release/park tasks, admin notifications), `EnvironmentUiListener` (recovery popup) |
| `EnvironmentEvents.TaskBlockedEvent` | An incoming task was accepted and parked as BLOCKED | `EnvironmentUiListener` (admin dialog, once per episode) |

Recovery detection: a NOT_READY → READY transition publishes `ReadinessChangedEvent(ready=true)`; the orchestrator then releases every BLOCKED task back to QUEUED in FIFO order and notifies each submitting user ("Execution environment restored — task is starting").

## 3. Task Blocking Lifecycle

1. Task received (HTTP intake or plain POST) → validated and **persisted first**.
2. `TaskOrchestrator.submitTask` evaluates `monitor.isReady()` (unassessed state is treated as ready until the first assessment).
3. Ready → normal enqueue + `TASK_ACCEPTED` notification (existing flow).
4. Not ready → `CREATED → BLOCKED` with the reason persisted in `failure_reason`, a `TaskEvent` row, `TaskBlockedEvent`, user notification ("Task accepted — execution delayed" + problems), administrator notification (one per episode, deduplicated via `admin:TASK_BLOCKED:<episodeKey>`), **never enqueued**.
5. The dispatcher additionally never claims tasks while `!monitor.isReady()`, and queued tasks are parked as BLOCKED when readiness degrades (`ReadinessChangedEvent(ready=false)`).
6. On recovery the release is automatic; dismissing any notification never cancels or removes a task.

`TaskStatus.BLOCKED` transitions (validated by `TaskStateTransitionValidator`): `CREATED → BLOCKED`, `QUEUED → BLOCKED`, `BLOCKED → QUEUED`, `BLOCKED → CANCELLED`, `BLOCKED → FAILED`. The coarse `ExecutionStatus` mapping reports BLOCKED as QUEUED for backward compatibility; consumers that need the exact state use `TaskStatus`.

## 4. RCP Static Status Section

- **Environment menu** (`ApplicationActionBarAdvisor`): permanent top-level menu, rebuilt on every open from the authoritative snapshot. Shows overall readiness plus the four component indicators with the GREEN/ORANGE/RED/GRAY convention. Per-component context menus expose only supported actions: TRYST Server (health details, start/stop/restart web server, monitoring), Harness (health details via live connection test, re-check, harness selection via `ConnectedApplicationRegistry`, TEIP configuration), Provider (health details, re-check, refresh models, provider selection via `AiProviderRegistry`), Model (details, refresh availability, model selection via `AiModelRegistryService`).
- **Status line** (`ApplicationWorkbenchWindowAdvisor`): permanent "Execution Environment: READY / NOT READY — <first problem>" indicator updated via `StatusChangedEvent`, always visible regardless of the active page or editor.
- **`EnvironmentStatusDialog`** (application bundle, `eu.kalafatic.tryst.application.env`): full snapshot, problems, last checks, recovery guidance, refresh, harness/provider/model selection.
- **`EnvironmentNotReadyDialog` + `EnvironmentUiListener`**: actionable administrator notification on incoming blocked tasks (Start Missing Components / Open Environment Status / View Task / Keep Task Queued / Dismiss), opened at most once per readiness episode; recovery information dialog on restoration.
- **Dashboard** (`OverviewDashboardPage`): permanent Environment Health card (readiness, components, problems, last checks, Re-check + Recovery Wizard buttons), Blocked Tasks count, Active Workers.

## 5. HTTP API

| Endpoint | Purpose |
|---|---|
| `GET /api/environment/status` | Authoritative environment snapshot JSON |
| `POST /api/environment/refresh` | Explicit full assessment including model discovery; returns fresh snapshot |
| `POST /api/tasks` response | Extended with `accepted`, `execution` (STARTING/DELAYED), `environmentReady`, `problems` |
| `POST /api/tasks/intake/submit` response | Same acceptance fields appended |

## 6. Web Client

- New Task modal: `execution === "DELAYED"` renders "Task accepted — execution delayed" with problems, admin-notified note and safe-queue statement; otherwise "Task accepted — execution starting".
- Dashboard: Execution Environment card (readiness badge, component dots, problems, last checks, Re-check button) polling the authoritative endpoint; Blocked Tasks stat.
- Studio: `BLOCKED` status badge (amber) with the persisted block reason in the tooltip.
- Recovery notifications arrive over the existing SSE notification stream (no new channel).

## 7. Logging & Auditability

All transitions are logged via `TrystLog` with task/component identifiers: component health changes, readiness changes, task blocked, administrator notified, environment restored, task released. `TaskEvent` rows persist every BLOCKED/QUEUED transition. User-facing messages stay concise; credentials are never logged or exposed (provider configuration is masked by the existing registry contract).

## 8. Verification

- `ExecutionEnvironmentMonitorTest` — 9 tests: component evaluation, readiness problems, recovery detection, fallback-model semantics, full SQLite-based blocked-task flow with automatic release, event contract.
- `TaskLifecycleTransitionTest` — extended with BLOCKED transition validation and blocked lifecycle.
- Regression batches: `TaskOrchestratorIntegrationTest`, `TaskQueueConcurrencyTest`, `StartupModeAndReadinessTest`, `AsynchronousTaskExecutionHttpE2ETest`, `SessionContinuationHttpTest`, `ConnectedApplicationRegistryTest`, `OsgiServiceRegistrationTest`, `DomainModelTest` — all green.
