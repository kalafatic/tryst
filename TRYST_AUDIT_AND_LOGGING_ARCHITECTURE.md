# TRYST Audit and Logging Architecture

## 1. Overview
Audit and logging infrastructure guarantees durability across application restarts, strict redaction of sensitive credentials, unified structured diagnostic logging across all OSGi plug-ins, and native real-time console observability integrated into the default RCP perspective.

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

## 3. Centralized Diagnostic Logging (`TrystLog` & `LogDiagnosticService`)
- Central logging facility rooted in `eu.kalafatic.tryst.core.log`:
  - Canonical severity levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` (`LogLevel`).
  - Automatic thread-local correlation tracking via `CorrelationContext` (`correlationId` and `taskId`).
  - Sensitive credential redaction via `LogSanitizer` (masks passwords, bearer tokens, API keys, private keys).
  - Bounded in-memory circular buffer (default 2,000 entries) retrievable via `TrystLog.getRecentEntries()`.
  - Non-blocking asynchronous persistent file appender with size-based rotation and backup retention (`AsyncFileLogAppender`) under `.tryst/logs/tryst.log`.
  - Real-time subscriber listener API (`LogListener`) allowing UI and monitoring subsystems to stream events live.
  - Graceful shutdown hook in `TrystApplicationPlugin` and `ApplicationLifecycleCoordinator`.

## 4. Professional TRYST Console View (`ConsoleOutputView`)
- Integrated directly into the default perspective (`TrystMainPerspective`), docked in the bottom region (weight 70% top, 30% bottom).
- Native Eclipse RCP / SWT / JFace implementation managed by `ConsoleOutputController`.
- Operational capabilities:
  - Live log streaming with batched, thread-safe UI updates (`Display.asyncExec`).
  - Severity level filtering (ALL, TRACE, DEBUG, INFO, WARN, ERROR) via toolbar combo.
  - Substring search text field matching messages, task IDs, and correlation IDs.
  - Pause and resume live log updates.
  - Clear console display action (without clearing persistent files or history).
  - Copy selected message or full structured details with formatted stack traces to clipboard.
  - Color-accented visual indicators for ERROR (red), WARN (amber), INFO (blue), DEBUG, and TRACE.
  - Contextual navigation to associated tasks in `MainWorkspaceView`.
