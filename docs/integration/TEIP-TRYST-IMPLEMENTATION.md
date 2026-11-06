# TEIP 1.0 — TRYST Implementation Architecture

## 1. Architectural Strategy

The TRYST implementation of the TRYST–EVOLUTION Integration Protocol (TEIP 1.0) bridges TRYST's autonomous development orchestrator and human-in-the-loop governance with EVO's advanced Darwin AI engine while upholding strict isolation.

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
- `TeipClient`: Implemented using standard Java 21 `java.net.http.HttpClient`. Handles all HTTP methods, timeout handling, bearer token authentication, request/response JSON serialization, and cursor pagination for events.
- Records/DTOs: Fully models TEIP 1.0 specifications (`TeipHealthResponse`, `TeipCapabilitiesResponse`, `TeipExecutionRequest`, `TeipExecutionResponse`, `TeipExecutionResult`, `TeipEvent`, `TeipError`).

### 2.2 Integration Service (`eu.kalafatic.tryst.evo.integration.service`)
- `EvoIntegrationService`: Cohesive facade managing:
  - Configuration loading and persistence (`EvoConfiguration`).
  - Active and historical execution tracking (`EvoExecution`).
  - Durable event reception, sequence gap detection, and deduplication (`EvoExecutionEvent`).
  - Execution reconciliation upon TRYST startup or network disruption.
  - Optional execution delegation for `TaskWorker`.

### 2.3 Scoped MCP Server (`eu.kalafatic.tryst.evo.integration.mcp`)
- Implements JSON-RPC 2.0 endpoint over HTTP (`/api/mcp`).
- Tools exposed:
  - `get_project_metadata`: Inspects project settings, branch, repository URL.
  - `list_workspace_files`: Lists directory contents within task workspace sandbox.
  - `read_workspace_file`: Reads files safely within task workspace sandbox.
  - `search_workspace_code`: Text grep within workspace files.
  - `get_task_context`: Returns task title, description, instructions, acceptance criteria.
  - `propose_patch`: Applies unified diff patch to workspace (guarded by `WORKSPACE_WRITE` permission).
  - `run_build_and_test`: Runs build verification in workspace (guarded by `EXECUTE_BUILD` permission).

All tool invocations are audited via `PermissionManager` and strictly validated against sandbox path traversal.

### 2.4 Task Lifecycle Gating
- TRYST `TaskStatus` remains authoritative (`CREATED`, `READY`, `QUEUED`, `CLAIMED`, `CLONING`, `ANALYZING`, `PLANNING`, `IMPLEMENTING`, `BUILDING`, `TESTING`, `WAITING_FOR_REVIEW`, `APPROVED`, `COMMITTING`, `COMPLETED`, `FAILED`, `CANCELLED`).
- External EVO execution states (`QUEUED`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCELLED`) are stored in `evo_executions` and never injected into `TaskStatus`.
- Code review, approval, and Git push gating remain strictly human-controlled.

---

## 3. Database Schema (Migration V13)

Schema migration V13 in `SchemaMigrationService` establishes:
- `evo_configurations`: Persists endpoint URL, enabled status, auth token, timeouts.
- `evo_executions`: Persists execution mappings (`id`, `tryst_task_id`, `tryst_session_id`, `evo_execution_id`, `capability`, `ai_mode`, `status`, `idempotency_key`, `created_at`, `updated_at`).
- `evo_execution_events`: Persists streamed events (`event_id`, `execution_id`, `sequence`, `event_type`, `correlation_id`, `timestamp`, `data`) with unique deduplication constraint on `event_id`.
