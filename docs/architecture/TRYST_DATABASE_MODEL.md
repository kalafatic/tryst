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

### 2.5 Version 6: Production Workspace and Sandbox Management (`V6`)
- **`workspaces`**
  - Updated with columns:
    - `owner_id` (TEXT NOT NULL DEFAULT 'default-user')
    - `task_id` (TEXT)
    - `status` (TEXT NOT NULL DEFAULT 'ACTIVE')
    - `repository_url` (TEXT)
    - `branch` (TEXT NOT NULL DEFAULT 'main')
    - `metadata_json` (TEXT)
  - Indexes:
    - `idx_workspaces_owner_id`
    - `idx_workspaces_task_id`
    - `idx_workspaces_status`

### 2.6 Version 7: Secure GitHub Credentials Storage (`V7`)
- **`github_credentials`**
  - `id` (TEXT PRIMARY KEY), `user_id` (TEXT NOT NULL UNIQUE, FK -> `users.id` ON DELETE CASCADE), `github_username` (TEXT NOT NULL), `encrypted_token` (TEXT NOT NULL), `scopes` (TEXT), `token_type` (TEXT NOT NULL DEFAULT 'pat'), `expires_at` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_github_credentials_user`, `idx_github_credentials_username`

### 2.7 Version 8: Interactive Web Code Review Revisions & Comments (`V8`)
- **`review_revisions`**
  - `id` (TEXT PRIMARY KEY), `review_id` (TEXT NOT NULL, FK -> `code_reviews.id` ON DELETE CASCADE), `revision_number` (INTEGER NOT NULL), `diff` (TEXT), `explanation` (TEXT), `build_status` (TEXT), `test_summary` (TEXT), `created_at` (TEXT NOT NULL)
  - Indexes: `idx_review_revisions_review_id`, `idx_review_revisions_rev_num` (UNIQUE)
- **`review_comments`**
  - `id` (TEXT PRIMARY KEY), `review_id` (TEXT NOT NULL, FK -> `code_reviews.id` ON DELETE CASCADE), `task_id` (TEXT NOT NULL, FK -> `tasks.id` ON DELETE CASCADE), `revision_number` (INTEGER NOT NULL), `file_path` (TEXT), `diff_side` (TEXT), `start_line` (INTEGER), `end_line` (INTEGER), `code_context` (TEXT), `comment_type` (TEXT), `content` (TEXT NOT NULL), `author_id` (TEXT), `resolved` (INTEGER DEFAULT 0), `include_in_rework` (INTEGER DEFAULT 0), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_review_comments_review_id`, `idx_review_comments_task_id`
- **`code_reviews`**
  - Columns added: `ai_explanation` (TEXT), `revision_count` (INTEGER DEFAULT 1)

### 2.8 Version 9: Task Recovery Checkpoints and Granular Persistence (`V9`)
- **`task_recovery_checkpoints`**
  - `id` (TEXT PRIMARY KEY), `task_id` (TEXT NOT NULL, FK -> `tasks.id` ON DELETE CASCADE), `stage` (TEXT NOT NULL), `checkpoint_data` (TEXT), `created_at` (TEXT NOT NULL)
  - Indexes: `idx_recovery_checkpoints_task`, `idx_recovery_checkpoints_created`

### 2.9 Version 10: Centralized Notification Dispatch and Preferences (`V10`)
- **`notifications`**
  - Added columns: `dedup_key` (TEXT), `seq_num` (INTEGER)
  - Indexes: `idx_notifications_user_seq`, `idx_notifications_dedup`
- **`notification_preferences`**
  - `id` (TEXT PRIMARY KEY), `user_id` (TEXT NOT NULL, FK -> `users.id` ON DELETE CASCADE), `event_type` (TEXT NOT NULL), `in_app_enabled` (INTEGER NOT NULL DEFAULT 1), `browser_push_enabled` (INTEGER NOT NULL DEFAULT 1), `email_enabled` (INTEGER NOT NULL DEFAULT 0), `updated_at` (TEXT NOT NULL)
  - Indexes: `idx_notif_prefs_user`

### 2.10 Version 11: Final Approval and Git Delivery Subsystem (`V11`)
- **`code_reviews`**
  - Added column: `approved_revision` (INTEGER)
- **`review_comments`**
  - Added column: `blocking` (INTEGER NOT NULL DEFAULT 1)
- **`git_commits`**
  - `id` (TEXT PRIMARY KEY), `commit_hash` (TEXT NOT NULL), `review_id` (TEXT NOT NULL REFERENCES `code_reviews.id` ON DELETE CASCADE), `task_id` (TEXT), `project_id` (TEXT NOT NULL REFERENCES `projects.id` ON DELETE CASCADE), `revision_number` (INTEGER NOT NULL), `author_id` (TEXT NOT NULL), `commit_message` (TEXT NOT NULL), `content_fingerprint` (TEXT NOT NULL), `branch` (TEXT NOT NULL), `files_changed` (INTEGER NOT NULL DEFAULT 0), `status` (TEXT NOT NULL DEFAULT 'COMMITTED'), `committed_at` (TEXT NOT NULL), `pushed_at` (TEXT)
  - Indexes: `idx_git_commits_review_id`, `idx_git_commits_task_id`, `idx_git_commits_project_id`, `idx_git_commits_hash`
