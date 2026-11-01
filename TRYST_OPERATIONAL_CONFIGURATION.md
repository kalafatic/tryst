# TRYST Operational Configuration

## 1. Overview
TRYST configuration is managed centrally via `ConfigurationService` and persisted authoritatively in `~/.tryst/tryst.properties`.

## 2. Configuration Parameters
- **AI Infrastructure**:
  - `ollama.endpoint`: HTTP endpoint of local Ollama server (default `http://localhost:11434`).
  - `ollama.model`: Active LLM (default `codellama`).
  - `ai.maxContextTokens`: Maximum context token budget (default `4096`).
  - `ai.maxOutputTokens`: Maximum response token limit (default `2048`).
- **Execution Limits**:
  - `execution.timeoutSeconds`: Request timeout duration (default `60`).
  - `execution.maxIterations`: Maximum iteration cycles for agent tools (default `5`).
  - `execution.maxRetryAttempts`: Max retries on transient errors (default `3`).
  - `security.sandboxEnabled`: Strict filesystem sandbox enforcement (default `true`).
- **Server Communication**:
  - `server.host`: Bind address (default `127.0.0.1`).
  - `server.http.port`: Embedded HTTP server port (default `8080`).
  - `server.tcp.port`: Native TCP command interface port (default `9090`).

## 3. Dynamic Configuration API
- Administrators can inspect operational settings via `GET /api/admin/config`.
- Dynamically alter active model and Ollama endpoint via `POST /api/admin/config` without restarting the process.
- Changes are validated immediately against invariant rules and saved to disk.
