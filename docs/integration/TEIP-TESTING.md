# TEIP 1.0 — Test Coverage & Verification Guide

## 1. Overview

This document details the automated test suite and verification procedures for TEIP 1.0 in TRYST.

---

## 2. Test Scenarios and Suite Breakdown

### 2.1 Protocol Serialization & DTO Contracts (`TeipProtocolContractTest`)
- Validates request/response serialization against the normative TEIP 1.0 JSON schemas.
- Verifies open-world tolerance (ignoring unknown JSON fields).
- Validates standard error code formatting and parsing.

### 2.2 Wire Client Integration (`TeipClientTest`)
- Mock HTTP server validating all consumed endpoints:
  - `GET /teip/v1/health`
  - `GET /teip/v1/capabilities`
  - `POST /teip/v1/executions`
  - `GET /teip/v1/executions/{id}`
  - `GET /teip/v1/executions/{id}/events`
  - `GET /teip/v1/executions/{id}/result`
  - `POST /teip/v1/executions/{id}/cancel`
- Validates idempotency key transmission and Bearer token headers.
- Tests sequence gap recovery and pagination (`afterSequence`).
- Tests retry behavior on 503/timeout and fail-fast on 401/403.

### 2.3 Persistence & Recovery (`EvoPersistenceAndRecoveryTest`)
- Schema migration V13 integrity.
- Execution mapping persistence between `taskId` and `evoExecutionId`.
- Event deduplication on `eventId`.
- Restart recovery and cursor reconciliation.

### 2.4 MCP Scoped Tools & Security (`McpServerSecurityTest`)
- JSON-RPC 2.0 dispatching (`tools/list` and `tools/call`).
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

### 2.5 Lifecycle & Human-in-the-Loop Invariants (`TeipLifecycleIntegrationTest`)
- Verification that completed EVO execution does not complete the TRYST task.
- Verification that EVO cannot approve reviews, commit, or push.
- Build and test validation executes independently within TRYST.

---

## 3. Running the Verification Suite

Run all tests via Maven:
```bash
xvfb-run mvn clean verify
```

Or run targeted integration tests:
```bash
xvfb-run mvn test -Dtest=TeipProtocolContractTest,TeipClientTest,EvoPersistenceAndRecoveryTest,McpServerSecurityTest,TeipLifecycleIntegrationTest
```
