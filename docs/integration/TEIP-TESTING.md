# TEIP 1.1 — Test Coverage & Verification Guide (TRYST side)

> **Status note:** describes the **1.1** test suite (WP-3.2/WP-3.4/WP-3.5, Phase 3). The
> canonical 1.1 specification lives in the EVO repository (`docs/protocol/TEIP-1.1.md`); this
> repository's `docs/protocol/TEIP-1.0.md` is only a pointer.

## 1. Overview

This document details the automated test suite and verification procedures for the TRYST side
of TEIP 1.1.

---

## 2. Test Scenarios and Suite Breakdown

### 2.1 Protocol Serialization & DTO Contracts (`TeipProtocolContractTest`)
- Validates canonical TEIP 1.1 request serialization (`mode`, `grantedToolScopes`, object
  `contextReferences`, `version: "1.1"`; deprecated aliases are NOT emitted).
- Verifies open-world tolerance (ignoring unknown JSON fields).
- Validates canonical top-level error envelope parsing.
- Validates `nextSequence` cursor parsing and the `latestSequence` deprecated alias fallback.

### 2.2 Wire Client Integration (`TeipClientTest`)
- Mock HTTP server validating all consumed endpoints:
  - `GET /teip/v1/health`
  - `GET /teip/v1/capabilities`
  - `POST /teip/v1/executions` (canonical **202** new / **200** duplicate both accepted;
    201 tolerated)
  - `GET /teip/v1/executions/{id}`
  - `GET /teip/v1/executions/{id}/events`
  - `GET /teip/v1/executions/{id}/result`
  - `POST /teip/v1/executions/{id}/cancel`
- Validates `Idempotency-Key` header transmission and Bearer token headers.
- Tests sequence gap recovery and pagination (`afterSequence`).
- Validates canonical error-envelope parsing on non-2xx responses.

### 2.3 Persistence & Recovery (`EvoPersistenceAndRecoveryTest`, `SchemaMigrationTest`, `DatabaseInitializationTest`)
- Schema migration chain integrity through V16 (`evo_executions.granted_scopes`).
- Execution mapping persistence between `taskId` and `evoExecutionId`.
- Event deduplication on `eventId`.
- Restart recovery and cursor reconciliation.

### 2.4 MCP Scoped Tools & Security (`McpServerSecurityTest`)
- JSON-RPC 2.0 dispatching (`initialize`, `tools/list` and `tools/call`).
- MCP lifecycle: `initialize` (protocol version `2024-11-05`) required before tool methods;
  unsupported protocol versions rejected; uninitialized executions rejected.
- Scoped tools validation:
  - `get_project_metadata`
  - `list_workspace_files`
  - `read_workspace_file`
  - `search_workspace_code`
  - `get_task_context`
  - `propose_patch`
  - `run_build_and_test`
- Rejection of path traversal (`../etc/passwd`, absolute paths).
- Validation that write and build operations fail when scope is read-only.
- Audit trail verification via `PermissionManager`.

The `/api/mcp` HTTP layer additionally enforces (verified in `McpHttpHandler`):
- `Authorization: Bearer <tryst-mcp-token>` (loopback-only dev fallback when no token is configured).
- Required `X-Execution-Id` resolved against the persisted execution record
  (`evo_executions`, including `granted_scopes`); unknown or terminal executions refused.

### 2.5 Lifecycle & Human-in-the-Loop Invariants (`TeipLifecycleIntegrationTest`)
- Verification that completed EVO execution does not complete the TRYST task.
- Verification that EVO cannot approve reviews, commit, or push.
- Build and test validation executes independently within TRYST.

---

## 3. Running the Verification Suite

Run all tests via Maven (requires JDK 21 and Maven 3.9+; the tests module uses
tycho-surefire with `useUIHarness=false`):

```bash
xvfb-run mvn -o integration-test -pl tests/eu.kalafatic.tryst.tests
```

Or run targeted TEIP tests:

```bash
xvfb-run mvn -o integration-test -pl tests/eu.kalafatic.tryst.tests \
  -Dtest='TeipClientTest,TeipProtocolContractTest,McpServerSecurityTest,EvoPersistenceAndRecoveryTest,TeipLifecycleIntegrationTest,SchemaMigrationTest'
```

## 4. Cross-Repository Conformance (WP-3.5)

The EVO-side live wire tests live in the EVO repository
(`eu.kalafatic.evolution.controller.tests`, package `...teip`): `TeipApiEndpointTest` boots a
real `EvolutionServer` and exercises the canonical 1.1 endpoints (202/200, aliases,
`VERSION_MISMATCH`, 409 `details.currentStatus`, auth enforcement, idempotent cancel),
`TeipDtoSerializationTest`, `TeipExecutionManagerTest` and `TrystMcpClientTest` (required
headers, initialize handshake, fail-closed scope mapping).

Live delegated execution end-to-end:
1. Start EVO with a configured shared token (`evo.teip.auth.token`) on port 28080.
2. In TRYST, configure the EVO endpoint (`http://localhost:28080`) and the same token via the
   AI Integration Center.
3. Create an execution through TRYST (`/api/integration/evo/executions`) and observe the
   canonical 202 accepted response, persisted execution record, event replay via
   `nextSequence`, and the TRYST MCP tool plane authorizing EVO's `X-Execution-Id` calls
   against `evo_executions.granted_scopes`.
