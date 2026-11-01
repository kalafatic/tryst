# TRYST Administration Architecture

## 1. Overview
TRYST provides an integrated administration platform exposed through authenticated REST endpoints (`/api/admin/*`) and rendered dynamically inside the TRYST Web Application Single Page Application.

## 2. Administrative REST Endpoints
- `GET /api/admin/overview`: Summary stats for users, sessions, projects, tasks, audit records, CPU/heap utilization, and AI inference health.
- `GET /api/admin/users`: List users and roles with account status.
- `POST /api/admin/users`: Create a new user with role and credentials.
- `POST /api/admin/users/{id}/(lock|unlock|activate|deactivate)`: Account lifecycle control.
- `GET /api/admin/sessions`: List active and historical user sessions.
- `POST /api/admin/sessions/{id}/revoke`: Explicit session invalidation.
- `GET /api/admin/processes`: Inspect managed OS child processes (PIDs, commands, durations, statuses).
- `POST /api/admin/processes/{id}/kill`: Graceful and forced process termination.
- `GET /api/admin/telemetry`: Query recent system telemetry snapshots.
- `GET /api/admin/audit`: Search and paginate durable audit event logs.
- `GET /api/admin/logs`: Access centralized application and diagnostic logs with sensitive information redacted.
- `GET /api/admin/database`: Inspect database path, file size, journal mode, and schema version.
- `POST /api/admin/database/backup`: Create atomic SQLite `VACUUM INTO` snapshots.
- `GET /api/admin/config`: Inspect operational settings.
- `POST /api/admin/config`: Dynamically update active model, timeout limits, and inference endpoints.

## 3. Web Administration UX
The Single Page Application features an operational navigation switch between the **Code Review Center** and the **Administration & Observability Center**:
- Grid statistics cards: Active Users, Active Sessions, Total Projects, Total Tasks, Audit Records.
- Interactive tabbed sections for Users & Roles, Sessions, OS Processes, Telemetry, Audit Logs, Diagnostics, and Database maintenance.
- Real-time action triggers with visual feedback for backups, user locks, and terminations.
