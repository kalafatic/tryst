# TRYST Relational Database Model and Migrations

**Document Identifier:** `TRYST-ARCH-DB-001`
**Status:** APPROVED BASELINE
**Classification:** Database Architecture Specification

---

## 1. Relational Database Principles

TRYST uses SQLite 3 via standard JDBC (`org.sqlite.JDBC`) without ORM overhead:
- **Authoritative Location:** `~/.tryst/tryst.db`.
- **Concurrency & WAL Mode:** Configured with `PRAGMA journal_mode = WAL;`, `PRAGMA synchronous = NORMAL;`, `PRAGMA busy_timeout = 5000;`, and `PRAGMA foreign_keys = ON;`.
- **Integrity Checks:** Verified upon connection opening via `PRAGMA integrity_check`.
- **Transaction Demarcation:** Atomic multi-statement operations run inside `UnitOfWork` / `SqliteDatabaseManager.runInTransaction()`, synchronizing on an internal monitor lock.

---

## 2. Relational Schema by Migration Version

### 2.1 Version 1: Core Domain Entities (`V1`)
- **`schema_version`**
  - `version` (INTEGER PRIMARY KEY), `description` (TEXT), `applied_at` (TEXT)
- **`projects`**
  - `id` (TEXT PRIMARY KEY), `owner_id` (TEXT NOT NULL DEFAULT 'default-user'), `name` (TEXT NOT NULL), `description` (TEXT), `location` (TEXT NOT NULL), `active` (INTEGER NOT NULL DEFAULT 0), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_projects_active`, `idx_projects_owner`
- **`workspaces`**
  - `id` (TEXT PRIMARY KEY), `root_path` (TEXT NOT NULL), `active_project_id` (TEXT, FK -> `projects.id`), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
- **`sessions`**
  - `id` (TEXT PRIMARY KEY), `project_id` (TEXT NOT NULL, FK -> `projects.id`), `name` (TEXT NOT NULL), `status` (TEXT NOT NULL), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_sessions_project_id`, `idx_sessions_status`
- **`tasks`**
  - `id` (TEXT PRIMARY KEY), `project_id` (TEXT, FK -> `projects.id`), `session_id` (TEXT, FK -> `sessions.id`), `title` (TEXT NOT NULL), `description` (TEXT), `status` (TEXT NOT NULL), `created_at` (TEXT NOT NULL), `completed_at` (TEXT)
  - Indexes: `idx_tasks_project_id`, `idx_tasks_session_id`, `idx_tasks_status`
- **`conversation_messages`**
  - `id` (TEXT PRIMARY KEY), `session_id` (TEXT NOT NULL, FK -> `sessions.id`), `role` (TEXT NOT NULL), `content` (TEXT NOT NULL), `created_at` (TEXT NOT NULL)
  - Index: `idx_messages_session_created`
- **`execution_results`**
  - `id` (TEXT PRIMARY KEY), `task_id` (TEXT NOT NULL, FK -> `tasks.id`), `exit_code` (INTEGER NOT NULL), `output` (TEXT), `error_output` (TEXT), `duration_ms` (INTEGER NOT NULL), `completed_at` (TEXT NOT NULL)
  - Index: `idx_results_task_id`
- **`agents`**
  - `id` (TEXT PRIMARY KEY), `name` (TEXT NOT NULL UNIQUE), `model_name` (TEXT NOT NULL), `system_prompt` (TEXT), `configuration_json` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)

### 2.2 Version 2: Code Review Center & Isolation (`V2`)
- **`code_reviews`**
  - `id` (TEXT PRIMARY KEY), `project_id` (TEXT NOT NULL, FK -> `projects.id`), `task_id` (TEXT NOT NULL, FK -> `tasks.id`), `branch` (TEXT NOT NULL), `status` (TEXT NOT NULL), `initial_diff` (TEXT), `effective_diff` (TEXT), `content_fingerprint` (TEXT), `approved_fingerprint` (TEXT), `commit_hash` (TEXT), `approved_by` (TEXT), `rejected_by` (TEXT), `rejection_reason` (TEXT), `build_status` (TEXT), `test_summary` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_code_reviews_project`, `idx_code_reviews_task`, `idx_code_reviews_status`
- **`review_file_edits`**
  - `id` (TEXT PRIMARY KEY), `review_id` (TEXT NOT NULL, FK -> `code_reviews.id`), `file_path` (TEXT NOT NULL), `original_content` (TEXT), `modified_content` (TEXT), `updated_at` (TEXT NOT NULL)
  - Index: `idx_review_file_edits_review`

### 2.3 Version 3: Security, Observability, and Operational Control (`V3`)
- **`users`**
  - `id` (TEXT PRIMARY KEY), `username` (TEXT NOT NULL UNIQUE), `password_hash` (TEXT NOT NULL), `salt` (TEXT NOT NULL), `role` (TEXT NOT NULL DEFAULT 'USER'), `active` (INTEGER DEFAULT 1), `locked` (INTEGER DEFAULT 0), `failed_attempts` (INTEGER DEFAULT 0), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_users_username`, `idx_users_role`
- **`user_sessions`**
  - `id` (TEXT PRIMARY KEY), `user_id` (TEXT NOT NULL, FK -> `users.id`), `token_hash` (TEXT NOT NULL UNIQUE), `client_ip` (TEXT), `user_agent` (TEXT), `expires_at` (TEXT NOT NULL), `revoked` (INTEGER DEFAULT 0), `created_at` (TEXT NOT NULL), `last_activity` (TEXT NOT NULL)
  - Indexes: `idx_user_sessions_user`, `idx_user_sessions_token`, `idx_user_sessions_revoked`
- **`audit_events`**
  - `id` (TEXT PRIMARY KEY), `event_time` (TEXT NOT NULL), `actor_id` (TEXT NOT NULL), `action` (TEXT NOT NULL), `target_type` (TEXT NOT NULL), `target_id` (TEXT), `project_id` (TEXT), `task_id` (TEXT), `outcome` (TEXT NOT NULL), `details_json` (TEXT)
  - Indexes: `idx_audit_events_time`, `idx_audit_events_actor`, `idx_audit_events_action`, `idx_audit_events_project`
- **`telemetry_snapshots`**
  - `id` (TEXT PRIMARY KEY), `timestamp` (TEXT NOT NULL), `cpu_system` (REAL), `cpu_process` (REAL), `memory_heap_used` (INTEGER), `memory_heap_max` (INTEGER), `memory_system_used` (INTEGER), `disk_workspace_used` (INTEGER), `disk_workspace_total` (INTEGER), `active_sessions` (INTEGER), `queued_tasks` (INTEGER), `running_tasks` (INTEGER), `ai_latency_ms` (REAL)
  - Index: `idx_telemetry_time`
- **`notifications`**
  - `id` (TEXT PRIMARY KEY), `user_id` (TEXT NOT NULL, FK -> `users.id`), `type` (TEXT NOT NULL), `title` (TEXT NOT NULL), `message` (TEXT NOT NULL), `reference_type` (TEXT), `reference_id` (TEXT), `is_read` (INTEGER DEFAULT 0), `created_at` (TEXT NOT NULL)
  - Index: `idx_notifications_user_read`
- **`managed_processes`**
  - `id` (TEXT PRIMARY KEY), `task_id` (TEXT, FK -> `tasks.id`), `pid` (INTEGER NOT NULL), `command_class` (TEXT NOT NULL), `working_dir` (TEXT NOT NULL), `start_time` (TEXT NOT NULL), `duration_ms` (INTEGER DEFAULT 0), `status` (TEXT NOT NULL), `exit_code` (INTEGER)
  - Indexes: `idx_managed_processes_task`, `idx_managed_processes_status`

### 2.4 Version 4: AI Providers and Model Publication Catalog (`V4`)
- **`ai_providers`**
  - `id` (TEXT PRIMARY KEY), `name` (TEXT NOT NULL), `provider_type` (TEXT NOT NULL), `endpoint` (TEXT NOT NULL), `enabled` (INTEGER DEFAULT 1), `health_status` (TEXT NOT NULL DEFAULT 'UNKNOWN'), `last_checked_at` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Index: `idx_ai_providers_enabled`
- **`ai_models`**
  - `id` (TEXT PRIMARY KEY), `provider_id` (TEXT NOT NULL, FK -> `ai_providers.id`), `model_identifier` (TEXT NOT NULL), `display_name` (TEXT NOT NULL), `capabilities_json` (TEXT), `context_limit` (INTEGER DEFAULT 4096), `available` (INTEGER DEFAULT 1), `published` (INTEGER DEFAULT 1), `min_role` (TEXT DEFAULT 'USER'), `last_verified_at` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_ai_models_provider`, `idx_ai_models_available`, `idx_ai_models_published`
