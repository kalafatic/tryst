# TRYST User Administration Architecture in Native RCP

## 1. Overview
Comprehensive native Eclipse RCP administration enables system administrators to inspect an individual user and all associated resources, execution sessions, tasks, code reviews, and audit history through an evidence-based interface that adheres to OSGi service discovery and strict security boundaries.

## 2. Component & View Architecture
- **`UsersAndSessionsView`** (`eu.kalafatic.tryst.ui.view.usersAndSessions`):
  - Displays high-level tabular listings of active users, roles, and active web sessions.
  - Features an "Inspect Selected User Detail" action button and table double-click navigation that launches `UserDetailView`.
- **`UserDetailView`** (`eu.kalafatic.tryst.ui.view.userDetail`):
  - Dedicated multi-tab inspection interface:
    1. **Overview & Identity**: User ID, username, display role, active status, lock state, failed attempts, and summary metric cards.
    2. **Projects & Repositories**: Authoritatively queried projects owned by the user (`ProjectRepository.findByOwnerId`), local repository workspace paths, Git branches, and status.
    3. **User & Application Sessions**: Explicit separation between browser authentication sessions (`UserSessionRepository.findByUserId`) and autonomous agent execution sessions (`SessionRepository.findByProjectId`).
    4. **Tasks & Execution History**: All autonomous and user tasks belonging to the user's projects, execution outcomes, duration, priority, and assigned agents.
    5. **Agent Processes**: Operating system child processes recorded by `ProcessExecutionManager` that are associated with the user's tasks.
    6. **Code Reviews**: Git code reviews associated with the user's tasks, approval status, content fingerprints, and commit hashes.
    7. **Audit & Activity Timeline**: Chronological security and audit events from `AuditEventRepository` filtered by `actorId == userId`.

## 3. Data Decoupling & OSGi Service Discovery
- Views do not execute direct SQL or ORM calls; all access is mediated by authoritative OSGi services discovered via `TrystUiServices`:
  - `UserRepository`
  - `UserSessionRepository`
  - `ProjectRepository`
  - `SessionRepository`
  - `TaskRepository`
  - `CodeReviewRepository`
  - `AuditEventRepository`
  - `ProcessExecutionManager`
- Views remain decoupled from display lifecycles: opening or closing `UserDetailView` has zero impact on server execution or database connections.

## 4. Security & Privacy Guardrails
- **Authentication Secrets**: Password hashes, salts, and session tokens are never rendered in the UI.
- **RBAC Enforcement**: Access to administrative views and service endpoints requires `UserRole.ADMIN` or appropriate granular permissions (`Permission.MANAGE_USERS`). Non-admin users cannot inspect other tenants' details.
