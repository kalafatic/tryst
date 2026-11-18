# Unified Multi-Harness / Multi-Provider / Multi-Agent Control Platform
# PHASE 1 — Architectural Audit Report

**Date:** 2026-11-20
**Scope:** TRYST (`tryst-121126`) + DStudio (`DStudio-121126`)
**Method:** Direct source inspection of both repositories. No phase-report assumption was
taken at face value; every EXISTS verdict below was verified against actual source files.

---

## 1. Existing Component Inventory

### 1.1 DStudio — bundle layout (dependency direction enforced: ui → application → {teip, harness, infrastructure} → core)

| Bundle | Role | Key components (verified paths) |
|---|---|---|
| `eu.kalafatic.dstudio.core` | SPI, domain, services, stores, event bus | `core/harness/*`, `core/inference/*`, `core/model/*`, `core/service/*`, `core/store/*`, `core/event/*`, `core/config/DStudioConfiguration` |
| `eu.kalafatic.dstudio.harness` | Harness + inference-engine adapters | `opencode/OpenCodeHarnessAdapter`, `aider/AiderHarnessAdapter`, `dcode/DCodeHarnessAdapter`, `claude/ClaudeCodeHarnessAdapter`, `enr/EnrInferenceEngineAdapter`, `ollama/OllamaInferenceEngineAdapter`, `openai/OpenAiCompatibleInferenceEngineAdapter`, `anthropic/AnthropicInferenceEngineAdapter`, `ContainmentConstraints` |
| `eu.kalafatic.dstudio.infrastructure` | SQLite, JGit, secrets, config | `persistence/Sqlite*Store` (7 stores), `SchemaMigrationService` (V1–V3), `SecretCodec` (AES/GCM), `JGitWorkspaceDiffService`, `ProcessExecutionSupport`, `DStudioConfigurationServiceImpl` |
| `eu.kalafatic.dstudio.teip` | TEIP 1.1 provider + API + web assets | `server/DStudioServer`, `TeipHttpHandler`, `TeipExecutionManager`, `TeipWire`, `TeipAuth`/`ApiAuth`, `SseSupport`/`EventsSseHandler`, `InferenceHttpHandler`, `WorkspaceHttpHandler`, `WebAssetsHandler`, `client/EvoMcpClient` |
| `eu.kalafatic.dstudio.application` | Lifecycle + IApplication (desktop/headless) | `DStudioApplication`, `DStudioServerApplication`, `DStudioHeadlessRunner`, `DStudioRuntime`, `DStudioStartupExit`, `lifecycle/DStudioLifecycleCoordinator`, `DStudioInferenceEngineFactory`, `PortConflictDialog` |
| `eu.kalafatic.dstudio.ui` | RCP views/perspectives/handlers | `harness/HarnessView` + `HarnessConfigDialog`, `inference/InferenceView`, `execution/ExecutionView` + `RunHarnessDialog`, `explorer/ProjectExplorerView`, `review/ReviewView`, `handlers/*`, 4 perspectives |

### 1.2 TRYST — bundle layout (dependency direction enforced)

| Bundle | Role | Key components (verified paths) |
|---|---|---|
| `eu.kalafatic.tryst.domain` | Domain model, harness SPI, AI services | `harness/CodingHarnessAdapter` (SPI), `ConnectedApplicationRegistry`/`DefaultConnectedApplicationRegistry`/`ConnectedApplicationRepository`/`ConnectedApplicationDescriptor`, `HarnessAiDelegator`, `ai/ExternalAiDelegator`, `ai/AiModelRegistryService` (+`ModelRegistry`), `ai/AIProvider`, `ai/AiInferenceService`, `sandbox/SandboxProvider`, `model/Task` |
| `eu.kalafatic.tryst.infrastructure` | Adapters, persistence, CLI, VCS | `cli/OpenCodeCodingHarnessAdapter`, `dcode/DCodeCodingHarnessAdapter`, `ai/OllamaProvider`, `ai/EnrProvider`, `persistence/SchemaMigrationService` (V1–V20), `persistence/repository/Sqlite*Repository` (20+), `sandbox/DefaultProcessSandboxProvider` |
| `eu.kalafatic.tryst.evo.integration` | Family connectors | `service/DStudioCodingHarnessAdapter`, `service/EvoCodingHarnessAdapter`, `service/EvoAiDelegator`, `service/EvoIntegrationService`, `client/TeipClient`, `model/TeipModels`, `mcp/McpServer` |
| `eu.kalafatic.tryst.application` | Lifecycle + HTTP server wiring | `lifecycle/ApplicationLifecycleCoordinator`, `http/ConnectedApplicationsHttpHandler`, `TrystApplication`/`TrystServerApplication` |
| `eu.kalafatic.tryst.ui` | RCP editor pages/views | `editor/page/AiIntegrationPage`, `editor/page/StudioEditorPage`, `editor/page/TaskManagementPage`, `editor/page/TaskExecutionPage`, `editor/page/TaskDetailsPage`, `editor/page/TeipIntegrationPage`, 15 pages total |

---

## 2. Existing Contracts (reusable, verified)

| Contract | Location | Shape | Notes |
|---|---|---|---|
| **HarnessAdapter (DStudio SPI)** | `dstudio.core/harness/HarnessAdapter` | id/name/type/capabilities/availability/testConnection/config/`execute(request, sink)` | Sink = `HarnessExecutionSink` (streaming callbacks) |
| **HarnessRegistry (DStudio)** | `dstudio.core/harness/HarnessRegistry` | register/unregister/list/find/default-select/enable/config/test/aggregateCapabilities | Persisted via `HarnessStore` |
| **InferenceEngineAdapter (DStudio SPI)** | `dstudio.core/inference/InferenceEngineAdapter` | engineId/type/availability/test/listModels/load/unload/`chat`/`streamChat`/runtimeStatus | Includes model + chat records |
| **InferenceEngineRegistry (DStudio)** | `dstudio.core/inference/InferenceEngineRegistry` | register/unregister/remove/list/find/default/enable/config/test | Multi-instance; HTTP admin routes exist |
| **InferenceService (DStudio)** | `dstudio.core/inference/InferenceService` + `InferenceSession`/`InferenceSessionMessage` | persistent multi-turn sessions, stream + cancel | Schema V3 |
| **ExecutionService (DStudio)** | `dstudio.core/service/ExecutionService` | submit/cancel/get/events/list/reconcile; `ExecutionRequest(harnessId, projectId, idempotencyKey, instruction, prompt, model, agent, autoApprove, workingDirectory, timeout)` | `agent` is an opaque string — no agent registry |
| **TEIP 1.1 wire** | `dstudio.teip/server/TeipWire` + `TeipExecutionManager` | health/capabilities/create(202 idempotent)/status/events(cursor)/result/cancel; `parameters.harness`, `parameters.workingDirectory`, `parameters.model`, `parameters.timeoutSeconds` | Normative spec: `evolution-121126/docs/protocol/TEIP-1.1.md` |
| **FHC 1.0** | DCode `/api/v1` surface consumed by `DCodeHarnessAdapter` (DStudio) and `DCodeCodingHarnessAdapter` (TRYST) | DCode harness wire mapping | DCode remains independently executable |
| **CodingHarnessAdapter (TRYST SPI)** | `tryst.domain/harness/CodingHarnessAdapter` | appId/name/type/capabilities/availability/test/config/`executeTask(task, workspace, instructions, prompt, logConsumer, cancellationSupplier, timeout)` | 4 implementations registered |
| **ExternalAiDelegator (TRYST seam)** | `tryst.domain/ai/ExternalAiDelegator` | `isAvailableForTask(task)`, `delegateImplementation(...)` | Consumed by `TaskWorker` (line 687–690); `HarnessAiDelegator` bridges to the registry |
| **ConnectedApplicationRegistry (TRYST)** | `tryst.domain/harness/ConnectedApplicationRegistry` | register/unregister/list/find/select/test/config/enable | Product-local registry; persisted V17 |

## 3. Existing Registries (verified)

| Registry | Repo | Status |
|---|---|---|
| `DefaultHarnessRegistry` | DStudio | EXISTS — 4 adapters registered in `DStudioLifecycleCoordinator.onCoreServiceInitialization()` (lines 168–175) |
| `DefaultInferenceEngineRegistry` | DStudio | EXISTS — 4 engine adapters registered (lines 196–205) + factory-based runtime instances |
| `DefaultConnectedApplicationRegistry` | TRYST | EXISTS — adapters registered in `ApplicationLifecycleCoordinator.onCoreServiceInitialization()` (EVO, OpenCode, DStudio, DCode) |
| `AiModelRegistryService` | TRYST | EXISTS — in-memory provider+model catalog (not persisted) |
| Agent registry | both | **MISSING** — TRYST has a V1 `agents` DB table (id, name, model_name, system_prompt, configuration_json) + `AgentRepository`, but no registry service, no profile editor, no capabilities; DStudio has no agent dimension at all |
| Execution-profile registry | both | **MISSING** |
| Central model registry (metadata, context limits, activation) | both | **PARTIAL** — models are engine-discovered lists (`InferenceEngineAdapter.InferenceModel`, `AIProvider.ProviderModelMetadata`); TRYST hardcodes `contextLimit=4096` in `AiModelRegistryService`; DStudio has `ModelDescriptor` (contextWindowTokens) only inside harness capability descriptors |

## 4. Existing Adapters (verified)

| Adapter | Repo | Live status |
|---|---|---|
| OpenCodeHarnessAdapter | DStudio | EXISTS, live-verified (Phase 7 evidence) |
| AiderHarnessAdapter | DStudio | EXISTS |
| DCodeHarnessAdapter | DStudio | EXISTS (endpoint 58080) |
| ClaudeCodeHarnessAdapter | DStudio | EXISTS — CLI not present on host; live test skips |
| Enr/Ollama/OpenAiCompatible/AnthropicInferenceEngineAdapter | DStudio | EXIST — OpenAI-compat live-verified against real Ollama `/v1` |
| OpenCodeCodingHarnessAdapter | TRYST | EXISTS (local CLI) |
| DCodeCodingHarnessAdapter | TRYST | EXISTS (TEIP/FHC mapping, WAITING_FOR_APPROVAL flow) |
| DStudioCodingHarnessAdapter | TRYST | EXISTS (TEIP 1.1 client; config V18) |
| EvoCodingHarnessAdapter | TRYST | EXISTS (TEIP client; EVO endpoint) |
| OllamaProvider / EnrProvider | TRYST | EXIST (`AIProvider` impls) |

## 5. Existing Persistence (verified)

- **DStudio** SQLite `~/.dstudio/dstudio.db`, forward-only V1–V3: `harnesses`, `harness_state`, `harness_config`, `projects`, `executions`, `execution_events`, `execution_idempotency`, `reviews`, `review_files`, `inference_engines`, `inference_engine_state`, `inference_engine_config`, `inference_sessions`, `inference_messages`. **Secrets encrypted at rest** via `SecretCodec` (AES/GCM, key file 600).
- **TRYST** SQLite `~/.tryst/tryst.db`, forward-only V1–V20: `tasks`, `sessions`, `projects`, `workspaces`, `execution_results`, `agents`, `users`, `connected_applications` (V17), `dstudio_configurations` (V18), `dcode_configurations` (V20), plus 40+ tables. **DCode/DStudio auth tokens intentionally NOT persisted** (runtime-only) — restart loses authentication state; no encrypted-secret store in TRYST.
- **Task-level execution configuration: MISSING in both.** `Task` (TRYST) has `agentId`/`agentType` but no harness/provider/model/agent-profile fields; `ExecutionTask` (DStudio) stores harnessId/model/agent strings but no provider id, permissions or snapshot.
- **Execution configuration snapshots: MISSING in both.**

## 6. Existing UI (verified)

- **DStudio RCP**: `HarnessView` (table: harness/type/enabled/status/default + Set Default / Enable / Test / Config buttons), `InferenceView` (engine + model tables + Set Default / Enable / Test / Refresh Models / Load / Unload), `ExecutionView` + `RunHarnessDialog` (model list discovered from engines, filtered by harness provider-type compatibility), `ReviewView`, `ProjectExplorerView`, `HarnessConfigDialog`. No Agent Manager, no Provider Manager (engine instances are HTTP-administered only), no Model Manager (download/remove not applicable), no Execution-Profile UI.
- **DStudio Web IDE**: `/` assets, workspace file API, AI chat with engine/model selectors, TEIP task surface, SSE events.
- **TRYST RCP**: `AiIntegrationPage` — Ollama-centric (provider label, endpoint, model combo, Start/Stop AI service, Test connectivity, Refresh Models, Configure endpoint, Copy info). No harness selection, no provider switching, no agent selection, no execution parameters. Task pages (TaskManagement/TaskDetails/TaskExecution/Studio) have no execution-environment selection.
- **TRYST Web**: `ConnectedApplicationsHttpHandler` exposes connected-application administration; task intake does not carry execution configuration.

## 7. Missing Functionality (vs. target specification)

| Dimension | DStudio | TRYST |
|---|---|---|
| Harness registry/discovery | EXISTS | EXISTS (connected applications) |
| Harness lifecycle mgmt (start/stop/restart, sessions view) | PARTIAL (processes per execution; no daemon mgmt) | PARTIAL (no harness lifecycle; AI service start/stop only) |
| Provider registry/SPI | EXISTS (inference-engine registry ≈ provider) | PARTIAL (2 providers; registry in-memory) |
| Provider add/edit/remove + credentials UI | PARTIAL (HTTP admin only; no RCP dialog) | MISSING |
| Model registry/discovery | PARTIAL (engine lists; no central registry) | PARTIAL (AiModelRegistryService, hardcoded context 4096, no persistence) |
| Model download/load/unload/activate | PARTIAL (load/unload per engine) | PARTIAL (start/stop AI service) |
| Agent registry / profiles | **MISSING** | PARTIAL (V1 `agents` table + repository, no service/UI) |
| Execution profiles (harness+provider+model+agent combos) | **MISSING** | **MISSING** |
| Session management | EXISTS (inference sessions + executions) | EXISTS (sessions; not harness-visible) |
| Streaming & cancellation | EXISTS | EXISTS (per adapter) |
| Runtime monitoring | PARTIAL (ExecutionView/events; no token stats) | PARTIAL (AiIntegrationPage status only) |
| Global→project→task config inheritance | **MISSING** | **MISSING** |
| Per-task harness/provider/model/agent selection | PARTIAL (TEIP parameters.harness/model only) | **MISSING** (global selection only) |
| Execution config snapshot (immutable, persisted) | **MISSING** | **MISSING** |
| Capability-aware compatibility validation | PARTIAL (supportedProviderTypes in diagnostics; run dialog filters) | MISSING |

## 8. Duplicate Implementations / Divergence Risks

1. **Two harness SPI families** (by design, both project-owned): DStudio `HarnessAdapter` and TRYST `CodingHarnessAdapter`. TRYST routes to DStudio via TEIP (`DStudioCodingHarnessAdapter`), and separately keeps its own local OpenCode/DCode adapters — OpenCode and DCode execution logic exists in BOTH repositories (DStudio OpenCodeHarnessAdapter vs TRYST OpenCodeCodingHarnessAdapter; DCode likewise). Risk: behavioral drift; mitigation: TRYST-local adapters are kept only for standalone mode and must respect the same compatibility matrix.
2. **Two provider abstraction families**: DStudio `InferenceEngineAdapter` vs TRYST `AIProvider`/`AiInferenceService`. Ollama/ENR clients exist in both repos. No shared contract (per project ownership rules, TRYST must not depend on DStudio bundles).
3. **Model catalog duplication**: TRYST `AiModelRegistryService` (in-memory, hardcoded 4096 context) vs DStudio engine `listModels()` + harness `ModelDescriptor`. Divergent metadata shapes.
4. **Agent semantics**: DStudio `request.agent()` is an opaque string passed to `opencode --agent`; TRYST `agents` table has `model_name`-bound entries used by the local agent execution. No shared profile contract.

## 9. Compatibility Risks

- **No shared wire contract for provider/agent/profile exchange** between TRYST and DStudio (TEIP execution carries only harness/model/workingDirectory/timeout). Extending TEIP parameters must remain open-world (`parameters.*`) per DStudio AGENTS.md.
- **Credential persistence asymmetry**: DStudio encrypts at rest; TRYST stores nothing (tokens lost on restart). A TRYST-selected cloud provider (OpenAI/Anthropic) would need encrypted persistence or runtime-only re-entry — spec requires secrets never in logs/UI/snapshots and separate from ordinary config.
- **DCode harness endpoint**: DStudio `DCodeHarnessAdapter.DEFAULT_ENDPOINT` = 58080 (family convention); TRYST `DCodeCodingHarnessAdapter.DEFAULT_ENDPOINT` currently equals `http://localhost:48080` — **divergence** (TRYST points at DStudio's port instead of DCode's 58080). Verified in source.
- **Hardcoded defaults**: TRYST AiModelRegistryService hardcodes `codellama` default model and 4096 context limit; Ollama-only AI service lifecycle (`TrystApplication` startHttpServer etc.).
- **Port conventions**: DStudio 48080/48180 (per DStudio AGENTS.md); TRYST 38080 block — both repos document the family +10000 convention; TRYST's DCode adapter default contradicts it.
- **Forward-only migrations**: any schema addition must be a new migration in both repos; TRYST is at V20, DStudio at V3.

## 10. Phase 1 Verdict

DStudio owns a mature, tested harness+inference core (158/158 tests per its Phase 7 report; verified structures in source). TRYST owns a functional connected-application seam (4 harness adapters, delegator, V17/V18/V20 persistence) but its AI environment UI is Ollama-only and no task-level execution configuration exists.

The dominant gaps for the unified platform are: **(1)** agent profiles as a first-class dimension, **(2)** execution profiles + global/project/task configuration inheritance with immutable snapshots, **(3)** a TRYST-side AI Environment Management UI and per-task selection, **(4)** provider/model parity (cloud providers, honest capability checks), **(5)** aligned credential handling, and **(6)** fixing the DCode endpoint divergence.
