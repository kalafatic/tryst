# PHASE 3 — END-TO-END TEST REPORT

**Date:** 2026-11-16
**Products:** TRYST (`/home/petr/git/tryst-121126`), EVO (`/home/petr/git/evolution-121126`)
**Scope:** real execution evidence for the Phase 3 acceptance scenarios (TRYST→OpenCode,
EVO Developer Mode→OpenCode, TEIP 1.1 live, failure & recovery). No mocks on the harness
paths of the acceptance scenarios.

Toolchain: Maven 3.9.12 (`~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn`),
JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), Tycho 4.0.8 (TRYST) / 4.0.5 (EVO), offline
mode. System Maven 3.8.7 / JDK 8 are incompatible (verified). `xvfb-run` is not installed
on this host; all test runs use `useUIHarness=false` and the available display
(`DISPLAY=:1`).

---

## Scenario 1 — TRYST executes a real coding task through OpenCode — **PASS**

### Method

New test `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow`
(tests bundle) boots the REAL TRYST runtime (`ApplicationLifecycleCoordinator` through the
normal lifecycle stages: PLATFORM → CONFIGURATION → CORE_SERVICE → APPLICATION_READY),
isolated under a temp home directory with production auth semantics
(`tryst.dev.mode=false`, bootstrapped admin) and a seeded real model configuration
(`ollama.model=qwen3-coder-30b-32k:latest` — an actually installed Ollama model).

The test then drives the REAL user workflow over the REAL HTTP API:

1. Admin login: `POST /api/auth/login` → 200, session token.
2. Harness selection + configuration:
   `POST /api/integration/applications/select {"applicationId":"opencode"}` (Bearer admin) → 200;
   `POST /api/integration/applications/opencode/config
   {"agent":"build","model":"qwen3-coder-30b-32k:latest","autoApprove":"true"}` → 200.
   The model id is deliberately a BARE id to exercise the provider-prefix normalization fix.
3. Project creation: `POST /api/projects {"name":"OpenCodeE2E"}` → 201.
4. Real repository: the working tree of a real repository (with `.git`) is copied and
   served via a `file://` URL; TRYST clones it into its sandbox (runtime `git` CLI).
5. Task intake: `POST /api/tasks/intake/submit` with title/description
   ("Append the line 'tryst-e2e-marker-7429' to README.md and create e2e-marker.txt
   containing exactly: tryst-e2e-ok"), projectId, repositoryUrl → 201 + taskId.
6. Progress polling: `GET /api/tasks` (Bearer admin) until terminal status.

### Results (actual)

- Task status reached **WAITING_FOR_REVIEW** (through CLONING → ANALYZING → PLANNING →
  IMPLEMENTING (OpenCode) → BUILDING → TESTING).
- `GET /api/tasks` shows the task with a persisted `reviewId`; `GET /api/reviews` returns
  the review for the task (reviewable result present).
- Filesystem verification inside the TRYST workspace sandbox: `e2e-marker.txt` exists and
  contains exactly `tryst-e2e-ok`; `README.md` contains the appended marker line —
  OpenCode REALLY modified the cloned repository.
- Changed-file collection: the harness delegation reported `modified files: 2`
  (README.md + e2e-marker.txt) — see also the diagnostic test below.

Surefire: `OpenCodeEndToEndWorkflowTest` — **3/3 PASS**, `testRealOpenCodeTaskThroughTrystHttpWorkflow`
**51.4 s**.

### Harness-boundary diagnostic (additional evidence)

`HarnessDelegationDiagnosticTest.testRealHarnessDelegationModifiesWorkspace` drives the
exact TaskWorker delegation seam (`HarnessAiDelegator` over the registry) against a real
cloned workspace:

```
DELEGATION_RESULT handled=true success=true summary=OpenCode execution completed in workspace ws; modified files: 2 error=
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 55.53 s
```

## Scenario 2 — EVO Developer Mode executes a real coding task through OpenCode — **PASS**

### Method

The Developer Mode execution path is: Darwin prompt → `coding_harness_execute` tool
(`CodingHarnessTool`, registered in `ToolFactory`) → `ConnectedApplicationRegistry`
selection → `OpenCodeCodingHarnessAdapter` → `opencode run` inside the resolved
development target.

New EVO test `ConnectedApplicationRegistryTest.testRealCodingHarnessToolExecutionInTarget`
registers the REAL OpenCode adapter, selects it, and invokes the REAL
`coding_harness_execute` tool with a resolved target directory. The existing
`testRealOpenCodeTaskInsideResolvedTarget` exercises the adapter directly in a resolved
target.

### Results (actual)

- Tool invocation returned the execution summary (no exception); the requested file
  `evo-tool-e2e.txt` was created INSIDE the target with the requested content.
- Target confinement: the adapter appends mandatory containment constraints
  ("never create or modify files outside this directory"); during development of this
  test the LLM once chose an absolute path outside the target — this was diagnosed from
  the persisted OpenCode tool-call record (`part.data.tool="write"`,
  `input.filePath="/tmp/evo-tool-e2e.txt"`) and fixed by the containment constraints;
  the re-run verified in-target writes.
- Surefire: `ConnectedApplicationRegistryTest` — **10/10 PASS** including
  `testRealCodingHarnessToolExecutionInTarget` 23.4 s and
  `testRealOpenCodeTaskInsideResolvedTarget` 22.4 s (both REAL OpenCode runs; previously
  the smoke test consumed the full 300 s timeout and proved nothing).

## Scenario 3 — EVO ↔ TRYST through TEIP 1.1 — **PASS (live EVO server; client verified by contract tests)**

### Method

Support test `TeipLiveServerHoldTest` boots the REAL `EvolutionServer` (TEIP 1.1 endpoints)
and holds it open; an external HTTP client (curl — the same wire behavior TRYST's
`TeipClient` uses) drives the canonical contract. (The canonical port 28080 is occupied on
this host by an unrelated third-party web application; the server was therefore bound to
28081 — the port is configuration, the contract is identical. Verified: the
`TeipApiEndpointTest` suite already covers port-28080-class behavior in-process.)

### Results (actual, live HTTP)

| Operation | Request | Observed result |
|---|---|---|
| Health | `GET /teip/v1/health` | 200, canonical 1.1 envelope, `status: UP`, `ready: true` |
| Capabilities | `GET /teip/v1/capabilities` | 200, canonical arrays |
| Create execution | `POST /teip/v1/executions` (new idempotencyKey) | **202**, `executionId: exec-920842a96ac0`, `status: QUEUED`, canonical links |
| Duplicate | same idempotencyKey again | **200**, SAME `executionId` (persisted idempotency) |
| Status | `GET /teip/v1/executions/exec-920842a96ac0` | 200, `RUNNING`, `progress: 0.35`, real status message (Darwin engine was genuinely executing; statusMessage showed Ollama fallback to `qwen3-coder-next:32k`) |
| Events | `GET .../events?afterSequence=0&limit=10` | 200, `nextSequence: 8`, sequenced events (`EXECUTION_QUEUED` first, canonical fields) |
| Cancel | `POST .../cancel` | 200 `CANCELLED` |
| Cancel again | `POST .../cancel` | 200 `CANCELLED` (idempotent cancel) |
| Result (terminal) | `GET .../result` | 200, `status: CANCELLED`, `patch`, `modifiedFiles`, `completedAt` |
| Unknown execution | `GET /teip/v1/executions/exec-nonexistent` | **404** with canonical top-level error envelope (`code: NOT_FOUND`) |

Persistence: the TEIP store created `teip_executions` / `teip_idempotency` / `teip_events`
(execution row persisted with `idempotency_key=idem-e2e-1`, status CANCELLED, 13 events) —
verified in the store's `data/evolution.db` (CWD-relative path; see limitations).

Client side: TRYST `TeipClientTest` (6/6), `TeipProtocolContractTest` (5/5),
`EvoPersistenceAndRecoveryTest` (2/2), `TeipLifecycleIntegrationTest` (3/3),
`McpServerSecurityTest` (6/6) — the TRYST-side wire/contract suites — all pass. EVO-side
server suites: `TeipApiEndpointTest` 7/7, `TeipDtoSerializationTest` 10/10,
`TeipExecutionManagerTest` 6/6, `TrystMcpClientTest` 7/7,
`TeipProtocolUiIntegrationTest` 7/7 (37/37), including real-server endpoint tests with
auth enforcement, 202/200 semantics and `VERSION_MISMATCH`.

A simultaneous two-process TRYST↔EVO run was not performed in this environment (the EVO
RCP workbench is not launched headless here); both protocol directions were verified
against the same canonical TEIP 1.1 contract with real runtimes on each side.

## Scenario 4 — Failure and recovery — **PASS**

| Failure mode | Evidence | Result |
|---|---|---|
| OpenCode executable unavailable | `OpenCodeCodingHarnessAdapterTest.testConnectionTestWithInvalidBinary` (binary `/nonexistent/opencode-binary`) | honest `ConnectionTestResult` failure; no success claim |
| Invalid model/provider configuration | `OpenCodeEndToEndWorkflowTest.testOpenCodeAdapterReportsHonestFailureForInvalidModel` — REAL CLI run with model `ollama/definitely-not-an-installed-model-xyz` (4.1 s) | `handled=true, success=false`, exit-code/stderr diagnostics in the error message; never reported as success |
| Process timeout | adapter timeout loop (bounded `waitFor(500ms)` polling against the configured timeout); default 900 s | deterministic `timedOut` result with elapsed-time message |
| Cancellation | `OpenCodeCodingHarnessAdapterTest.testCancellationSupplierPreventsLaunch` | deterministic `cancelled` result |
| Unexpected process termination | adapter `destroy()` path + interrupted-thread handling (`InterruptedException` → cancelled result, process destroyed) | covered by adapter tests |
| TEIP connection interruption / remote unavailable | TRYST `EvoIntegrationHandler.handleHealthCheck` returns `{"connected":false,"status":"DOWN",...}` on failure; EVO-side `SERVICE_UNAVAILABLE` reserved + retryable error envelope (contract tests) | verified behavior |
| HTTP-service restart | `OpenCodeEndToEndWorkflowTest.testHttpServiceRestartReRegistersIntegrationRoutes` (0.49 s) — `coordinator.restartHttpService()` then `/api/integration/applications`, `/api/integration/evo/health`, `/api/mcp` all respond again (WP-D1 fix; pre-fix these routes vanished) | PASS |
| Application restart | `FullPlatformLifecycleAndSecurityIntegrationTest` (lifecycle boot→shutdown→termination) + `ApplicationRestartRecoveryTest`, `DatabaseInitializationTest` in the 305-test full run | PASS |

## Regression suites (actual commands and results)

TRYST (all with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`,
`/home/petr/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn -o integration-test
-pl tests/eu.kalafatic.tryst.tests`):

| Command (`-Dtest=...`) | Result |
|---|---|
| `OpenCodeEndToEndWorkflowTest,HarnessDelegationDiagnosticTest` | 4/4 PASS |
| `ConnectedApplicationRegistryTest,ConnectedApplicationsHttpSecurityTest,OpenCodeCodingHarnessAdapterTest,TeipClientTest,TeipProtocolContractTest,McpServerSecurityTest,TaskIntakeAndVoiceWorkflowTest,AsynchronousTaskExecutionHttpE2ETest` | 54/54 PASS |
| `TeipLifecycleIntegrationTest,EvoPersistenceAndRecoveryTest,SchemaMigrationTest,AuthenticationAndAuthorizationTest` | 10/10 PASS |
| FULL suite excluding 3 pre-existing broken classes: `*Test,!TrystStudioIntegrationTest,!ServerLifecycleAndUiAccessTest,!PortManagementSubsystemTest` | **305/305 PASS** |

EVO (same JDK, `./mvnw -o integration-test -pl eu.kalafatic.evolution.controller.tests`):

| Command (`-Dtest=...`) | Result |
|---|---|
| `eu.kalafatic.evolution.controller.tests.harness.ConnectedApplicationRegistryTest` | 10/10 PASS |
| `eu.kalafatic.evolution.controller.tests.teip.*` | 37/37 PASS |

Builds:

| Build | Command | Result |
|---|---|---|
| TRYST bundles (domain/infrastructure/application incl. all finalization fixes) | `mvn -o install -pl plugins/eu.kalafatic.tryst.domain,plugins/eu.kalafatic.tryst.infrastructure,plugins/eu.kalafatic.tryst.application` | SUCCESS |
| TRYST full reactor | `mvn -o install` | all modules build; the reactor run fails ONLY at the tests module because of 3 pre-existing broken test classes (see limitations) — the code modules (targetplatform, core, domain, infrastructure, evo.integration, application, ui, feature, product) all SUCCESS |
| EVO controller (+adapter fix) | `./mvnw -o install -pl eu.kalafatic.evolution.controller` | SUCCESS |
| EVO full reactor | `./mvnw -o install -DskipTests` | all 28 product/test modules SUCCESS up to `eu.kalafatic.evolution.repository`, which fails ONLY at win32 product materialization in offline mode (missing Windows-specific p2 fragments: `org.apache.httpcomponents.client5.httpclient5-win`, `org.eclipse.ecf.provider.filetransfer.httpclient5.win32`) — a pre-existing packaging/environment limitation unrelated to Phase 3; the linux gtk product materialized successfully |

## Known Limitations

1. The three excluded TRYST test classes are pre-existing failures, structurally
   unrelated to Phase 3 (verified against the schema/code):
   - `TrystStudioIntegrationTest` (3 failures): the Studio subsystem persists to
     `studio_alternatives` / `studio_plan_steps` tables that NO migration (V1–V17)
     creates, so the service silently no-ops (SQLException swallowed); the review-comment
     test writes a comment referencing a nonexistent review and hits the
     `review_comments` foreign key. None of these tables/constraints were touched by any
     Phase 3 change.
   - `ServerLifecycleAndUiAccessTest` (5 failures in the FULL run only) and
     `PortManagementSubsystemTest` (1 error in the FULL run only): pass cleanly in
     isolation (6/6 and 11/11); they fail only inside the combined run due to
     cross-test OSGi-service and port state. Pre-existing suite-order sensitivity.
2. A simultaneous two-process TRYST↔EVO TEIP run was not performed (EVO workbench not
   launched headless in this environment); each protocol direction was verified live
   against the canonical contract on its own side.
3. The canonical EVO TEIP port 28080 is currently occupied on this host by an unrelated
   third-party web application; the live TEIP session used 28081 (configuration, not
   contract).
4. The EVO `TeipPersistenceStore` database path is CWD-relative (`data/evolution.db`),
   so the location of `teip_*` tables depends on the launch directory; the docs
   reference the path without noting this.
5. The user's interactive OpenCode data directory (`~/.local/share/opencode`) is
   corrupted ("database disk image is malformed", reproduced 2026-11-16); the harness
   adapters are now immune (per-execution state directories), but the user's interactive
   OpenCode environment itself remains broken and is outside Phase 3 scope.
6. Live LLM behavior remains probabilistic: the containment constraints make
   in-target writes the norm, but an LLM may still attempt out-of-target absolute paths;
   for git-based targets the modified-file containment check rejects such escapes.
