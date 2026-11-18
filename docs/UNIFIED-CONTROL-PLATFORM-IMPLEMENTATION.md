# Unified Multi-Harness / Multi-Provider / Multi-Agent Control Platform
# FINAL IMPLEMENTATION REPORT

**Date:** 2026-11-20
**Repositories:** DStudio (`DStudio-121126`), TRYST (`tryst-121126`)
**Preceding deliverables:** `UNIFIED-CONTROL-PLATFORM-PHASE1-AUDIT.md` (component
inventory, contracts, gaps, risks), `UNIFIED-CONTROL-PLATFORM-PHASE2-ALIGNMENT.md`
(ownership, four-dimension vocabulary, agent-profile contract, execution-configuration
inheritance, TEIP evolution).

---

## 1. Architecture changes

### DStudio (harness platform owner)
- **New agent dimension** — `core/agent/AgentProfile` (system instructions, role,
  description, tool permissions, restrictions, preferred harness/provider/model,
  execution parameters), `AgentProfileRegistry` + `DefaultAgentProfileRegistry` (CRUD,
  clone, default selection, capability-aware compatibility checks against harness ×
  inference engine × model), persisted via `AgentProfileStore`/`SqliteAgentProfileStore`
  (schema V4).
- **Execution configuration with inheritance** — `core/config/ExecutionConfiguration`
  (harness, provider, model, agent profile, parameters, permissions, limits, settings),
  `ExecutionConfigurationService`/`DefaultExecutionConfigurationService` resolving
  GLOBAL → PROJECT → TASK, persisted in `execution_configs`; **immutable snapshots** in
  `execution_snapshots` (schema V4). `DefaultExecutionService` resolves, validates
  (provider existence, agent compatibility) and snapshots before any harness starts;
  `ExecutionTask`/`SqliteExecutionStore` record the resolved provider/agent/parameters.
- **Harness request enrichment** — `HarnessExecutionRequest` now carries providerId,
  agentProfileId, agent system instructions, tool permissions and execution parameters
  so adapters never guess the execution environment.
- **TEIP 1.1 additive evolution** — `parameters.provider`, `parameters.agent`,
  `parameters.autoApprove`, `parameters.toolPermissions` (open-world `parameters.*`);
  agent profiles appear in the capabilities response (`TeipWire.AgentDescriptor` gained
  preferredHarness/preferredProvider/preferredModel/toolPermissions — additive).
- **Port-convention correction (cross-project defect)** — DStudio reverted to the
  documented family convention **48080/48180** (its transient 58xxx values collided with
  DCode's 58080 primary port); persisted 58xxx values are corrected once on load;
  `DCodeHarnessAdapter.DEFAULT_ENDPOINT` = 58080 (DCode's own port).

### TRYST (workflow owner, DStudio consumer)
- **Persisted provider registry** — `AiProviderRegistry`/`DefaultAiProviderRegistry`
  wrapping the existing `AiModelRegistryService` catalog (single provider lifecycle);
  `AiProviderDescriptor` + `AiProviderRepository`/`SqliteAiProviderRepository`
  (schema V21 evolves the existing V4 `ai_providers` table: `default_model`,
  `config_json`; new `ai_provider_state`). Credentials encrypted at rest with the new
  AES/GCM `SecretCodec` (`secrets.key`, owner-only); masked (`****`) on every read path;
  password-field semantics keep existing secrets when masked values are re-saved.
- **Execution configuration** — TRYST `ExecutionConfiguration`,
  `ExecutionConfigurationService` (GLOBAL→PROJECT→TASK), `execution_configurations` +
  `task_execution_snapshots` (schema V21). `HarnessAiDelegator` (the preserved
  `ExternalAiDelegator` seam) resolves the **per-task harness**, fails explicitly when a
  configured harness is unavailable (never silently switches), and snapshots the resolved
  environment before the harness executes.
- **Agent profiles** — `AgentProfileSupport` stores profile fields inside the existing
  V1 `agents.configuration_json` (forward-compatible; local-agent semantics unchanged);
  the `agents` table also gained V21 profile columns.
- **DStudio adapter** — `DStudioCodingHarnessAdapter` passes task-resolved
  provider/agent/model/harness through TEIP parameters.
- **DCode adapter** — default endpoint corrected to `http://localhost:58080` (DCode's
  family port).
- **Shared JSON helper** — `core/json/TrystJson` (flat string maps/lists, dependency-free)
  with an infrastructure facade; one implementation shared by core/domain/infrastructure.

## 2. Files and modules modified

### DStudio
- New: `core/agent/AgentProfile.java`, `AgentProfileRegistry.java`,
  `DefaultAgentProfileRegistry.java`; `core/store/AgentProfileStore.java`;
  `core/config/ExecutionConfiguration.java`, `ExecutionConfigurationService.java`,
  `DefaultExecutionConfigurationService.java`; `core/store/ExecutionConfigurationStore.java`;
  `infrastructure/persistence/SqliteAgentProfileStore.java`,
  `SqliteExecutionConfigurationStore.java`;
  `ui/agent/AgentView.java`, `ui/agent/AgentConfigDialog.java`;
  `tests/…/AgentProfileAndExecutionConfigTest.java`.
- Modified: `SchemaMigrationService` (V4), `DefaultExecutionService`,
  `ExecutionService.ExecutionRequest`, `ExecutionTask`, `HarnessExecutionRequest`,
  `TeipExecutionManager`, `TeipWire.AgentDescriptor`, `DStudioLifecycleCoordinator`,
  `DStudioUiPlugin`, `HarnessPerspective`, `RunHarnessDialog`, `ExecutionView`,
  `ui/plugin.xml`, `core/META-INF/MANIFEST.MF`, `JsonSupport`,
  `DCodeHarnessAdapter` (endpoint), `DStudioConfiguration` + `DStudioConfigurationServiceImpl`
  (port convention), `PortConflictHandlingTest` (repaired pre-existing NPE + convention tests).

### TRYST
- New: `core/json/TrystJson.java`; `domain/ai/AiProviderDescriptor.java`,
  `AiProviderRegistry.java`, `DefaultAiProviderRegistry.java`,
  `ExecutionConfiguration.java`, `ExecutionConfigurationService.java`,
  `DefaultExecutionConfigurationService.java`, `AgentProfileSupport.java`;
  `domain/repository/AiProviderRepository.java`, `ExecutionConfigurationRepository.java`;
  `infrastructure/persistence/SecretCodec.java`, `TrystJson.java` (facade),
  `infrastructure/persistence/repository/SqliteAiProviderRepository.java`,
  `SqliteExecutionConfigurationRepository.java`;
  `ui/editor/page/AgentProfileDialog.java`, `TaskExecutionEnvironmentDialog.java`;
  `tests/…/UnifiedAiEnvironmentTest.java`.
- Modified: `SchemaMigrationService` (V21, `LATEST_VERSION`), `AIProvider` (compatible
  default methods), `HarnessAiDelegator` (per-task resolution + snapshot),
  `DStudioCodingHarnessAdapter` (TEIP provider/agent/model params),
  `DCodeCodingHarnessAdapter` (endpoint 58080), `ApplicationLifecycleCoordinator`
  (provider registry + exec config wiring, OSGi registrations, getters), `TrystUiServices`
  (accessors), `AiIntegrationPage` (full AI Environment page), `TaskManagementPage`
  (execution-environment button), `core/META-INF/MANIFEST.MF`; 4 schema-version tests +
  `ApplicationLifecycleTest` updated to the new migration/notification reality.

## 3. Database migrations

| Repo | Migration | Content |
|---|---|---|
| DStudio | **V4** (new) | `agent_profiles`, `agent_profile_state`, `execution_configs`, `execution_snapshots`; `executions` gains `provider_id`, `agent_profile_id`, `exec_params_json` |
| TRYST | **V21** (new) | `ai_providers` gains `default_model`, `config_json` (evolves V4 table); `ai_provider_state`; `execution_configurations`; `task_execution_snapshots`; `agents` gains 8 profile columns |
| TRYST | `SchemaMigrationService.LATEST_VERSION` | 21 (constant added; tests now assert against it) |

Forward-only chains preserved; both verified against real existing databases
(TRYST `~/.tryst/tryst.db` migrated 20→21 live; DStudio fresh + convention correction).

## 4. UI changes

- **DStudio**: new **Agent Profiles** view + create/edit dialog (instructions, role,
  tools, restrictions, preferred environment) in the AI & Harness perspective;
  `RunHarnessDialog` gains Provider + Agent Profile selectors; `ExecutionView` shows
  Provider/Agent/Model columns.
- **TRYST**: `AiIntegrationPage` replaced by the complete **AI Environment** page:
  global execution-environment defaults (harness/provider/model/agent), Connected
  Harnesses management (default/enable/test/configure), AI Providers management
  (test/enable/default/configure with encrypted credentials), Agent Profiles
  management (create/edit/clone/delete/default), and a **Runtime Monitor** table showing
  the actual execution environment per task from its snapshot. All previous operations
  (Test AI Connectivity, Refresh Models, Start/Stop AI Service, Configure Endpoint,
  Copy Model Info) preserved. `TaskManagementPage` gains "Execution Environment..."
  per-task override (harness/provider/model/agent; running executions never modified).

## 5. Tests executed and actual results

| Suite | Result |
|---|---|
| DStudio `mvn install` + tests (Tycho Surefire, OSGi runtime) | **168/168 pass** (was 158; +9 new, +1 repaired pre-existing NPE test) |
| TRYST full test suite (363 tests) | **362/363 pass** — the single failure is the pre-existing live `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow` requiring the real opencode CLI/model environment (same failure before this work) |
| New tests | DStudio `AgentProfileAndExecutionConfigTest` (6), TRYST `UnifiedAiEnvironmentTest` (5) — credential encryption at rest, provider lifecycle persistence, scope inheritance + snapshot immutability, agent-profile round trip, per-task harness resolution incl. explicit unavailability failure |
| Cross-project E2E | TRYST `DStudioLiveE2eTest` against a live headless DStudio on 48080: **PASS — real task delegated TRYST → DStudio → harness** (marker file produced) |
| Runtime | DStudio headless: TEIP health/capabilities on 48080, V4 schema verified in SQLite. TRYST product: clean startup, V21 applied on the real user DB, providers registered, no view/SWT errors |

## 6. Acceptance criteria status

1. DStudio functional multi-harness management — **YES** (existing + verified).
2. DStudio functional multi-provider management — **YES** (engine registry + new agent/config UI; engine instances HTTP-administered).
3. DStudio model discovery and management — **YES** (engine discovery + load/unload; verified live for Ollama/OpenAI-compatible).
4. DStudio agent profile management — **YES** (new registry, store, UI, tests).
5. TRYST equivalent essential selection and control — **YES** (AI Environment page).
6. TRYST per-task harness/provider/model/agent — **YES** (TASK-scope overrides + snapshots + delegator resolution; UI in TaskManagementPage + AI Environment).
7. Compatible shared domain contracts — **YES** (TEIP parameters + mirrored domain semantics; Phase 2 doc).
8. Existing workflows operational — **YES** (all pre-existing suites green except the noted live test; splash/menu work intact).
9. Local and cloud inference through compatible providers — **PARTIAL** (Ollama/ENR local; OpenAI-compatible/Anthropic adapters exist in DStudio and are wire/live-tested where endpoints exist; TRYST cloud providers require adding adapters — registry supports it).
10. DCode, OpenCode, Aider independently selectable — **YES** (DStudio harness registry; TRYST connected applications incl. DStudio-TEIP route; port collision between DStudio and DCode resolved).
11. Execution status reflects real runtime — **YES** (snapshots + runtime monitor + TEIP status).
12. Cross-project E2E tests pass — **YES** (DStudioLiveE2eTest live-run above; repeat with DCode requires a running DCode at 58080 — the DCode adapter path is covered by unit tests and the fixed endpoint).

## 7. Remaining limitations (honest disclosure)

- Cloud provider adapters in TRYST (OpenAI/Anthropic/OpenAI-compatible) are not yet
  registered as TRYST-local `AIProvider`s — the persisted registry supports them, but
  TRYST ships Ollama+ENR adapters only; cloud inference is reachable today through the
  DStudio harness route.
- Web API exposure of the new registries is limited to the existing connected-application
  handler; provider/agent/execution-config endpoints for the TRYST web surface are not
  yet added (RCP UI and domain services are the shared backend).
- Cross-project E2E with **DCode** as the harness requires a running DCode product at
  58080 (not available on this host); its adapter path is unit-covered and its endpoint
  corrected.
- DStudio GUI runtime verification requires a graphical host (AGENTS.md convention);
  compile- and unit-verified only.

## 8. Known environment notes

- TRYST's only failing test (`OpenCodeEndToEndWorkflowTest`) and the three DStudio-side
  family live tests require external runtimes (opencode CLI, EVO MCP) and fail/skip
  identically before and after this work.
- Three stale DStudio headless processes from earlier sessions were left untouched
  (user-owned).
