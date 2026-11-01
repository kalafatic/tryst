# TRYST Database Schema

## 1. Schema Overview

The database schema is normalized and models all core development concepts:
- `schema_version`: Tracks migration history and current schema version.
- `projects`: Persistent project records.
- `workspaces`: Active workspaces and paths.
- `sessions`: Development and agent conversational sessions bound to projects.
- `tasks`: Development and autonomous agent tasks with explicit status.
- `conversation_messages`: Chronological conversation turns in sessions.
- `execution_results`: Build, test, and command execution results linked to tasks.
- `agents`: Agent profiles and model configuration specifications.

## 2. Table Definitions

### `schema_version`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `version` | INTEGER | PRIMARY KEY | Sequential migration number |
| `description` | TEXT | NOT NULL | Migration description |
| `applied_at` | TEXT | NOT NULL | ISO-8601 timestamp |

### `projects`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Stable domain identifier (UUID) |
| `name` | TEXT | NOT NULL | Project name |
| `location` | TEXT | NOT NULL | Filesystem path |
| `active` | INTEGER | NOT NULL DEFAULT 0 | 1 if active project, else 0 |
| `created_at` | TEXT | NOT NULL | ISO-8601 creation timestamp |
| `updated_at` | TEXT | NOT NULL | ISO-8601 modification timestamp |

Indexes: `idx_projects_active` on `(active)`

### `workspaces`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Stable workspace identifier |
| `root_path` | TEXT | NOT NULL | Workspace directory path |
| `active_project_id` | TEXT | REFERENCES `projects(id)` ON DELETE SET NULL | Currently active project |
| `created_at` | TEXT | NOT NULL | Creation timestamp |
| `updated_at` | TEXT | NOT NULL | Modification timestamp |

### `sessions`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Session identifier |
| `project_id` | TEXT | NOT NULL, REFERENCES `projects(id)` ON DELETE CASCADE | Associated project |
| `name` | TEXT | NOT NULL | Session name |
| `status` | TEXT | NOT NULL | Session status (ACTIVE, CLOSED, etc.) |
| `created_at` | TEXT | NOT NULL | Creation timestamp |
| `updated_at` | TEXT | NOT NULL | Modification timestamp |

Indexes: `idx_sessions_project_id` on `(project_id)`, `idx_sessions_status` on `(status)`

### `tasks`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Task identifier |
| `project_id` | TEXT | REFERENCES `projects(id)` ON DELETE SET NULL | Associated project |
| `session_id` | TEXT | REFERENCES `sessions(id)` ON DELETE SET NULL | Associated session |
| `title` | TEXT | NOT NULL | Task title |
| `description` | TEXT | | Task description |
| `status` | TEXT | NOT NULL | Execution status (IDLE, RUNNING, COMPLETED, FAILED, CANCELLED) |
| `created_at` | TEXT | NOT NULL | Creation timestamp |
| `completed_at` | TEXT | | Completion timestamp |

Indexes: `idx_tasks_project_id` on `(project_id)`, `idx_tasks_session_id` on `(session_id)`, `idx_tasks_status` on `(status)`

### `conversation_messages`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Message identifier |
| `session_id` | TEXT | NOT NULL, REFERENCES `sessions(id)` ON DELETE CASCADE | Associated session |
| `role` | TEXT | NOT NULL | USER, ASSISTANT, or SYSTEM |
| `content` | TEXT | NOT NULL | Content string |
| `created_at` | TEXT | NOT NULL | ISO-8601 creation timestamp |

Indexes: `idx_messages_session_created` on `(session_id, created_at)`

### `execution_results`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Result identifier |
| `task_id` | TEXT | NOT NULL, REFERENCES `tasks(id)` ON DELETE CASCADE | Associated task |
| `exit_code` | INTEGER | NOT NULL | Exit code (0 for success) |
| `output` | TEXT | | Standard output |
| `error_output` | TEXT | | Error output |
| `duration_ms` | INTEGER | NOT NULL | Execution duration in ms |
| `completed_at` | TEXT | NOT NULL | ISO-8601 completion timestamp |

Indexes: `idx_results_task_id` on `(task_id)`

### `agents`
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | TEXT | PRIMARY KEY | Agent configuration identifier |
| `name` | TEXT | NOT NULL UNIQUE | Agent identifier name |
| `model_name` | TEXT | NOT NULL | LLM model name (e.g. qwen2.5-coder) |
| `system_prompt` | TEXT | | System prompt template |
| `configuration_json`| TEXT | | JSON configuration parameters |
| `created_at` | TEXT | NOT NULL | Creation timestamp |
| `updated_at` | TEXT | NOT NULL | Modification timestamp |
