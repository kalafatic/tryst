# TRYST Operational and Deployment Guide

**Document Identifier:** `TRYST-OPS-DEPLOY-001`
**Version:** 1.0.0
**Target Environment:** Java 21, Eclipse Equinox / OSGi 2024-03, Linux x86_64, Windows, macOS

---

## 1. Executive Overview

TRYST is a self-hosted, asynchronous autonomous software development and code review platform inspired by Google Jules. Built natively on Eclipse RCP and Equinox OSGi, TRYST provides an authoritative server-side administration and execution runtime, integrated SQLite database, local Ollama AI model integration, and a decoupled modern Web interface for human-in-the-loop code review and operations management.

This guide details the deployment, runtime configuration, observability, health supervision, and operational administration of the platform.

---

## 2. System Requirements & Prerequisites

### 2.1 Hardware Requirements
- **CPU:** 4+ physical cores (8+ cores recommended for concurrent builds and local LLM execution).
- **RAM:** Minimum 8 GB available RAM (16 GB+ recommended when hosting local Ollama models).
- **Disk:** Fast SSD with at least 20 GB free space for repository sandboxes, SQLite WAL, and build caches.

### 2.2 Software Prerequisites
- **Java Runtime:** Eclipse Temurin or OpenJDK 21 LTS (64-bit).
- **Version Control:** `git` (v2.30+) installed and reachable in `PATH`.
- **Display Server (Linux headless environments):** `xvfb` if launching GUI tests or workbench plugins.
- **AI Engine (Optional / Local):** Ollama running locally at `http://localhost:11434`.

---

## 3. Product Build & Packaging

Build the complete product from the repository root using Apache Maven and Tycho:

```bash
# Clean build, run integration tests, and materialize the RCP product distribution
mvn clean verify
```

The build produces deployable runtime distributions under:
- `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id-linux.gtk.x86_64.tar.gz`
- `product/eu.kalafatic.tryst.product/target/products/eu.kalafatic.tryst.product.id/`

---

## 4. Deployment & Execution Modes

### 4.1 Server Desktop Mode (Default)
In standard desktop mode, TRYST launches the SWT workbench, status views, embedded browser, and background services:

```bash
./tryst
```

### 4.2 Headless Server Mode
For production headless servers (Docker containers, Linux systemd services):

```bash
./tryst -headless -consoleLog -nosplash
# or using JVM system property:
./tryst -vmargs -Dtryst.headless=true -Dtryst.web.autoOpen=false
```

---

## 5. Configuration & Environment Variables

| Variable / Property | Description | Default |
|---|---|---|
| `TRYST_HTTP_PORT` / `tryst.http.port` | Preferred internal HTTP listener port | `8080` (falls back to range 8080-8180 or 0) |
| `TRYST_ADMIN_PASSWORD` | Initial administrator password on first boot | Setup token generated if unset |
| `TRYST_OLLAMA_ENDPOINT` | Local or remote Ollama HTTP endpoint | `http://localhost:11434` |
| `TRYST_WORKSPACE_DIR` | Base directory for workspaces and sandboxes | `~/.tryst/workspace` |
| `TRYST_DB_PATH` | Path to the SQLite database file | `~/.tryst/tryst.db` |
| `tryst.web.autoOpen` | Automatically open browser to `/tryst` on startup | `true` |

---

## 6. Administration & Operational Architecture

The administration subsystem exposes 17 authoritative management domains accessible via the web interface (`/tryst`) and protected REST API endpoints (`/api/admin/*` requiring `ADMIN` role):

1. **Server Status (`/api/admin/server`):** Overall system readiness and component-level health (Database, HTTP, AI).
2. **OSGi Bundles (`/api/admin/bundles`):** Real-time bundle identifiers, symbolic names, versions, and lifecycle states (`ACTIVE`, `RESOLVED`, etc.).
3. **Registered Services (`/api/admin/services`):** Discovered services and operational controllers for starting/stopping background workers (`telemetry`, `data-retention`, `task-orchestrator`).
4. **HTTP Server Status (`/api/admin/http`):** Listener state, actual bound port, host binding, and canonical routing endpoint.
5. **SQLite Status & Integrity (`/api/admin/database`):** Database file path, file size, schema version, PRAGMA consistency checks (`integrity_check`, `quick_check`, `foreign_key_check`), and snapshot backup triggers.
6. **AI Provider Connectivity (`/api/admin/ai`):** Ollama status, endpoint availability, request latency, and health check diagnostics.
7. **AI Model Publication (`/api/admin/models`):** Catalog of registered AI models, parameter sizes, and role restrictions.
8. **Task Supervision (`/api/admin/tasks`):** Active and recovery-required tasks, task cancellation (`POST /api/admin/tasks/{id}/cancel`), and execution context inspections (`GET /api/admin/tasks/{id}/context`).
9. **Worker Utilization (`/api/admin/workers`):** Thread capacity, active worker threads, queued tasks, and real-time utilization percentages.
10. **Workspaces & Sandboxes (`/api/admin/workspaces`):** Multi-tenant sandboxes, paths, branch mappings, and isolation states.
11. **Managed OS Processes (`/api/admin/processes`):** Active process tracking, bounded stdout/stderr streaming inspection (`GET /api/admin/processes/{id}/output`), and controlled termination.
12. **Git Subsystem (`/api/admin/git`):** Git binary availability, sandbox isolation, and remote VCS credentials.
13. **Build & Quality Assurance (`/api/admin/builds`):** Supported build technologies (Maven, Gradle, npm, Cargo, Python, Custom).
14. **User Sessions (`/api/admin/sessions`):** Active bearer sessions, client IPs, expiration dates, and immediate revocation.
15. **User Roles & RBAC (`/api/admin/users`):** User management, locking/unlocking, account activation, and role assignments (`POST /api/admin/users/{id}/role`).
16. **Audit & Security Log (`/api/admin/audit`):** Immutable event log of security decisions, permission validations, and admin actions.
17. **Structured Diagnostics (`/api/admin/logs`):** Ring-buffer log search, filtering by level/component, and correlation ID tracing.

---

## 7. Observability & Troubleshooting

### 7.1 Structured Correlation Tracing
All HTTP requests support the `X-Correlation-Id` header. The server propagates this correlation ID through thread-local scopes (`CorrelationContext`) to asynchronous task execution workers and echoes it back in HTTP response headers.

Log format:
```
[CallerClass] [corr:<correlationId>] [task:<taskId>] <log message>
```

### 7.2 Database Consistency Verification
Run database integrity checks without downtime:
```bash
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" http://localhost/tryst/api/admin/database/integrity
```

### 7.3 Data Retention & Compaction
Trigger retention policy pruning (expired sessions, old telemetry snapshots, historical task events):
```bash
curl -X POST -H "Authorization: Bearer <ADMIN_TOKEN>" http://localhost/tryst/api/admin/database/retention
```

---

## 8. Graceful Shutdown & Recovery

When shutting down the platform (`SIGTERM` or UI exit):
1. The HTTP and TCP server socket listeners close immediately to reject new connections.
2. The `TaskOrchestrator` stops accepting new queued tasks and cancels active worker contexts.
3. Managed child processes spawned for builds or Git clones are terminated cleanly.
4. Active workspaces with uncommitted work are preserved in `WAITING_FOR_REVIEW` or marked `RECOVERY_REQUIRED` for deterministic resumption on restart.
5. SQLite executes a clean WAL checkpoint before closing connections.
