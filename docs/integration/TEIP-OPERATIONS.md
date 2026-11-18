# TEIP 1.1 — Operational & Deployment Guide

> **Status note:** describes the **1.1** implementation (WP-3.2, Phase 3). Canonical
> specification: `docs/protocol/TEIP-1.1.md` (pointer in this repository). The canonical
> default EVO endpoint is `http://localhost:28080` (the historical 1.0 default of `39080`
> was a defect — fixed in WP-3.2).

## 1. Overview

This guide describes configuration, activation, security tokens, diagnostics, and operational troubleshooting for the TRYST–EVOLUTION Integration Protocol (TEIP 1.1).

---

## 2. Configuration & Activation

The TEIP integration can be managed via the Web UI (under **AI Integration Center**) or programmatically via `/api/integration/evo/*` endpoints. Connected coding applications (EVO via TEIP, OpenCode) are managed via `/api/integration/applications`.

### 2.1 Configuration Properties
- `endpoint`: Base URL of the EVO service (default `http://localhost:28080`).
- `authToken`: Bearer authentication token for authenticating TRYST to EVO; the same token is enforced as the TRYST MCP token for EVO→TRYST tool calls.
- `enabled`: Boolean toggle indicating whether EVO integration is active.
- `timeoutSeconds`: HTTP request and execution wait timeout (default: 60s).
- `maxRetries`: Maximum retry attempts for transient network errors (default: 3).

### 2.2 Enabling the Integration via API
```bash
curl -X POST http://localhost:38080/tryst/api/integration/evo/config \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TRYST_ADMIN_TOKEN>" \
  -d '{
    "endpoint": "http://127.0.0.1:28080",
    "authToken": "secret-evo-token",
    "enabled": true,
    "timeoutSeconds": 60,
    "maxRetries": 3
  }'
```

### 2.3 Validating Connection & Health
```bash
curl -X GET http://localhost:38080/tryst/api/integration/evo/health \
  -H "Authorization: Bearer <TRYST_USER_TOKEN>"
```
Returns:
```json
{
  "connected": true,
  "status": "UP",
  "protocol": "TEIP",
  "version": "1.1",
  "engineVersion": "0.1.0",
  "nodeId": "evo-worker-01"
}
```

### 2.4 Connected Applications (Phase 3, A-14 / WP-3.10)

All connected-application endpoints require authentication with the
`MANAGE_AGENTS` permission (WP-3.10): every request must carry a valid session bearer token
(`Authorization: Bearer <token>`); administrators always hold the permission. Requests
without credentials are rejected with 401, authenticated principals without the permission
with 403. The explicit emergency development mode (`tryst.dev.mode=true`) retains the
platform-wide unauthenticated DEV bypass.

```bash
# List registered applications and their connection status
curl -X GET http://localhost:38080/tryst/api/integration/applications \
  -H "Authorization: Bearer <TRYST_ADMIN_TOKEN>"

# Select the coding harness used for delegated implementation
curl -X POST http://localhost:38080/tryst/api/integration/applications/select \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TRYST_ADMIN_TOKEN>" \
  -d '{"applicationId": "opencode"}'

# Run a live connection test
curl -X POST http://localhost:38080/tryst/api/integration/applications/opencode/test \
  -H "Authorization: Bearer <TRYST_ADMIN_TOKEN>"

# Update OpenCode connection configuration (model uses OpenCode's provider/model format;
# bare ids are normalized to the local ollama provider by the adapter)
curl -X POST http://localhost:38080/tryst/api/integration/applications/opencode/config \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TRYST_ADMIN_TOKEN>" \
  -d '{"agent": "build", "model": "ollama/qwen3-coder-30b-32k:latest"}'
```


---

## 3. Web UI Integration Center

Navigate to `http://localhost:38080/tryst` and click **AI Integration Center** in the top navigation bar.

Key Operational Views:
1. **Connection & Configuration Card**: Manage endpoint URL, auth token, and enable/disable toggle. Perform live ping/health checks.
2. **Capabilities & Models Card**: View discovered EVO AI capabilities, supported modes, and available models.
3. **TRYST MCP Tools Scopes Card**: Review the list of MCP tools exposed to EVO, their permissions, and sandbox constraints.
4. **Active & Historical Executions Table**: Inspect execution IDs, associated TRYST tasks, capabilities, statuses, and execution timestamps. Includes real-time cancel button for active executions.
5. **Execution Detail & Events Modal**: View real-time sequential event stream, tool invocations, AST analyses, and structured patch proposals.

---

## 4. Diagnostics & Troubleshooting

### 4.1 EVO Disconnection or Downtime
When EVO is offline:
- Health check returns `connected: false` with detailed diagnostic error message.
- TRYST task execution falls back gracefully to standard local AI or indicates integration unavailability without corrupting task state.
- Existing executions remain safely persisted in `evo_executions`.

### 4.2 Sequence Gaps and Event Replay
If network disconnection causes dropped event deliveries:
- The integration client checks `afterSequence` during periodic poll.
- Missing events are replayed from EVO's `/teip/v1/executions/{id}/events?afterSequence={n}` endpoint.
- Database enforces unique constraint on `event_id` preventing duplicate insertion.

### 4.3 Log Correlation
Logs emitted by the TEIP subsystem are tagged with:
- Correlation context: `[corr:<correlationId>] [task:<taskId>]`
- Log class: `TeipClient`, `EvoIntegrationService`, or `McpServer`.
