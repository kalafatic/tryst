# TRYST – Asynchronous Coder 2: Test Plan

**Date:** 2026-11-21
**Scope:** Backend server administration, asynchronous worker/pool management, execution statistics, session continuation, project configuration, diff review and iterative coding workflow.

## 1. Strategy

- **Fixture-based tests:** orchestration, persistence, HTTP-surface behavior with a deterministic fake AI (`TestAiFakes.codingAi()`) and temp-directory SQLite databases. These verify the TRYST machinery, not the model.
- **Real-integration tests:** `OpenCodeEndToEndWorkflowTest` executes a real coding task through the real OpenCode CLI and a real Ollama model (environment-gated).
- **Live runtime verification:** manual HTTP checks against a running headless product build.

## 2. Test Inventory

### 2.1 Existing suite (regression)

Full `./mvnw -B -o clean verify` (JDK 21). Baseline: 373 tests. After this stage: **381 tests**.

### 2.2 New tests added in this stage

| Test class | Scenarios | Type |
|---|---|---|
| `AsyncCoder2RegressionTest` (8 tests) | Honest AI failure without provider; `agent_type` round-trip; `execution_result_id` persisted after execution; `repository_mirrors` schema-compliant persistence; project configuration round-trip; intake creates persistent session; pause+resume of an active task; recovery retry re-queues and executes | Fixture |
| `SessionContinuationHttpTest` (1 test) | Scenario B: intake → session; continue → same session/sandbox; accumulated diff contains both iterations; session detail lists both tasks and conversation | Fixture + real git sandbox |
| `OpenCodeEndToEndWorkflowTest` (updated) | Real OpenCode task through HTTP (environment-gated on model availability); HTTP-service restart re-registers routes; honest failure for invalid model | Real integration |
| `TestAiFakes` | Shared deterministic fake AI fixture (replaces the removed "autonomous fallback" reliance in flow tests) | Helper |

### 2.3 Updated tests

- `TaskOrchestratorIntegrationTest`, `AsynchronousTaskExecutionHttpE2ETest`, `EndToEndWorkflowStabilizationTest` (Scenario E): now wire `TestAiFakes.codingAi()` — they previously depended on the removed silent-fabrication fallback.
- `OpenCodeEndToEndWorkflowTest`: environment gate for the required model.

## 3. End-to-End Scenarios

| Scenario | Verification |
|---|---|
| A – New asynchronous task | `AsynchronousTaskExecutionHttpE2ETest` (submission < 2 s, full stage trail to WAITING_FOR_REVIEW, cancellation) + `AsyncCoder2RegressionTest` (execution result binding, session creation) |
| B – Continue an existing session | `SessionContinuationHttpTest` (session identity, workspace reuse, accumulated diff, conversation history) |
| C – Diff review + iterative correction | `IterativeReviewAndReworkWorkflowTest` (comments, blocking comments, rework cycle, revision numbering) + `DiffParserTest` (unified/side-by-side parsing) |
| D – Backend administration | Live runtime checks (see Validation Report §5) + `AdministrationAndOperationalManagementTest` |
| E – Failure & recovery | `AsyncCoder2RegressionTest` (honest AI failure, recovery retry, pause/resume), `EndToEndWorkflowStabilizationTest` (reconciliation to RECOVERY_REQUIRED), `OpenCodeEndToEndWorkflowTest` (invalid model honest failure) |

## 4. Execution Commands

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -B -o clean verify                          # full suite (JDK 21, offline)
./mvnw -B -o clean verify -Dtest=<ClassPattern>    # focused run
```

## 5. Environment-Gated Scenarios

- `OpenCodeEndToEndWorkflowTest.testRealOpenCodeTaskThroughTrystHttpWorkflow` requires an installed Ollama model capable of following coding instructions (default `qwen3-coder-30b-32k:latest`, override with `-Dtryst.test.model=`). When unavailable, the test self-reports **SKIPPED (environment)**. On this host the model is installed but execution exceeds the 15-minute poll deadline on current hardware → recorded **BLOCKED (hardware)** in the validation report.
