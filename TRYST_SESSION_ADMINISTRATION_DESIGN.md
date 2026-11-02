# TRYST Session Administration Design

## 1. Domain Separation of Concepts
TRYST strictly separates:
- **HTTP Sessions**: Web client connections.
- **User Sessions (`UserSession`)**: Authenticated bearer tokens with slide TTL and IP tracking.
- **Application Sessions (`Session`)**: Execution units bound to projects and workspaces.
- **Agent Tasks (`AgentTask`)**: Discrete autonomous development objectives.
- **Managed Processes (`ManagedProcessRecord`)**: Operating system child processes.

## 2. Session Detail Inspection (`SessionDetailView`)
Administrators can select an active or archived `Session` to inspect:
- Identity and project linkage.
- Session lifecycle status (`ACTIVE`, `PAUSED`, `COMPLETED`, `CLOSED`, `TERMINATED`).
- Creation and last activity timestamps.
- All associated `AgentTask` records, their priorities, statuses, and linked code reviews.
