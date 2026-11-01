# TRYST Audit and Logging Architecture

## 1. Overview
Audit and logging infrastructure guarantees durability across application restarts and redaction of sensitive credentials.

## 2. Persistent Audit Log (`AuditEventRepository` & `SqliteAuditEventRepository`)
- **Storage**: SQLite `audit_events` relational table.
- **Attributes**: `id`, `event_time`, `actor_id`, `action`, `target_type`, `target_id`, `project_id`, `task_id`, `outcome`, `details_json`.
- **Recorded Actions**:
  - `LOGIN`, `LOGOUT`, `ADMIN_BOOTSTRAP`, `ADMIN_ACCESS_DENIED`, `ACCOUNT_LOCK`.
  - `USER_CREATE`, `USER_UPDATE`, `ROLE_CHANGE`.
  - `PROJECT_ACCESS`, `PROJECT_CREATE`, `WORKSPACE_LOCK`.
  - `TASK_QUEUE`, `TASK_START`, `TASK_COMPLETE`, `TASK_FAIL`.
  - `REVIEW_APPROVE`, `REVIEW_REJECT`, `COMMIT_AUTHORIZED`.
  - `PROCESS_EXECUTE`, `PROCESS_KILL`.
- **Durability**: Survives application restarts; tested and verified independently.

## 3. Centralized Diagnostic Logging (`LogDiagnosticService`)
- Provides an in-memory ring-buffer for application diagnostics (max 1000 entries).
- Supports filtering by severity level, component name, and free-text search.
- **Sensitive Value Redaction**: Automatically redacts passwords, secrets, and bearer tokens using regex masking (`token=[REDACTED]`).
