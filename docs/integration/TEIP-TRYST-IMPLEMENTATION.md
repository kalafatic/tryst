# TEIP 1.1 — TRYST Implementation Architecture

> **Status note:** describes the TRYST-side **1.1** implementation (WP-3.2/WP-3.4, Phase 3). The
> canonical specification is `docs/protocol/TEIP-1.1.md` (this repository's
> `docs/protocol/TEIP-1.0.md` is only a pointer to the canonical copy in the EVO repository).
> Conformance state:
> - Client accepts HTTP 200/201/202 for create-execution (202 canonical for new, 200 for
>   idempotent duplicates).
> - Client emits canonical request fields (`mode`, `grantedToolScopes`, object
>   `contextReferences`, `version: "1.1"`) and the `Idempotency-Key` header.
> - Client parses the canonical `nextSequence` events cursor (`latestSequence` accepted as
>   deprecated alias) and the canonical top-level error envelope.
> - Canonical default EVO endpoint `http://localhost:28080` (was `39080` in 1.0 — defect fixed).
> - MCP tool plane (`/api/mcp`) enforces `Authorization: Bearer <tryst-mcp-token>` and
>   `X-Execution-Id`, requires the MCP `initialize` handshake (protocol version `2024-11-05`)
>   and derives granted scopes from the persisted execution record
>   (`evo_executions.granted_scopes`, migration V16).

## 1. Architectural Strategy

The TRYST implementation of the TRYST–EVOLUTION Integration Protocol (TEIP 1.1) bridges TRYST's autonomous development orchestrator and human-in-the-loop governance with EVO's advanced Darwin AI engine while upholding strict isolation.

### 1.1 OSGi Bundle Separation
To satisfy the constraint that TRYST Core must not depend on EVO classes and remains fully operational when EVO is offline, the integration is packaged in a dedicated bundle:
`eu.kalafatic.tryst.evo.integration`

Dependency hierarchy:
- `eu.kalafatic.tryst.core`: Logging, events, port management, lifecycle, configuration.
- `eu.kalafatic.tryst.domain`: Domain models, repositories, task/review lifecycle, permissions, tools.
- `eu.kalafatic.tryst.infrastructure`: SQLite persistence, HTTP server, Git service.
- `eu.kalafatic.tryst.evo.integration`: TEIP client, DTOs, execution tracking, MCP server, integration service.

Core and Domain bundles have **zero** compile-time references to `eu.kalafatic.tryst.evo.integration` or `eu.kalafatic.evolution.*`.

---

## 2. Components and Responsibilities

### 2.1 Wire Layer & Client (`eu.kalafatic.tryst.evo.integration.client`)
- `TeipClient`: Implemented using standard Java 21 `java.net.http.HttpClient`. Handles all HTTP methods, timeout handling, bearer token authentication, request/response JSON serialization, cursor pagination for events and canonical TEIP 1.1 error-envelope parsing.
- Records/DTOs: Fully models the canonical TEIP 1.1 specification (`TeipHealthResponse`, `TeipCapabilitiesResponse`, `TeipExecutionRequest`, `TeipExecutionResponse`, `TeipExecutionResult`, `TeipEvent`, `TeipEventsResponse`, `TeipError`).

### 2.2 Integration Service (`eu.kalafatic.tryst.evo.integration.service`)
- `EvoIntegrationService`: Cohesive facade managing:
  - Configuration loading and persistence (`EvoConfiguration`).
  - Active and historical execution tracking (`EvoExecution`).
  - Durable event reception, sequence gap detection, and deduplication (`EvoExecutionEvent`).
  - Execution reconciliation upon TRYST startup or network disruption.
  - Optional execution delegation for `TaskWorker`.
- Granted MCP tool scopes are persisted on the execution record (`granted_scopes`) as the
  authorization source of truth for the MCP tool plane.

### 2.3 Scoped MCP Server (`eu.kalafatic.tryst.evo.integration.mcp`)
- Implements JSON-RPC 2.0 endpoint over HTTP (`/api/mcp`).
- Requires and enforces `Authorization: Bearer <tryst-mcp-token>` and `X-Execution-Id`
  (TEIP 1.1 §8.2). When no shared token is configured, only loopback callers are admitted
  (explicit development fallback).
- Requires the MCP `initialize` handshake (protocol version `2024-11-05`) before
  `tools/list`/`tools/call`.
- Tools exposed:
  - `get_project_metadata`: Inspects project settings, branch, repository URL.
  - `list_workspace_files`: Lists directory contents within task workspace sandbox.
  - `read_workspace_file`: Reads files safely within task workspace sandbox.
  - `search_workspace_code`: Text grep within workspace files.
  - `get_task_context`: Returns task title, description, instructions, acceptance criteria.
  - `propose_patch`: Applies unified diff patch to workspace (guarded by `write:workspace` permission).
  - `run_build_and_test`: Runs build verification in workspace (guarded by `execute:build` permission).

All tool invocations are audited via `PermissionManager` and strictly validated against sandbox path traversal.

### 2.4 Task Lifecycle Gating
- TRYST `TaskStatus` remains authoritative (`CREATED`, `READY`, `QUEUED`, `CLAIMED`, `CLONING`, `ANALYZING`, `PLANNING`, `IMPLEMENTING`, `BUILDING`, `TESTING`, `WAITING_FOR_REVIEW`, `APPROVED`, `COMMITTING`, `COMPLETED`, `FAILED`, `CANCELLED`).
- External EVO execution states (`QUEUED`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCELLED`) are stored in `evo_executions` and never injected into `TaskStatus`.
- Code review, approval, and Git push gating remain strictly human-controlled.

---

## 3. Database Schema (Migrations V13 and V16)

Schema migration V13 in `SchemaMigrationService` establishes:
- `evo_configurations`: Persists endpoint URL, enabled status, auth token, timeouts.
- `evo_executions`: Persists execution mappings (`id`, `tryst_task_id`, `tryst_session_id`, `evo_execution_id`, `capability`, `ai_mode`, `status`, `idempotency_key`, `created_at`, `updated_at`).
- `evo_execution_events`: Persists streamed events (`event_id`, `execution_id`, `sequence`, `event_type`, `correlation_id`, `timestamp`, `data`) with unique deduplication constraint on `event_id`.

Schema migration V16 (TEIP 1.1) adds:
- `evo_executions.granted_scopes`: Persisted granted MCP tool scopes (comma-separated), the
  authorization record the `/api/mcp` tool plane derives caller scopes from.
