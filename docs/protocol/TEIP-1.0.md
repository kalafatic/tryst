# TEIP — TRYST–EVOLUTION Integration Protocol 1.0

Normative Specification

## 1. Overview and Scope

The TRYST–EVOLUTION Integration Protocol (TEIP 1.0) defines the authoritative, interoperable, wire-level protocol and behavioral contract between:

1. **TRYST**: An Eclipse RCP-based server platform governing users, authentication, projects, Git repositories, isolated workspaces, task lifecycle, build and test verification, code reviews, human approvals, and commit authorization.
2. **EVOLUTION (EVO)**: An AI platform and execution engine governing the Darwin Engine, autonomous AI inference, model selection, prompt generation, tool orchestration, and agent workflows.

TEIP establishes a loosely coupled, distributed integration boundary over standard HTTP/HTTPS and JSON.

### 1.1 Non-Negotiable Boundary Rules
- **No Direct Object Sharing**: TRYST and EVO are independent runtimes. Neither system may load classes or directly manipulate internal memory, database schemas, or session managers of the other.
- **Task Lifecycle Sovereignty**: TRYST is the sole authority for task state, workspace modifications, test execution, code review approval, and Git push operations. A completed EVO execution never marks a TRYST task as completed.
- **Human-in-the-Loop Commit Gating**: EVO and AI agents are strictly forbidden from approving code reviews, creating git commits, or pushing changes to remote repositories.
- **Sandbox Isolation**: EVO never receives raw host filesystem paths or long-lived Git credentials. All file operations occur via scoped, permission-validated MCP tools.

---

## 2. Transport and Wire Format

- **Protocol Name**: TRYST–EVOLUTION Integration Protocol
- **Protocol Identifier**: `TEIP`
- **Protocol Version**: `1.0`
- **Transport**: HTTP/1.1 or HTTP/2 over TLS (HTTPS in production; HTTP allowed for local dev/testing).
- **Payload Encoding**: UTF-8 JSON (`application/json`).
- **Base URI Path**: `/teip/v1`
- **Timestamps**: ISO 8601 UTC string (e.g., `2026-11-06T18:00:00Z`).
- **Identifiers**: Opaque strings (UUIDs or alphanumeric keys).
- **Compatibility**: Receivers must ignore unknown JSON properties (open-world assumption). Major version mismatches must return structured protocol errors (`VERSION_MISMATCH`).

---

## 3. Common Metadata and Error Model

### 3.1 Common Request Envelope
Every mutating or contextual TEIP command carries:
```json
{
  "protocol": "TEIP",
  "version": "1.0",
  "requestId": "req-12345678-abcd-ef01-2345-6789abcdef01",
  "correlationId": "corr-87654321-4321-4321-4321-cba987654321",
  "idempotencyKey": "idem-task-101-stage-analyze",
  "timestamp": "2026-11-06T18:00:00Z",
  "caller": {
    "system": "TRYST",
    "projectId": "proj-abc",
    "taskId": "task-xyz"
  }
}
```

### 3.2 Error Representation
All error responses return standard HTTP status codes along with a stable JSON body:
```json
{
  "error": {
    "code": "CAPABILITY_UNSUPPORTED",
    "message": "Requested capability 'DARWIN_REFACTOR' is not currently registered or available on this node.",
    "requestId": "req-12345678",
    "correlationId": "corr-87654321",
    "retryable": false,
    "details": {
      "supportedCapabilities": ["CODE_ANALYSIS", "CODE_IMPLEMENTATION", "PLAN_SYNTHESIS"]
    }
  }
}
```

Standard error codes:
- `AUTHENTICATION_FAILED` (401, non-retryable)
- `AUTHORIZATION_DENIED` (403, non-retryable)
- `VALIDATION_FAILED` (400, non-retryable)
- `NOT_FOUND` (404, non-retryable)
- `CONFLICT` (409, non-retryable)
- `CAPABILITY_UNSUPPORTED` (422, non-retryable)
- `TIMEOUT` (504, retryable)
- `SERVICE_UNAVAILABLE` (503, retryable)
- `INTERNAL_ERROR` (500, non-retryable)

---

## 4. EVO Endpoints Consumed by TRYST

### 4.1 GET `/teip/v1/health`
Checks runtime availability and protocol compatibility.
- **Request Headers**: `Authorization: Bearer <token>`
- **Response** (`200 OK`):
```json
{
  "status": "UP",
  "protocol": "TEIP",
  "version": "1.0",
  "engineVersion": "0.1.0",
  "nodeId": "evo-worker-01",
  "uptimeSeconds": 86400,
  "timestamp": "2026-11-06T18:00:00Z"
}
```

### 4.2 GET `/teip/v1/capabilities`
Discovers available AI models, modes, and tool capabilities dynamically.
- **Response** (`200 OK`):
```json
{
  "capabilities": [
    {
      "id": "CODE_ANALYSIS",
      "name": "Codebase Analysis & Inspection",
      "supportedModes": ["DEFAULT", "DEEP_REASONING"],
      "requiredScopes": ["read:workspace", "read:task"]
    },
    {
      "id": "CODE_IMPLEMENTATION",
      "name": "Autonomous Code Modification",
      "supportedModes": ["DEFAULT", "INCREMENTAL"],
      "requiredScopes": ["read:workspace", "write:workspace", "execute:build"]
    }
  ],
  "availableModels": [
    {
      "id": "claude-3-5-sonnet",
      "name": "Claude 3.5 Sonnet",
      "contextWindowTokens": 200000,
      "recommended": true
    }
  ]
}
```

### 4.3 POST `/teip/v1/executions`
Creates and initiates an autonomous execution in EVO.
- **Request Headers**:
  - `Content-Type: application/json`
  - `Authorization: Bearer <token>`
  - `Idempotency-Key: <key>`
- **Request Body**:
```json
{
  "protocol": "TEIP",
  "version": "1.0",
  "requestId": "req-987",
  "correlationId": "corr-876",
  "idempotencyKey": "idem-task-100-step-1",
  "timestamp": "2026-11-06T18:00:00Z",
  "caller": {
    "system": "TRYST",
    "projectId": "proj-tryst",
    "taskId": "task-100"
  },
  "capability": "CODE_IMPLEMENTATION",
  "aiMode": "DEFAULT",
  "model": "claude-3-5-sonnet",
  "instruction": "Refactor UserService to use connection pooling.",
  "contextReferences": [
    "src/main/java/eu/kalafatic/tryst/UserService.java"
  ],
  "mcpToolScopes": [
    "read:workspace",
    "read:task",
    "write:workspace"
  ],
  "parameters": {
    "maxTokens": 4096,
    "temperature": 0.2
  }
}
```
- **Response** (`201 Created` or `200 OK` for duplicate idempotency key):
```json
{
  "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
  "evoSessionId": "evo-sess-11223344",
  "status": "QUEUED",
  "createdAt": "2026-11-06T18:00:01Z"
}
```

### 4.4 GET `/teip/v1/executions/{executionId}`
Polls status and execution state.
- **External Execution States**: `QUEUED`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCELLED`.
- **Response** (`200 OK`):
```json
{
  "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
  "status": "RUNNING",
  "phase": "ANALYZING_AST",
  "progress": 0.45,
  "lastSequence": 4,
  "createdAt": "2026-11-06T18:00:01Z",
  "updatedAt": "2026-11-06T18:00:05Z"
}
```

### 4.5 GET `/teip/v1/executions/{executionId}/events?afterSequence={n}&limit={count}`
Retrieves events in strict sequential order for durable replay and reconciliation.
- **Response** (`200 OK`):
```json
{
  "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
  "events": [
    {
      "eventId": "evt-001",
      "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
      "sequence": 1,
      "type": "EXECUTION_STARTED",
      "timestamp": "2026-11-06T18:00:02Z",
      "correlationId": "corr-876",
      "data": { "model": "claude-3-5-sonnet" }
    },
    {
      "eventId": "evt-002",
      "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
      "sequence": 2,
      "type": "TOOL_INVOKED",
      "timestamp": "2026-11-06T18:00:03Z",
      "correlationId": "corr-876",
      "data": { "tool": "read_workspace_file", "path": "pom.xml" }
    }
  ],
  "hasMore": false,
  "latestSequence": 2
}
```

### 4.6 GET `/teip/v1/executions/{executionId}/result`
Retrieves final structured execution output.
- **Response** (`200 OK`):
```json
{
  "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
  "status": "COMPLETED",
  "summary": "Completed UserService connection pool refactoring.",
  "patch": "diff --git a/src/main/java/eu/kalafatic/tryst/UserService.java ...",
  "modifiedFiles": ["src/main/java/eu/kalafatic/tryst/UserService.java"],
  "metrics": {
    "durationMs": 4250,
    "promptTokens": 1280,
    "completionTokens": 450,
    "toolCallsCount": 3
  },
  "completedAt": "2026-11-06T18:00:10Z"
}
```

### 4.7 POST `/teip/v1/executions/{executionId}/cancel`
Aborts an active execution.
- **Response** (`200 OK`):
```json
{
  "executionId": "evo-exec-550e8400-e29b-41d4-a716-446655440000",
  "status": "CANCELLED",
  "message": "Execution cancellation accepted."
}
```

---

## 5. Model Context Protocol (MCP) Over HTTP

TRYST exposes standard JSON-RPC 2.0 Model Context Protocol tools under `/api/mcp` to allow EVO agents to query and interact with task workspaces under strict least-privilege scoping.

### 5.1 JSON-RPC Transport Contract
- **HTTP POST** to `/api/mcp`
- Headers:
  - `Content-Type: application/json`
  - `Authorization: Bearer <tryst-mcp-token>`
  - `X-Execution-Id: <executionId>`

### 5.2 Tool Discovery (`tools/list`)
Request:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}
```
Response:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "get_project_metadata",
        "description": "Returns project configuration and repository branch information",
        "inputSchema": { "type": "object", "properties": { "projectId": { "type": "string" } }, "required": ["projectId"] }
      },
      {
        "name": "list_workspace_files",
        "description": "Lists directory contents within task workspace sandbox",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" }, "path": { "type": "string" } }, "required": ["taskId"] }
      },
      {
        "name": "read_workspace_file",
        "description": "Reads file contents within task workspace sandbox",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" }, "path": { "type": "string" } }, "required": ["taskId", "path"] }
      },
      {
        "name": "search_workspace_code",
        "description": "Searches text patterns within task workspace files",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" }, "query": { "type": "string" } }, "required": ["taskId", "query"] }
      },
      {
        "name": "get_task_context",
        "description": "Returns task title, description, intake instructions, and acceptance criteria",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" } }, "required": ["taskId"] }
      },
      {
        "name": "propose_patch",
        "description": "Applies a unified diff patch to the task workspace sandbox if write permission is granted",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" }, "patch": { "type": "string" } }, "required": ["taskId", "patch"] }
      },
      {
        "name": "run_build_and_test",
        "description": "Runs build verification in workspace sandbox if build permission is granted",
        "inputSchema": { "type": "object", "properties": { "taskId": { "type": "string" } }, "required": ["taskId"] }
      }
    ]
  }
}
```

### 5.3 Tool Invocation (`tools/call`)
Request:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "read_workspace_file",
    "arguments": {
      "taskId": "task-100",
      "path": "src/App.java"
    }
  }
}
```
Response:
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "public class App { ... }"
      }
    ],
    "isError": false
  }
}
```

---

## 6. Security and Containment

1. **Authentication**: All TEIP HTTP exchanges must be authenticated via Bearer tokens.
2. **Path Sanitization**: Absolute paths, `..` segments, symlink escapes, and null-byte injections are strictly rejected.
3. **No Self-Approval**: EVO results are presented to TRYST as candidate proposals. Code reviews must be approved by a human reviewer. Commits and remote pushes must be explicitly authorized by a user with `COMMIT_APPROVED_CHANGES` permission.
4. **Idempotency**: Execution creation and cancellation must be idempotent.
5. **Durable Recovery**: Sequence cursors prevent event loss across network interruptions or process restarts.
