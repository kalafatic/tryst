# PHASE 3 — FINALIZATION PLAN

**Date:** 2026-11-16
**Products:** TRYST (`/home/petr/git/tryst-121126`), EVO (`/home/petr/git/evolution-121126`)
**Objective:** finish Phase 3 — functional, robust, end-to-end-proven OpenCode harness
integration in both products with TEIP 1.1 as the canonical inter-product contract.
**Predecessors:** Phase 3 recovery audit (`PHASE-3-RECOVERY-AUDIT.md`), WP-3.10 security
report (`PHASE-3-WP-3.10-SECURITY-REPORT.md`, accepted). Both are treated as verified
input; their findings are the starting point of this plan.

---

## 1. Verified Current Architecture

### 1.1 TRYST (verified in source)

| Layer | Component | Location |
|---|---|---|
| Harness SPI | `CodingHarnessAdapter` (SPI + result records) | `plugins/eu.kalafatic.tryst.domain/src/.../domain/harness/CodingHarnessAdapter.java` |
| Registry | `ConnectedApplicationRegistry` / `DefaultConnectedApplicationRegistry` / `ConnectedApplicationDescriptor` / `ConnectedApplicationRepository` | `.../domain/harness/*` |
| Persistence | SQLite `connected_applications` / `connected_application_state` (migration V17) | `infrastructure/.../SchemaMigrationService.java` |
| Adapters | `OpenCodeCodingHarnessAdapter` (WP-3.7), `EvoCodingHarnessAdapter` (TEIP bridge) | `infrastructure/cli/`, `evo.integration/service/` |
| Orchestration seam | `HarnessAiDelegator implements ExternalAiDelegator` → `TaskWorker` implementation stage | `domain/harness/HarnessAiDelegator.java`, `domain/service/TaskWorker.java:687-699` |
| Wiring | registry + adapters + delegator in `ApplicationLifecycleCoordinator` (fields, lifecycle) | `application/lifecycle/ApplicationLifecycleCoordinator.java:440-471` |
| HTTP management | `/api/integration/applications` (WP-3.10 authorized: Bearer → `MANAGE_AGENTS`) | `application/http/ConnectedApplicationsHttpHandler.java` |
| TEIP 1.1 client | `TeipClient` (202/200, idempotency, `nextSequence`, canonical error envelope, 28080 default) | `evo.integration/client/TeipClient.java` |
| MCP tool plane | `/api/mcp` (Bearer + `X-Execution-Id`, `initialize`, scopes from `evo_executions.granted_scopes`) | `evo.integration/service/EvoHttpHandlers.java`, `evo.integration/mcp/*` |
| Task lifecycle | `TaskWorker` stages CLONING → ANALYZING → PLANNING → IMPLEMENTING (harness or local agent) → BUILD/TEST → WAITING_FOR_REVIEW; TRYST remains authoritative | `domain/service/TaskWorker.java` |

### 1.2 EVO (verified in source)

| Layer | Component | Location |
|---|---|---|
| Harness SPI | `CodingHarnessAdapter` (File-target variant) | `controller/.../harness/CodingHarnessAdapter.java` |
| Registry | `ConnectedApplicationRegistry` singleton, JSON persistence via `ResourceManager` | `controller/.../harness/ConnectedApplicationRegistry.java` |
| Adapter | `OpenCodeCodingHarnessAdapter` (WP-3.9) | `controller/.../harness/OpenCodeCodingHarnessAdapter.java` |
| Darwin tool | `coding_harness_execute` (`CodingHarnessTool`) registered in `ToolFactory` | `controller/.../tools/CodingHarnessTool.java`, `ToolFactory.java:44-53` |
| Developer Mode | prompt documents the harness tool; Darwin remains orchestrator | `orchestration/selfdev/DeveloperEngine.java:293-297` |
| TEIP 1.1 server | `TeipApiController` + DTOs + `TeipExecutionManager` + persisted stores (202/200, auth, `VERSION_MISMATCH`, idempotency, `nextSequence`) | `controller/.../teip/*` |
| TEIP UI | `teip.html` dashboard + `TeipProtocolPage` | `orchestration/teip.html`, `view/.../TeipProtocolPage.java` |

### 1.3 Verified tests (previous run evidence)

- TRYST: registry 6/6, OpenCode adapter 7/7, TEIP client 6/6, protocol contract 5/5, MCP 6/6,
  WP-3.10 boundary 15/15 (2026-11-16 15:33 run, Maven 3.9.12/JDK 21).
- EVO: TEIP suites 37/37 (12:20 run), harness registry+adapter 9/9 (12:28 run).
- Runtime evidence: TRYST live DB migrations V1–V17; connected_applications populated;
  clean startup/shutdown logs (11:21).

## 2. Verified Gaps and Defects (found during finalization inspection)

| # | Defect | Evidence | Severity |
|---|---|---|---|
| G1 | `restartHttpService()`/`initializeHttpServerInstance()` creates a new `TrystHttpServer` but never re-registers `/api/integration/evo`, `/api/mcp`, `/api/integration/applications` → after an HTTP restart all Phase 3 routes vanish | `ApplicationLifecycleCoordinator.java:1214-1259` vs registrations only at `:596-604` | HIGH (workflow outage after restart) |
| G2 | `--model <bare-id>` (no provider prefix) fails in OpenCode v1.19.5 with exit 1 "Model not found: <id>/"; adapter passes the configured model verbatim; ops doc example uses a bare id | live CLI probe 2026-11-16 15:51 (EXIT=1); `TEIP-OPERATIONS.md` | HIGH (documented configuration fails) |
| G3 | No end-to-end execution evidence: previous live smoke runs hit the 5-min timeout (cold model load); neither product had a proven real task completion with file changes | surefire reports (12:28/12:30/15:33) | ACCEPTANCE-BLOCKING |
| G4 | TRYST `EvoCodingHarnessAdapter` (TEIP bridge) has no dedicated test | test inventory | MEDIUM |
| G5 | Orphaned legacy `OpenCodeCliAdapter` (SandboxProvider impl) has no callers since WP-3.6 rewiring | grep: only self-references | LOW |
| G6 | TRYST web UI has no connected-applications management page (backend-only; docs §2.4 describe curl-level usage only — corrected) | `TrystHttpServer.java` has no `/api/integration/applications` UI references | LOW (API is the contract) |
| G7 | EVO `data/evolution.db` lacks `teip_*` tables while idle (created lazily at server start by `TeipPersistenceStore`) | sqlite `.tables` | VERIFY (needs live EVO server run) |
| G8 | EVO `TEIP-OPERATIONS.md` still 1.0-titled with stale `localhost:3000/mcp` example | file content | LOW (doc) |

Security notes (deferred per task §10, not blocking functionality): `EvoIntegrationHandler`
does not enforce authorization (pre-existing, out of finalization scope); emergency dev
mode is default-on (Phase 1/2 platform decision).

## 3. End-to-End Execution Paths (verified by source inspection)

### TRYST task → OpenCode
1. `POST /api/tasks/intake/submit` (or `/api/tasks`) → `TaskIntakeService` → task CREATED/QUEUED, project+workspace resolved (local path, `file://` or HTTP repo URL).
2. `TaskOrchestrator` picks the task → `TaskWorker.call()`: CLONING (workspace verify / `cloneOrFetchRepository`), ANALYZING, PLANNING.
3. IMPLEMENTING: `externalAiDelegator.isAvailableForTask` → `HarnessAiDelegator` → selected adapter (`opencode` when selected, else `evo` default when EVO enabled, else local agent fallback).
4. Adapter runs `opencode run <prompt> --agent/--model/--print-logs/--dir <workspace> [--auto]`, streams stdout, honors cancellation/timeout, collects modified files via `GitService`, enforces containment.
5. `TaskWorker` proceeds: build/test stages → `WAITING_FOR_REVIEW`; review/approval/commit remain human-controlled (`/api/reviews`, approval endpoints).

### EVO Developer Mode → OpenCode
1. Developer Mode resolves target (`ChatSession.targetPath` → project root → error).
2. Darwin invokes `coding_harness_execute` with the task prompt and working dir.
3. `CodingHarnessTool` → `ConnectedApplicationRegistry.findSelectedAdapter()` (explicit selection or OpenCode fallback) → adapter executes `opencode run ... --dir <target>` with timeout/cancellation/containment.
4. Result summary + modified files returned to the Darwin context; Darwin remains responsible for verification.

### EVO ↔ TRYST (TEIP 1.1)
- TRYST `TeipClient` → EVO `TeipApiController` (`/teip/v1/*`, port 28080, Bearer auth, idempotency, events with `nextSequence`); EVO `TrystMcpClient` → TRYST `/api/mcp` (Bearer + `X-Execution-Id`, scoped tools).

## 4. Proposed Bounded Work Packages

| WP | Work | Acceptance criteria |
|---|---|---|
| A | Finalization plan (this document) | Complete |
| B | TRYST→OpenCode real E2E | Real task through the real TRYST runtime (coordinator + HTTP API) with the real OpenCode CLI modifies the workspace; task reaches WAITING_FOR_REVIEW; changed files collected; review data available; statuses verified via API |
| C | EVO Dev Mode→OpenCode real E2E | Real task through `coding_harness_execute` path with real OpenCode modifies the confined target; result returned; no writes outside target |
| D1 | Fix G1 (restart re-registration) | Restart HTTP service; all three integration routes respond again; regression test |
| D2 | Fix G2 (model normalization) in both adapters | Bare model id executes successfully (provider prefix auto-added); provider-prefixed ids unchanged; unit tests both products |
| D3 | TEIP 1.1 live verification | Live EVO server: health/capabilities/202/200/idempotency/events/result/cancel/auth verified over real HTTP; TRYST client suite re-run; EVO TEIP suites re-run |
| D4 | Failure & recovery scenarios | Unavailable CLI, invalid model, timeout, cancellation, unexpected termination, TEIP-down, HTTP restart, app restart — deterministic observable outcomes, documented |
| D5 | Docs + closure | `PHASE-3-END-TO-END-TEST-REPORT.md`, `PHASE-3-FINAL-CLOSURE.md`, EVO ops doc corrected (G8), G5 noted |

## 5. Test and Verification Strategy

Toolchain: Maven 3.9.12 (wrapper dist `~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197`),
JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), Tycho 4.0.8 offline. System Maven 3.8.7/JDK 8
are incompatible (verified). `xvfb-run` is not installed; runs use `DISPLAY=:1` with
`useUIHarness=false` (documented).

- Unit/adapter tests: existing suites + new tests for G2 normalization and G1 restart.
- Real E2E TRYST: full `ApplicationLifecycleCoordinator` boot in a JUnit test (pattern:
  `FullPlatformLifecycleAndSecurityIntegrationTest`), real HTTP API, real OpenCode CLI
  (`/home/petr/.opencode/bin/opencode` v1.19.5), real Ollama models
  (`ollama/qwen3-coder-30b-32k:latest`, verified reachable), real repo copy with `.git`
  served via `file://` URL. No mocks on the harness path.
- Real E2E EVO: real `CodingHarnessTool`/adapter/registry against a real copied target
  directory with `.git` (the Developer Mode execution path).
- TEIP live: boot EVO `EvolutionServer` (existing test harness) and drive endpoints over
  real HTTP; TRYST client suite against mock + the live wire semantics already covered by
  EVO-side tests; cross-product evidence documented.
- Failure scenarios: scripted probes with exact commands and observed outputs.

Recording discipline: PASS / FAIL / BLOCKED / NOT RUN with actual command lines and
outputs; no source-inspection-only claims.

## 6. Sequence

1. WP-D1 + D2 code fixes (coordinator restart registration, model normalization, doc G8).
2. Rebuild TRYST infrastructure+application bundles; rebuild EVO controller.
3. Targeted regression tests (both products).
4. WP-B real E2E (TRYST), WP-C real E2E (EVO), WP-D3 TEIP live, WP-D4 failures.
5. Final builds + full targeted suites; write end-to-end report and closure.
