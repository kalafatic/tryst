# PHASE 3 — FINAL CLOSURE

**Date:** 2026-11-16
**Products:** TRYST (`/home/petr/git/tryst-121126`), EVO (`/home/petr/git/evolution-121126`)
**Predecessors:** `PHASE-3-RECOVERY-AUDIT.md`, `PHASE-3-WP-3.10-SECURITY-REPORT.md`
(accepted), `PHASE-3-FINALIZATION-PLAN.md`, `PHASE-3-END-TO-END-TEST-REPORT.md`.

---

## 1. Executive Summary

Phase 3 is **functionally complete and accepted at the implementation level**:

- **TRYST executes real coding tasks through OpenCode** — proven end to end through the
  real application (lifecycle, SQLite, HTTP API, repository sandbox, task orchestration):
  the task went CLONING → ANALYZING → PLANNING → IMPLEMENTING (real `opencode run`) →
  BUILDING → TESTING → **WAITING_FOR_REVIEW**, with the OpenCode-created file and README
  modification verified inside the TRYST workspace and a persisted review available
  through the review API.
- **EVO Developer Mode executes real coding tasks through OpenCode** — proven through the
  real `coding_harness_execute` tool against a resolved target; the file was created
  inside the confined target and the result returned to the tool caller.
- **TEIP 1.1 works in the actual runtime** — the real EVO server answered the canonical
  contract over live HTTP (202/200 idempotency, sequenced events with `nextSequence`,
  idempotent cancel, canonical error envelope) while the Darwin engine genuinely executed
  the delegated task; all 37 EVO wire tests and the TRYST client/contract suites pass.
- **Reliability fixes were delivered** (all with real execution evidence): OpenCode
  process invocation was made deterministic (stdin detach + headless env + per-execution
  state dirs + model provider normalization + containment constraints); the task-intake
  workspace race was closed; the HTTP-service restart re-registration defect was fixed.
- **305/305 TRYST tests pass** (full suite minus three pre-existing broken classes,
  documented), 10/10 EVO harness tests, 37/37 EVO TEIP tests, and all relevant builds
  succeed.

## 2. Final Verified Architecture

Unchanged from the approved design (TEIP 1.1, EVO AGENTS.md §6, TRYST AGENTS.md §1/§13):

- **TRYST** owns task lifecycle, persistence, workspace/sandbox isolation, review,
  approval and commit. The harness SPI (`CodingHarnessAdapter`), the product-local
  `ConnectedApplicationRegistry` (SQLite, migration V17), `HarnessAiDelegator` over the
  existing `ExternalAiDelegator` seam, and the `OpenCodeCodingHarnessAdapter` form the
  integration boundary. The EVO-via-TEIP adapter remains available as a second harness.
  The local CodeAgent remains the built-in fallback.
- **EVO** owns its lifecycle and orchestration; Darwin remains the orchestrator. The
  harness SPI, the JSON-persisted registry and `coding_harness_execute` keep OpenCode an
  execution capability behind the adapter boundary.
- **TEIP 1.1** remains the canonical inter-product contract (EVO `/teip/v1/*` server;
  TRYST `TeipClient` + scoped `/api/mcp` tool plane). No changes to the canonical
  contract were made.
- No new event buses, session managers, registries, frameworks or dependencies were
  introduced. TRYST still has zero compile-time references to EVO implementation classes.

## 3. Implemented Components in EVO

| Component | Status | Evidence |
|---|---|---|
| `CodingHarnessAdapter` SPI | COMPLETE (pre-existing) | `controller/.../harness/CodingHarnessAdapter.java` |
| `ConnectedApplicationRegistry` (+ descriptor, JSON persistence via ResourceManager) | COMPLETE (pre-existing) | `controller/.../harness/ConnectedApplicationRegistry.java` |
| `OpenCodeCodingHarnessAdapter` | COMPLETE + finalization hardening: provider-prefix model normalization, hermetic per-run environment (CI/telemetry/NO_COLOR, product-owned XDG dirs, OPENCODE* vars removed), stdin detach (`/dev/null`), prompt-level containment constraints | adapter source; shell probes; harness suite |
| `coding_harness_execute` tool + ToolFactory registration | COMPLETE (pre-existing) | `tools/CodingHarnessTool.java`, `ToolFactory.java` |
| Developer Mode prompt wiring | COMPLETE (pre-existing) | `DeveloperEngine.java` |
| TEIP 1.1 server (controller/DTOs/store/manager/MCP client) | COMPLETE (pre-existing; verified live) | `teip/*`; live HTTP session; 37/37 tests |
| TEIP dashboard + workbench page | COMPLETE (pre-existing) | `teip.html`, `TeipProtocolPage.java` |
| New tests | `testRealCodingHarnessToolExecutionInTarget` (real tool path), `TeipLiveServerHoldTest` (live-server support) | surefire 10/10 |

## 4. Implemented Components in TRYST

| Component | Status | Evidence |
|---|---|---|
| Harness SPI + registry + SQLite persistence (V17) + `HarnessAiDelegator` + `EvoCodingHarnessAdapter` | COMPLETE (pre-existing) | `domain/harness/*`, `SchemaMigrationService` V17 |
| `OpenCodeCodingHarnessAdapter` | COMPLETE + finalization hardening (same set as EVO, state under `~/.tryst/opencode-harness/runs/*`) | adapter source; E2E evidence |
| `/api/integration/applications` (WP-3.10 authorized) | COMPLETE | `ConnectedApplicationsHttpHandler` + security tests 15/15 |
| TEIP 1.1 client + MCP tool plane | COMPLETE | `TeipClient`, `EvoHttpHandlers`; contract tests |
| Task intake race fix | NEW: workspace allocated before enqueue (new `TaskOrchestrator.submitTask(Task,...)` overload used by `DefaultTaskIntakeService`) | code; intake + async E2E tests pass |
| HTTP restart re-registration | NEW: `registerIntegrationHttpHandlers(...)` invoked from both initial start and `initializeHttpServerInstance()` (restart path) | restart E2E test |
| New tests | `OpenCodeEndToEndWorkflowTest` (3), `HarnessDelegationDiagnosticTest` (1) | surefire |

## 5. TEIP 1.1 Integration Status — **VERIFIED**

Contract compatibility, request/response semantics, idempotency (202 new / 200 duplicate),
sequence handling (`nextSequence`), error propagation (canonical envelope, 404), timeout
and cancellation behavior, and remote-unavailable behavior were all verified live or by
the passing wire suites (see the end-to-end report, Scenario 3). No contract changes were
made. Limitation: a simultaneous two-process run was not performed (documented).

## 6. OpenCode Harness Integration Status — **VERIFIED WORKING**

Both products execute real OpenCode tasks and collect real results (file changes inside
the confined target, structured summaries, honest failures). The integration is immune to
the host's corrupted interactive OpenCode data directory and to interactive/network
stalls in the CLI (see the root-cause chain documented in §9).

## 7. TRYST End-to-End Execution Evidence

Real task through the real application via HTTP API (production auth mode), real
repository clone, real OpenCode execution: **WAITING_FOR_REVIEW** with persisted review,
verified workspace changes (`e2e-marker.txt`, `README.md`), 51.4 s. Full details and
commands: `PHASE-3-END-TO-END-TEST-REPORT.md`, Scenario 1.

## 8. EVO Developer Mode End-to-End Execution Evidence

Real `coding_harness_execute` invocation against a resolved target with the real OpenCode
CLI: tool returned the result, file verified inside the target (23.4 s). The adapter
direct-run test (22.4 s) additionally proves target-resolution + execution.
Details: end-to-end report, Scenario 2.

## 9. Reliability and Failure-Recovery Verification

During finalization a real production-blocking defect chain in the OpenCode process
invocation was diagnosed and fixed (all verified with probes and real runs):

1. **stdin dependency**: `opencode run` blocks indefinitely when it inherits an open
   stdin pipe from a product runtime (observed: process alive in `ep_poll`, zero network,
   no session creation; reproduced deterministically with a Java spawn probe). Fix:
   `redirectInput(/dev/null)` in both adapters.
2. **Headless env**: without `CI=1`/telemetry-off the CLI performs an external network
   call after startup that can hang on a stalled network path (observed idle HTTPS
   connection to a Cloudflare address). Fix: hermetic env in both adapters.
3. **Poisoned state reuse**: a hung/crashed run leaves stale OpenCode state that blocks
   subsequent runs at session creation. Fix: per-execution `XDG_DATA_HOME/STATE/CACHE`
   dirs owned by the product (also isolates harness state from the user's corrupted
   interactive OpenCode data — "database disk image is malformed" reproduced).
4. **Model id format**: bare model ids fail (`Model not found: <id>/`, exit 1). Fix:
   provider-prefix normalization (`ollama/` for bare ids) in both adapters.
5. **Containment**: the LLM once wrote an absolute path outside the target. Fix:
   mandatory prompt-level containment constraints + the existing modified-file
   containment check (git targets).
6. **Intake race**: tasks could be claimed before their workspace was allocated. Fix:
   allocate-then-enqueue.
7. **HTTP restart**: integration routes were lost after `restartHttpService()`. Fix:
   unified re-registration.

Failure scenarios (unavailable CLI, invalid model, timeout, cancellation, interruption,
TEIP-down, HTTP restart, app restart) are deterministic and covered by tests — see the
end-to-end report, Scenario 4.

## 10. Build and Test Results

| Item | Result |
|---|---|
| TRYST bundle builds (domain, infrastructure, application) | SUCCESS |
| TRYST full reactor build | all product modules SUCCESS; reactor `install` stops only at the tests module due to 3 pre-existing broken test classes (documented) |
| TRYST full test suite (minus 3 documented exclusions) | **305/305 PASS** |
| TRYST targeted Phase 3 suites | 4/4 + 54/54 + 10/10 PASS |
| EVO controller build | SUCCESS |
| EVO harness suite | **10/10 PASS** (incl. 2 real OpenCode runs) |
| EVO TEIP suite | **37/37 PASS** |
| EVO full reactor build | all 28 modules SUCCESS through `controller.tests`; aggregate fails ONLY at `eu.kalafatic.evolution.repository` win32 product materialization in offline mode (missing Windows p2 fragments — pre-existing environment limitation); linux product materialized |

## 11. Remaining Defects and Limitations

1. Pre-existing TRYST Studio subsystem defects (missing `studio_alternatives`/
   `studio_plan_steps` migrations; review-comment FK failure) — out of Phase 3 scope.
2. Pre-existing full-suite order sensitivity of
   `ServerLifecycleAndUiAccessTest`/`PortManagementSubsystemTest` (pass in isolation).
3. `EvoIntegrationHandler` still performs no authorization (deferred security item).
4. EVO `TeipPersistenceStore` uses a CWD-relative `data/evolution.db`.
5. The user's interactive OpenCode data directory is corrupted (harness is immune; the
   interactive environment itself is out of scope).
6. Live LLM behavior is probabilistic; containment is enforced by prompt constraints and
   (for git targets) modified-file verification.
7. TRYST web UI has no connected-applications management page (API is the contract).
8. Orphaned legacy `OpenCodeCliAdapter` remains in the codebase (no callers).
9. Full EVO suite has pre-existing unrelated failures (mediation/design/self-dev/forge
   classes, documented in the recovery audit).

## 12. Deferred Security Concerns

- `EvoIntegrationHandler` (`/api/integration/evo/*`) does not enforce authentication or
  authorization of its own (it receives `PermissionManager` but never checks).
  Operational implication: on LAN deployments in production mode, EVO integration
  configuration and executions are reachable without a token. WP-3.10 fixed the
  connected-applications surface; the same treatment is recommended for the EVO
  integration handler in a follow-up.
- Emergency development mode remains default-on (Phase 1/2 platform decision): in
  unconfigured deployments all endpoints, including integration management, are open.
  Production deployments must set `tryst.dev.mode=false`.

## 13. Explicit Phase 3 Acceptance Decision

**ACCEPTED** — with recorded limitations:

- TRYST executes a real coding task through OpenCode: YES (verified end to end).
- EVO Developer Mode executes a real coding task through OpenCode: YES (verified via the
  real tool path and real adapter runs).
- Both workflows return usable results to their products: YES (WAITING_FOR_REVIEW +
  review for TRYST; tool result + modified files for EVO).
- TEIP 1.1 works in the actual runtime: YES (live server session + wire suites).
- Existing orchestration and lifecycle mechanisms remain authoritative: YES (Darwin and
  TaskWorker unchanged in role; harnesses are capabilities behind adapter boundaries).
- Process lifecycle and failure handling are reliable: YES (deterministic, tested).
- Relevant builds and tests pass: YES (documented above).
- Remaining limitations explicitly documented: YES (§11, §12, end-to-end report).
- Final architecture documentation reflects the actual implementation: YES
  (`PHASE-3-FINALIZATION-PLAN.md`, this document, `docs/integration/*`).

## 14. Readiness Assessment for Phase 4

**READY, with conditions.** The OpenCode harness integration is functionally proven in
both products, TEIP 1.1 is verified live, and reliability defects that would block
operational use were fixed with evidence. Before Phase 4 begins, the Product Owner should
confirm the disposition of: (a) the three pre-existing broken TRYST test classes
(Studio schema defect + suite-order sensitivity), (b) the deferred
`EvoIntegrationHandler` authorization gap, and (c) the user's corrupted interactive
OpenCode data directory. None of these block the Phase 3 acceptance; they are tracked
work items for later phases.

---

*Phase 3 closure prepared 2026-11-16. No Phase 4/5 work was started. Awaiting Product
Owner review.*
