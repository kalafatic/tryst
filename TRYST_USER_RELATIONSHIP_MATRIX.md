# TRYST User Relationship Matrix

| Information Domain | Authoritative Domain Entity | Repository Method | Direct User Relationship | Available Detail |
|---|---|---|---|---|
| **User Identity** | `User` | `UserRepository.findById(userId)` | Primary Key (`users.id`) | Username, role (RBAC), active, locked, failed login attempts, createdAt, updatedAt |
| **Authentication Sessions** | `UserSession` | `UserSessionRepository.findByUserId(userId)` | Foreign Key (`user_sessions.user_id = users.id`) | Session ID, client IP, user agent, expiration, revocation status, last activity (Bearer tokens hidden) |
| **Projects & Workspaces** | `Project`, `Workspace` | `ProjectRepository.findByOwnerId(userId)` | Foreign Key (`projects.owner_id = users.id`) | Project ID, name, description, workspace location path, active status, creation time |
| **Git Repositories** | `Project` (Git working tree) | `GitService.getBranch(project.location)` | Project workspace repository path | Branch name, HEAD commit hash, clean/dirty working directory status |
| **Application Sessions** | `Session` | `SessionRepository.findByProjectId(projectId)` | Bound via owned projects (`sessions.project_id`) | Session ID, name, status (ACTIVE, PAUSED, COMPLETED, CLOSED), created/updated timestamps |
| **Autonomous Tasks** | `AgentTask` | `TaskRepository.findByProjectId(projectId)` | Bound via owned projects / sessions (`tasks.project_id`) | Task ID, title, description, status, priority, agent assigned, creation and completion timestamps |
| **Agent Executions** | `ManagedProcessRecord`, `ExecutionResult` | `ProcessExecutionManager.getManagedProcesses()` | Linked via `taskId` of user tasks | PID, command class, working directory, process status, execution duration, exit code |
| **Code Reviews** | `CodeReview` | `CodeReviewRepository.findByProjectId(projectId)` | Linked via user projects and tasks (`code_reviews.task_id`) | Review ID, branch, status (PENDING, APPROVED, REJECTED), initial & effective diffs, content fingerprint, commit hash |
| **Audit History** | `AuditEvent` | `AuditEventRepository.query(actorId, ...)` | Indexed column (`audit_events.actor_id = users.id`) | Timestamp, action, target type, target ID, outcome (SUCCESS/FAILURE), details payload |
| **User Notifications** | `Notification` | `NotificationRepository.findByUserId(userId, ...)` | Foreign Key (`notifications.user_id = users.id`) | Notification ID, type, title, message, reference type/ID, isRead status, timestamp |
