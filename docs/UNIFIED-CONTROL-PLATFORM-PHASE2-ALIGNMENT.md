# Unified Multi-Harness / Multi-Provider / Multi-Agent Control Platform
# PHASE 2 — Contract & Domain Alignment

**Date:** 2026-11-20
**Status:** Design baseline for Phases 3–6. Evolves existing FHC/TEIP contracts compatibly;
no competing contracts or duplicate registries are introduced.

---

## 1. Ownership (unchanged, reinforced)

| Concern | Owner |
|---|---|
| Coding-harness execution infrastructure, inference engines, TEIP provider | **DStudio** |
| Task lifecycle, sandboxes, repositories, review, approval, Git authority, workflow | **TRYST** |
| DCode harness (independent product, FHC 1.0 `/api/v1`) | **DCode** (consumed by both) |

TRYST must never be required to run DStudio for standalone operation, and vice versa.
TRYST consumes DStudio through TEIP 1.1 only (`DStudioCodingHarnessAdapter`).

## 2. The Four Dimensions — aligned vocabulary

| Dimension | DStudio term (authoritative in DStudio) | TRYST term | Wire term (TEIP `parameters.*`) |
|---|---|---|---|
| A. Harness | `harnessId` (`HarnessRegistry`) | connected application id (`ConnectedApplicationRegistry`) | `harness` (existing) |
| B. Provider / inference engine | `engineId` (`InferenceEngineRegistry`) | provider id (`AiModelRegistryService`, new persisted `ai_providers`) | `provider` (new, open-world) |
| C. Model | model id/name from engine `listModels()` | model identifier | `model` (existing field + parameter) |
| D. Agent | `agentProfileId` (NEW `AgentProfileRegistry`) | agent profile id (evolved `agents` table) | `agent` (new, open-world) |

Rule: an execution always carries all four identifiers explicitly (or resolves them through the
global-default chain at creation time and **snapshots the resolved values**).

## 3. Agent profile contract (NEW, both products)

```
AgentProfile {
  id, name, description, role, systemInstructions,
  toolPermissions: List<String> (e.g. "read:workspace","write:workspace","execute:build"),
  restrictions: List<String>,
  preferredHarnessId: String (empty = any),
  preferredProviderId: String (empty = any),
  preferredModel: String (empty = any),
  executionParameters: Map<String,String> (timeoutSeconds, autoApprove, maxTokens, temperature),
  createdAt, updatedAt
}
```

- Compatibility check (`capability-aware`): profile is *compatible* with (harness, provider,
  model) iff: preferredHarnessId empty or == selected harness; preferredProviderId empty or ==
  selected provider; the selected harness's `supportedProviderTypes` contains the provider's
  engine type; the provider's discovered models contain the selected model when the provider
  is available. Incompatible combinations are **never silently executed** — the caller must
  choose explicitly or the request is rejected with `CAPABILITY_UNSUPPORTED`.
- DStudio: new `AgentProfileRegistry` + `AgentProfileStore` (SQLite V4). `ExecutionRequest`
  gains `agentProfileId` (kept alongside the legacy free-text `agent` for the OpenCode
  `--agent` CLI value; when a profile is supplied, the profile's system instructions are
  prepended to the prompt and its permissions are passed to the adapter as configuration).
- TRYST: the existing `agents` table (V1: id, name, model_name, system_prompt,
  configuration_json) is **evolved compatibly** (V21 migration adds nullable columns:
  role, description, tool_permissions, restrictions, preferred_harness, preferred_provider,
  preferred_model, execution_parameters_json). Existing rows keep working (built-in local
  agent semantics unchanged).

## 4. Unified execution configuration + inheritance

```
ExecutionConfiguration {
  harnessId, providerId, modelId, agentProfileId,
  executionParameters: Map<String,String>,   // timeout, autoApprove, maxTokens, temperature
  toolPermissions: List<String>,
  resourceLimits: Map<String,String>,
  harnessSettings: Map<String,String>,       // adapter-specific, secrets excluded
  source: GLOBAL | PROJECT | TASK | EXECUTION
}
```

- Resolution: **GLOBAL defaults → PROJECT overrides → TASK overrides → EXECUTION snapshot**.
  More specific non-null/non-blank values win; the resolved value is validated for
  compatibility before execution.
- **Snapshot**: every accepted execution persists the full resolved configuration (immutable)
  before the harness starts. Changes later never affect a running execution.
- DStudio: global defaults persisted in the new `execution_configs` table (V4) keyed by
  scope+key; project overrides extend `projects` (V4 column `exec_config_json`); task
  overrides arrive through TEIP `parameters.*`; snapshots in new `execution_snapshots` (V4).
- TRYST: new migration V21: `execution_configurations` (scope GLOBAL/PROJECT/TASK, JSON,
  secrets excluded) + `task_execution_snapshots` (task_id → resolved config JSON) +
  `ai_providers` (persisted provider registry: id, name, type, endpoint, enabled, defaults).
  Secrets (API keys, tokens) are **runtime-only or encrypted**: TRYST adopts an
  AES/GCM `SecretCodec` equivalent to DStudio's for persisted provider credentials; masked
  in `getConnectionConfiguration()` and never in logs/snapshots/diagnostics.

## 5. TEIP 1.1 evolution (backward-compatible, open-world)

`POST /teip/v1/executions` gains optional parameters only (existing callers unaffected):

| parameter | meaning |
|---|---|
| `parameters.harness` (existing) | DStudio harness id |
| `parameters.provider` (new) | inference engine id; when absent the engine default applies |
| `parameters.model` (existing) | model id |
| `parameters.agent` (new) | agent profile id (must exist; compatibility-checked) |
| `parameters.agentInstructions` (new, optional) | free-text system prompt override |
| `parameters.autoApprove` (new) | boolean string |
| `parameters.toolPermissions` (new, JSON array) | effective tool permission list |
| `parameters.execParams.<key>` (new) | execution parameter overrides (timeoutSeconds etc.) |

Capabilities response already exposes harness `supportedProviderTypes` and models; the
agent list is extended with profile ids (`TeipWire.AgentDescriptor` gains `description`,
`preferredHarness`, `preferredProvider`, `preferredModel`).

## 6. What is deliberately NOT shared

- No cross-product registry, session manager, event bus or database.
- TRYST keeps its own `ConnectedApplicationRegistry`, `AiModelRegistryService` and new
  provider/agent stores; DStudio keeps `HarnessRegistry`, `InferenceEngineRegistry` and the
  new `AgentProfileRegistry`. Parity is maintained by mirrored domain semantics and the TEIP
  wire, not by shared bundles.
- DCode remains independently executable; OpenCode remains optional everywhere.

## 7. Phase sequencing

1. **Phase 3 (DStudio)**: `AgentProfile` SPI + registry + SQLite store + migration V4;
   execution configuration (defaults/inheritance/snapshot) + `ExecutionService` wiring;
   TEIP `parameters.agent`/`provider`/`autoApprove`/`toolPermissions`; Agent Manager +
   Provider Manager UI; runtime monitor completeness; tests.
2. **Phase 4 (TRYST)**: migration V21 (`ai_providers`, `execution_configurations`,
   `task_execution_snapshots`, `agents` extension); provider registry + SecretCodec;
   execution-config resolution service (global/project/task/snapshot); harness/provider/
   model/agent selection per task (TaskManagement/TaskDetails/Studio/TaskExecution pages);
   AI Environment page upgrade (keep all existing operations); DStudio adapter passes
   agent/provider parameters; per-task runtime monitoring; fix DCode default endpoint
   (58080 family convention); tests.
3. **Phase 5**: cross-project build + unit/integration tests + E2E scenarios (OpenCode and
   DCode through DStudio, provider/model switching, unavailable-harness behavior,
   credential-leak checks).
4. **Phase 6**: gap closure; remove obsolete duplicates only after proving nothing depends
   on them; final report.
