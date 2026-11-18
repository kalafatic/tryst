# PHASE 3 — RECOVERY AND CONTINUATION AUDIT

**Date:** 2026-11-16
**Trigger:** OpenCode session restarted after corruption of the OpenCode SQLite session database.
**Method:** Read-only audit of the TRYST and EVO repositories and their persisted documentation.
No source code, configuration, databases or existing documentation were modified.
**Git restriction:** Per TRYST AGENTS.md §12, no Git commands were executed. Repository state was
assessed exclusively from filesystem evidence (file contents, modification times, build
artifacts, test reports, runtime logs, runtime databases). Git commit history and branch state
were **not** inspected and remain unverified.

---

## 1. Executive Summary

The previous session's Phase 3 work **survived**. The working repositories
(`tryst-121126`, `evolution-121126`) contain the most recent state of the OpenCode-harness
integration (last filesystem activity: 2026-11-16 ~12:30), and the implementation is
substantially complete and verified:

- **TEIP 1.1 protocol work (WP-3.2–WP-3.5) is COMPLETE** on both sides. The canonical
  specification (`docs/protocol/TEIP-1.1.md`, EVO repo) exists, both implementations conform,
  and the EVO-side wire tests all pass (37/37 across five TEIP test classes, run 12:20).
- **The OpenCode coding-harness integration (WP-3.6–WP-3.9, "A-14/A-15") is COMPLETE** in
  code on both products: TRYST has the harness SPI, connected-application registry with SQLite
  persistence (migration V17), the OpenCode CLI adapter, the EVO-via-TEIP adapter and the
  `/api/integration/applications` management API; EVO has the mirror registry (JSON
  persistence), the OpenCode adapter, the `coding_harness_execute` tool and Developer Mode
  prompt wiring. EVO harness tests pass 9/9 (run 12:28, including a live OpenCode run);
  TRYST OpenCode adapter tests pass 7/7 (run 12:30).
- **Runtime evidence exists:** the live TRYST database has migrations V16/V17 applied
  (2026-11-16 10:47/11:11) and both `evo` and `opencode` connected-application rows persisted;
  TRYST started and shut down cleanly at 11:21 with the new subsystem wired.

Gaps that remain (Section 6): the connected-applications HTTP handler has **no authorization
enforcement**, the TRYST web UI has no connected-applications management page, some test
execution evidence was overwritten and must be re-run, live OpenCode end-to-end success is not
yet proven, and the Phase 1/2 decision documents themselves are not present in any repository
(only indirect references survive).

**Phase 3 can safely continue** with the security fix first (Section 9).

---

## 2. Recovered Phase 1/Phase 2 Decisions Relevant to Phase 3

**Important limitation:** The Phase 1/Phase 2 documents themselves (the "Phase 1/Phase 2
family audit" and the requirement catalog that produced the "A-14/A-15" identifiers) are
**not present in any repository** (search across `/home/petr/git/*` for `FHC`, "family
audit", `A-14`/`A-15`, `WP-3.1`). They presumably existed only in the corrupted OpenCode
session storage. The decisions below are reconstructed from surviving normative documents,
code comments and AGENTS.md instructions. Items marked [indirect] are inferred from
references rather than recovered from the original decision text.

### 2.1 Confirmed architectural decisions (direct evidence)

| # | Decision | Evidence |
|---|---|---|
| D1 | TEIP 1.1 is the single normative family contract; TEIP 1.0 copies are superseded pointers. Governance: change requires recorded justification + conformance wire-test update + product-owner sign-off. | `evolution-121126/docs/protocol/TEIP-1.1.md` (Status: NORMATIVE, Governance §0); `tryst-121126/docs/protocol/TEIP-1.0.md` (pointer only) |
| D2 | TEIP non-negotiable boundary rules: no direct object sharing; task lifecycle sovereignty (TRYST authoritative); human-in-the-loop commit gating; sandbox isolation. | `TEIP-1.1.md` §6.5 |
| D3 | TEIP 1.1 bugfixes are canonical: 202/200 create-execution, `mode`, `grantedToolScopes`, `caller.system`, object `contextReferences`, `nextSequence` cursor, top-level error envelope, mandatory Bearer auth with explicit dev-mode opt-out, persisted idempotency, MCP `initialize` required. | `TEIP-1.1.md` §1 change table |
| D4 | Canonical default EVO TEIP endpoint is `http://localhost:28080` (the 1.0 default of 39080 was a defect). | `TEIP-1.1.md` §9; `tryst-121126/plugins/.../domain/model/EvoConfiguration.java:29` |
| D5 | TRYST must stay independent of EVO: no EVO runtime dependency; TEIP integration confined to a dedicated bundle `eu.kalafatic.tryst.evo.integration`; core/domain bundles have zero compile-time references to `eu.kalafatic.evolution.*`. | `tryst-121126/AGENTS.md` §1, §5; `docs/integration/TEIP-TRYST-IMPLEMENTATION.md` §1.1 |
| D6 | Coding harnesses are execution capabilities behind an adapter boundary; the product's own engine remains the orchestrator. No hardcoding of a single agent into orchestration; keep provider specifics (process lifecycle, streaming, cancellation, result collection) inside the adapter. | `evolution-121126/AGENTS.md` §6; `tryst-121126/AGENTS.md` §13 |
| D7 | The connected-application registry is a **product-local** registry (TRYST: SQLite-backed, optional repository; EVO: JSON file under the EVO data dir via ResourceManager). It is explicitly **not** a cross-product shared service. | `tryst-121126/plugins/.../domain/harness/ConnectedApplicationRegistry.java` javadoc; `evolution-121126/.../controller/harness/ConnectedApplicationRegistry.java` javadoc |
| D8 | TRYST: the built-in local CodeAgent/CodeChangeExecutor remains the fallback and is not modelled as a connected application. Existing `ExternalAiDelegator` seam is reused for harness routing. | `CodingHarnessAdapter.java` javadoc (TRYST); `HarnessAiDelegator.java` javadoc; `ApplicationLifecycleCoordinator.java:465-468` |
| D9 | EVO: Developer Mode target resolution (`ChatSession.targetPath` → context project root → error) defines the harness execution boundary; harness must never write outside it. | `evolution-121126/.../harness/CodingHarnessAdapter.java` javadoc; `CodingHarnessTool.java` javadoc |
| D10 | OpenCode environment baseline: CLI v1.19.5 at `/home/petr/.opencode/bin/opencode`, default agent `build`, local Ollama models; the harness adapters use `opencode run <prompt> --agent/--model/--print-logs/--dir/--auto`. | `tryst-121126/OPENCODE_AUDIT_REPORT.md`; CLI `--help` verified 2026-11-16 (flags all valid in v1.19.5) |
| D11 | Scope restriction: Phase 3 touches only EVO and TRYST. DStudio, DCode (and TEIP) are out of scope unless a Phase 1/2 contract requires it. | Task instructions; DStudio/DCoder repos contain only skeleton files (no Phase 3 work) |

### 2.2 TEIP contract summary (Phase 3 conformance target)

TEIP 1.1 = HTTP/JSON protocol, base path `/teip/v1`, EVO exposes health/capabilities/
executions/events/result/cancel; TRYST exposes a scoped MCP JSON-RPC tool plane at `/api/mcp`
with mandatory `Authorization` + `X-Execution-Id`, `initialize` handshake (2024-11-05) and
scope derivation from the persisted execution record (`evo_executions.granted_scopes`, TRYST
migration V16). See `TEIP-1.1.md` §1–§9 for the full contract.

### 2.3 FHC contract

**No evidence found.** The term "FHC" does not occur in any repository or document under
`/home/petr/git` (only unrelated matches inside a third-party `package-lock.json`). If "FHC"
denotes a "family harness contract", its text was not persisted. It must be recovered from the
user or treated as an unresolved question (Section 7).

### 2.4 Phase 3 scope (reconstructed)

From surviving code comments and doc status notes, Phase 3 was organized into work packages:

| WP | Subject | Where referenced |
|---|---|---|
| WP-3.1 | (unknown — never referenced anywhere) | — |
| WP-3.2 | TRYST TEIP 1.1 client conformance (incl. 28080 endpoint fix) | `docs/integration/TEIP-*` (TRYST) |
| WP-3.3 | EVO TEIP 1.1 server conformance | `docs/integration/TEIP-EVO-IMPLEMENTATION.md` |
| WP-3.4 | TRYST MCP tool-plane 1.1 hardening + tests (V16) | `docs/integration/TEIP-TRYST-IMPLEMENTATION.md`, `TEIP-TESTING.md` |
| WP-3.5 | Cross-repository conformance tests | both `TEIP-TESTING.md` |
| WP-3.6 | TRYST SandboxProvider SPI wiring | `ApplicationLifecycleCoordinator.java:1280` |
| WP-3.7 | TRYST OpenCode coding-harness adapter | `OpenCodeCodingHarnessAdapter.java` (TRYST) |
| WP-3.8 | EVO connected-application registry | `ConnectedApplicationRegistryTest.java` (EVO) |
| WP-3.9 | EVO OpenCode adapter + `coding_harness_execute` tool + wiring | EVO `harness/`, `tools/`, `DeveloperEngine.java` |

Requirement identifiers "A-14" (connected applications) and "A-15" (coding-harness adapter
SPI) originate from the missing Phase 1/2 audit document [indirect].

---

## 3. Repository and Branch Inventory

Branch information is **unverified** (Git restriction). Filesystem evidence:

| Repository | Role | Latest modification | Phase 3 content |
|---|---|---|---|
| `/home/petr/git/tryst-121126` | TRYST working repo (audit target) | 2026-11-16 12:30 (test report); bundles built 12:28; docs 12:02–12:22 | Full WP-3.2/3.4/3.6/3.7 + TEIP 1.1 docs |
| `/home/petr/git/evolution-121126` | EVO working repo (audit target) | 2026-11-16 12:28 (harness test report); controller jar 12:23; docs 11:28–12:02 | Full WP-3.3/3.8/3.9 + TEIP 1.1 canonical spec |
| `/home/petr/git/tryst-161126` | TRYST snapshot, 2026-11-16 07:30 | pre-final-session | TEIP 1.0 docs only; **no harness packages** |
| `/home/petr/git/evolution-161126` | EVO snapshot, 2026-11-16 07:31 | pre-final-session | TEIP 1.0 docs only; no `TEIP-1.1.md`; **no harness package** |
| `/home/petr/git/tryst-031126`, `tryst-11126` | older TRYST snapshots (Nov 3/12) | — | pre-TEIP-1.1 |
| `/home/petr/git/evolution-*` (other) | older EVO snapshots | — | out of scope |
| `/home/petr/git/DCoder-121126`, `DStudio-121126` | out of scope | Nov 15 | skeleton only (`.project`, `README.md`) — no Phase 3 work, as required |

Conclusion: the working repos are the newest copies; the `-161126` siblings are safety
snapshots made at 07:30–07:31 before the final (corrupted) session. The final session's
deliverables — TEIP 1.1 spec, TEIP 1.1 conformance, the entire harness subsystem — exist only
in the working repos and are intact.

---

## 4. Existing Implementation Analysis

### 4.1 TRYST (all paths under `/home/petr/git/tryst-121126`)

| Component | Location | Notes |
|---|---|---|
| Harness SPI `CodingHarnessAdapter` (+ `ConnectionTestResult`, `HarnessExecutionResult`) | `plugins/eu.kalafatic.tryst.domain/src/eu/kalafatic/tryst/domain/harness/CodingHarnessAdapter.java` | Matches D6/D8; includes containment/authority javadoc |
| Registry API + impl + descriptor + repository contract | `domain/harness/{ConnectedApplicationRegistry,DefaultConnectedApplicationRegistry,ConnectedApplicationDescriptor,ConnectedApplicationRepository}.java` | Product-local (D7); hydration from persistence; enable/availability gating |
| SQLite persistence (migration V17: `connected_applications`, `connected_application_state`) | `infrastructure/persistence/SchemaMigrationService.java:783-809`; `infrastructure/persistence/repository/SqliteConnectedApplicationRepository.java` | Applied in live DB (see §4.3) |
| OpenCode CLI adapter (harness) | `infrastructure/cli/OpenCodeCodingHarnessAdapter.java` | `opencode run ... --dir <workspace>` with timeout/cancellation/containment check; WP-3.7 |
| EVO-via-TEIP adapter | `plugins/eu.kalafatic.tryst.evo.integration/src/.../service/EvoCodingHarnessAdapter.java` | Bridges existing `EvoAiDelegator` into the harness SPI (A-14/A-15) |
| Delegator bridge | `domain/harness/HarnessAiDelegator.java` | Implements existing `ExternalAiDelegator` seam; 30 min timeout |
| Lifecycle wiring | `application/lifecycle/ApplicationLifecycleCoordinator.java:339-341,447-469,596-604,1265-1284` | Registers `evo` + `opencode` adapters; defaults selection to `evo` when EVO enabled (historical behavior preserved); WP-3.6 SandboxProvider OSGi lookup with `DefaultProcessSandboxProvider` fallback |
| Management HTTP API `/api/integration/applications` | `application/http/ConnectedApplicationsHttpHandler.java` | list/select/test/config/enable — **no authorization check** (defect, §6) |
| TEIP 1.1 client | `evo.integration/client/TeipClient.java` | 202/200, `Idempotency-Key`, `nextSequence`+alias, canonical error envelope, 28080 default |
| MCP tool plane | `evo.integration/service/EvoHttpHandlers.java` (McpHttpHandler), `evo.integration/mcp/McpServer.java` | Bearer + `X-Execution-Id` enforced; scopes from `granted_scopes` |
| Legacy `OpenCodeCliAdapter` (SandboxProvider) | `infrastructure/cli/OpenCodeCliAdapter.java` | **Orphaned** — no caller remains after WP-3.6 rewiring (dead code) |

### 4.2 EVO (all paths under `/home/petr/git/evolution-121126`)

| Component | Location | Notes |
|---|---|---|
| Harness SPI | `eu.kalafatic.evolution.controller/src/.../harness/CodingHarnessAdapter.java` | D6/D9; `File`-based target |
| Registry (singleton, JSON persistence via ResourceManager) | `harness/ConnectedApplicationRegistry.java`, `harness/ConnectedApplicationDescriptor.java` | Persists to `<EVO_ROOT>/data/connected-applications.json`; in-memory fallback; OpenCode fallback selection |
| OpenCode adapter | `harness/OpenCodeCodingHarnessAdapter.java` | WP-3.9; containment check; `timeoutSeconds` config |
| Tool `coding_harness_execute` | `tools/CodingHarnessTool.java` | Delegates to selected adapter inside resolved target |
| Tool registration + adapter registration | `tools/ToolFactory.java:44-53` | Static init registers tool + OpenCode adapter |
| Developer Mode prompt | `orchestration/selfdev/DeveloperEngine.java:293-297` | Describes harness as execution capability; requires verification after use |
| TEIP 1.1 server | `controller/teip/{controller,dto,mcp,service,store}/*` | 202/200, `VERSION_MISMATCH`, mandatory auth + `evo.teip.devMode` loopback opt-out, persisted idempotency/events, `nextSequence` |
| TEIP dashboard | `orchestration/teip.html`, `view/.../TeipProtocolPage.java` + factory | UI exists (built 08:46) |

### 4.3 Runtime / persistence evidence (live artifacts, read-only)

- `~/.tryst/tryst.db`: migrations 1–17 applied; V16 `2026-11-16T10:47:36`, V17
  `2026-11-16T11:11:45`. Tables `connected_applications` (rows: `evo` "EVO Darwin Engine",
  `opencode` "OpenCode CLI", both enabled, status UNKNOWN) and `connected_application_state`
  (empty — no persisted explicit selection) exist.
- `~/.tryst/logs/tryst.log`: 2026-11-16 11:21 full startup with V17 application, clean
  "Application terminated cleanly" shutdown.
- `/home/petr/git/evolution-121126/data/evolution.db`: contains only `sessions`, `users` —
  the TEIP store tables (`teip_executions`, `teip_idempotency`, `teip_events`) are created by
  `TeipPersistenceStore` at EVO server start and are not present in the idle database (needs a
  live EVO start to verify).

### 4.4 Conformance assessment vs Phase 1/2 architecture

- TRYST core/domain bundles have **zero** compile-time references to
  `eu.kalafatic.evolution.*` (grep: only the dedicated `eu.kalafatic.tryst.evo.integration`
  bundle and its consumers reference TEIP classes) — D5 holds.
- Harness routing uses the existing `ExternalAiDelegator`/`TaskWorker` seam; no second task
  manager, no second event bus, no duplicate registry across products — D6/D8 hold.
- TEIP 1.1 wire behavior verified by code inspection (TRYST client, EVO server) and by the
  passing EVO-side conformance tests.
- No Phase 4/5 work was started in the repos.

---

## 5. Verified Completion Matrix

Legend: COMPLETE / PARTIAL / NOT IMPLEMENTED / BLOCKED / NEEDS VERIFICATION.

| Area | Expected result | Existing implementation | Status | Evidence |
|---|---|---|---|---|
| TEIP 1.1 canonical spec (EVO) | Single normative spec | Present, 471 lines, NORMATIVE | COMPLETE | `evolution-121126/docs/protocol/TEIP-1.1.md` |
| TEIP 1.0 pointer retirement (both repos) | 1.0 copies marked superseded | Done in both repos | COMPLETE | `docs/protocol/TEIP-1.0.md` (both) |
| WP-3.2 TRYST client conformance | 202/200, canonical fields, `nextSequence`, error envelope, 28080 default | Implemented in `TeipClient`, `EvoConfiguration` | COMPLETE | `TeipClient.java:78,82,182,395-489`; `EvoConfiguration.java:29` |
| WP-3.3 EVO server conformance | 202/200, aliases, VERSION_MISMATCH, auth, persisted idempotency/events, nextSequence | Implemented in `teip/*` | COMPLETE | `TeipApiController.java:45-158`; `TeipEventStore.java:120`; `TeipPersistenceStore.java:51-63` |
| WP-3.4 TRYST MCP tool plane 1.1 | Bearer + X-Execution-Id enforced, initialize handshake, scopes from V16 | Implemented | COMPLETE | `EvoHttpHandlers.java:324-348`; migration V16 in `SchemaMigrationService.java` |
| WP-3.5 cross-repo conformance tests | EVO+TRYST wire suites green | EVO: 37/37 pass. TRYST: tests exist+compiled; latest run evidence overwritten | COMPLETE (EVO) / NEEDS VERIFICATION (TRYST re-run) | surefire: `teip.*Test.txt` (12:20, all 0 failures); TRYST `TeipClientTest` etc. compiled 12:29, reports wiped by 12:30 targeted run |
| WP-3.6 TRYST SandboxProvider SPI | OSGi lookup with built-in fallback | Implemented (`lookupSandboxProvider` → `DefaultProcessSandboxProvider`) | COMPLETE | `ApplicationLifecycleCoordinator.java:1265-1284` |
| WP-3.7 TRYST OpenCode harness adapter | Adapter + tests + containment | Implemented; 7/7 tests pass | COMPLETE (unit) / NEEDS VERIFICATION (live task success) | `OpenCodeCodingHarnessAdapter.java`; surefire 12:30: 7 run, 0 fail; live smoke exited in 1.2 s (no real task evidence) |
| WP-3.8 EVO registry | JSON-persisted registry, selection/enable gating, tests | Implemented; tests pass | COMPLETE | `harness/ConnectedApplicationRegistry.java`; surefire 12:28: 9 run, 0 fail |
| WP-3.9 EVO OpenCode adapter + tool | Adapter, `coding_harness_execute`, ToolFactory, Developer prompt | Implemented; tests pass | COMPLETE (unit) / NEEDS VERIFICATION (live task success) | `harness/OpenCodeCodingHarnessAdapter.java`; `tools/CodingHarnessTool.java`; `ToolFactory.java:46`; surefire 12:28 — live smoke ran full 300 s (timeout path) |
| A-14 TRYST registry persistence | SQLite V17 | Implemented and applied in live DB | COMPLETE | `SchemaMigrationService.java:783`; `~/.tryst/tryst.db` rows |
| A-14 TRYST management API | `/api/integration/applications` | Implemented (list/select/test/config/enable) | PARTIAL — **no authorization enforcement** | `ConnectedApplicationsHttpHandler.java` (no PermissionManager; contrast `EvoIntegrationHandler`) |
| A-14 TRYST web UI (AI Integration Center applications card) | UI for list/select/test/config | Absent from embedded web UI | NOT IMPLEMENTED | `TrystHttpServer.java` (7935 lines) contains EVO integration UI (`/api/integration/evo/*`) but zero references to `/api/integration/applications`; `TEIP-OPERATIONS.md` §2.4/§3 overstate this |
| A-14 EVO management surface | (unknown plan) | In-process registry only; no UI/HTTP surface | NOT IMPLEMENTED (plan unknown) | no controllers reference the registry |
| TRYST registry unit tests | `ConnectedApplicationRegistryTest` incl. persistence round trip | Written + compiled | NEEDS VERIFICATION | compiled 12:29; no surefire report in latest run |
| TRYST EvoCodingHarnessAdapter tests | Dedicated tests | None found | NOT IMPLEMENTED | tests dir contains no class for it |
| EVO TEIP tests | 5 classes | All pass | COMPLETE | `TeipApiEndpointTest` 7, `TeipDtoSerializationTest` 10, `TeipExecutionManagerTest` 6, `TeipProtocolUiIntegrationTest` 7, `TrystMcpClientTest` 7 — 0 failures (12:20) |
| EVO harness tests | registry + adapter + tool | 9/9 pass | COMPLETE | surefire 12:28 (includes 300.7 s live smoke) |
| Phase 3 documentation (TRYST) | impl/testing/operations docs for 1.1 | Updated 12:02–12:22 | COMPLETE | `docs/integration/TEIP-{TRYST-IMPLEMENTATION,TESTING,OPERATIONS}.md` |
| Phase 3 documentation (EVO) | impl/testing/operations docs for 1.1 | impl+testing updated; operations doc still 1.0-titled with stale MCP URL example | PARTIAL | `TEIP-EVO-IMPLEMENTATION.md`, `TEIP-TESTING.md` updated; `TEIP-OPERATIONS.md` (1.0, `localhost:3000/mcp`) |
| Phase 3 plan/status document (WP list, A-items) | Persisted plan for traceability | Never created in repos | NOT IMPLEMENTED | only code/doc comments reference WPs and A-14/A-15 |
| TRYST full test suite post-Phase-3 | All green | Unknown — latest run was targeted (`-Dtest=OpenCodeCodingHarnessAdapterTest`) | NEEDS VERIFICATION | surefire dir contains only that report (12:30) |
| EVO full test suite | All green | 226 run, 23 failures, 18 errors in accumulated reports — all failures from unrelated subsystems at 08:47–08:48 run; TEIP/harness reports clean | NEEDS VERIFICATION (full re-run) | `controller.tests/target/surefire-reports/*` timestamps |
| EVO TEIP persistence tables at runtime | teip_* tables in evolution.db | Absent while server idle (lazy creation) | NEEDS VERIFICATION | `data/evolution.db` `.tables` = sessions, users |
| Live OpenCode end-to-end (both products) | Real task creates file inside sandbox/target | TRYST smoke: 1.2 s (no task run). EVO smoke: 300.7 s (timeout path). Neither demonstrates verified file creation | NEEDS VERIFICATION | test XMLs (12:30/12:28) |
| OpenCode CLI flag compatibility | v1.19.5 flags used by adapters | Verified all flags exist | COMPLETE | `opencode run --help` output; `OPENCODE_AUDIT_REPORT.md` |

---

## 6. Outstanding Tasks

Ordered by priority:

1. **SECURITY — add authorization to `ConnectedApplicationsHttpHandler`** (TRYST).
   The handler is registered without `PermissionManager` (contrast
   `ApplicationLifecycleCoordinator.java:597` vs `:602`) and performs no Bearer/role check,
   while the operations guide documents `Authorization: Bearer <TRYST_ADMIN_TOKEN>` for these
   endpoints. Reuse the existing permission/auth mechanism; do not invent a new one.
2. **Re-run TRYST tests** to refresh execution evidence: at minimum
   `ConnectedApplicationRegistryTest` plus the TEIP suite
   (`TeipClientTest,TeipProtocolContractTest,McpServerSecurityTest,EvoPersistenceAndRecoveryTest,TeipLifecycleIntegrationTest,SchemaMigrationTest`);
   ideally the full TRYST test module (last full-run result is unknown).
3. **Verify live OpenCode end-to-end** on both products: confirm a real `opencode run` task
   actually creates the marker file inside the workspace/target (both smoke tests currently
   pass assertions without proving successful task completion; the EVO run hit the 5-minute
   timeout). Investigate model/provider configuration (`provider/model` format expected by
   OpenCode vs `qwen3-coder-30b-32k:latest` in docs).
4. **TRYST web UI for connected applications**: either implement the "AI Integration Center"
   applications card (list/select/test/config) in the embedded web UI or correct
   `TEIP-OPERATIONS.md` §2.4/§3 to state backend-only.
5. **Add tests for `EvoCodingHarnessAdapter`** (TRYST) — currently untested.
6. **Documentation cleanup**: update EVO `TEIP-OPERATIONS.md` to 1.1 (fix stale MCP URL
   example `localhost:3000/mcp` → canonical `/api/mcp` on TRYST 38080); persist a Phase 3
   plan/status document (WP definitions + evidence) so future recovery does not depend on
   session storage.
7. **Retire orphaned `OpenCodeCliAdapter`** (TRYST infrastructure) — superseded by
   `OpenCodeCodingHarnessAdapter`; no callers remain (avoid two OpenCode execution paths).
8. **EVO full-suite re-run** to confirm the pre-existing failures (08:47 run: 23 failures/18
   errors across mediation/design/self-dev/forge tests) are unrelated to Phase 3 and to
   establish a clean regression baseline.
9. **Resolve open questions**: recover the Phase 1/2 audit documents and the meaning of
   "FHC" (blocker for verifying the complete A-14/A-15 requirement set and WP-3.1 scope).
10. **Minor**: reconcile timeouts (`EvoAiDelegator` 120 s wait vs `HarnessAiDelegator`
    30 min); confirm EVO `teip_*` tables after a real EVO server start.

---

## 7. Risks and Blockers

1. **Missing Phase 1/2 decision documents.** The family-audit requirement catalog
   (A-14/A-15) and the WP-3.x plan text are absent from all repositories; they likely exist
   only in the corrupted session database. Re-deriving them from code comments risks
   misinterpreting original intent. **Mitigation:** treat surviving normative docs
   (TEIP 1.1, both AGENTS.md) as authoritative; ask the user to re-supply the Phase 1/2
   documents if they exist elsewhere.
2. **"FHC" undefined.** No repository evidence. Cannot verify any FHC contract.
3. **Unauthenticated connected-applications API** (TRYST) — exposed on the configured bind
   address; must be fixed before further Phase 3 completion claims.
4. **Live OpenCode execution not proven.** EVO smoke exercised the timeout path (300.7 s);
   TRYST smoke completed in 1.2 s without running a task. Actual harness task success with the
   local model is unverified.
5. **TRYST test evidence overwritten.** The 12:30 targeted run wiped prior surefire reports;
   the full TRYST suite greenness after the last changes is unknown.
6. **EVO suite has unrelated pre-existing failures** (08:47 run) — weakens regression signal.
7. **Git state unknown** (restriction). Cannot confirm branch cleanliness, uncommitted
   change sets, or whether snapshots match commits. Review of changes relies on filesystem
   diffs only.
8. **Docs overstate UI**: TEIP-OPERATIONS.md describes web pages that have no frontend code.

---

## 8. Recommended Implementation Sequence

1. Fix the authorization gap in `ConnectedApplicationsHttpHandler` (reuse
   `PermissionManager` + admin-token check as in `EvoIntegrationHandler`).
2. Run TRYST targeted tests (registry + TEIP suite) and record results.
3. Run a controlled live OpenCode end-to-end verification in each product (fix
   provider/model configuration if needed).
4. Implement (or explicitly descope and re-document) the TRYST connected-applications web UI.
5. Add `EvoCodingHarnessAdapter` tests; retire `OpenCodeCliAdapter`.
6. Update EVO operations doc; persist the Phase 3 plan/status document in the repos.
7. Full TRYST and EVO suite runs for final regression baselines.

---

## 9. Can Phase 3 Continue Safely?

**Yes — with conditions.** The surviving implementation matches the recovered Phase 1/2
architecture (D1–D11), compiles (bundles and jars built 12:23–12:30), has passing targeted
tests on both products, and live runtime/persistence evidence in TRYST. No redesign is
required. Continuation is safe provided that: (a) the unauthenticated connected-applications
API is fixed first (risk #3), (b) verification gaps are closed by re-running tests rather than
assumed green, and (c) open questions (FHC, Phase 1/2 originals) are either resolved with the
user or explicitly recorded as unresolved before any contract-sensitive change.

## 10. Exact Recommended Next Action

**Add authorization enforcement to
`plugins/eu.kalafatic.tryst.application/src/eu/kalafatic/tryst/application/http/ConnectedApplicationsHttpHandler.java`**
— construct it with the existing `PermissionManager` (registration at
`ApplicationLifecycleCoordinator.java:602-604`) and reject unauthenticated calls exactly as
`EvoHttpHandlers.EvoIntegrationHandler` does — then run
`xvfb-run mvn -o integration-test -pl tests/eu.kalafatic.tryst.tests -Dtest='ConnectedApplicationRegistryTest,OpenCodeCodingHarnessAdapterTest,TeipClientTest,TeipProtocolContractTest,McpServerSecurityTest'`
and record the results.
