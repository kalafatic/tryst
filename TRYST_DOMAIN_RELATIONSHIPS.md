# TRYST Domain Relationships & Ownership Rules

## 1. Domain Ownership Model

The TRYST domain model establishes strict entity ownership and uses stable UUID strings for loose coupling across aggregate boundaries, preventing large recursive object graphs and cyclic dependencies.

### Relationship Architecture Diagram

```text
               +---------------------------+
               |          PROJECT          |
               +---------------------------+
                 |                       |
       1 : N     |             1 : N     |
  (by reference) |        (by reference) |
                 v                       v
      +---------------------+   +---------------------+
      |      WORKSPACE      |   |       SESSION       |
      +---------------------+   +---------------------+
                 |                 |               |
                 |                 | 1 : N         | 1 : N
                 |                 v               v
                 |      +------------------+ +-------------------+
                 |      |   CONVERSATION   | |       TASK        |
                 |      |     MESSAGE      | |    (AgentTask)    |
                 |      +------------------+ +-------------------+
                 |                                     |
                 |                                     | 1 : 1
                 | 1 : N                               v
                 | (aggregated)              +-------------------+
                 +-------------------------> | EXECUTION RESULT  |
                                             +-------------------+
                                                       ^
                                                       | references
                                             +-------------------+
                                             |       AGENT       |
                                             +-------------------+
```

---

## 2. Ownership & Reference Cardinality

| Parent Entity | Related Entity | Cardinality | Association Mechanism | Deletion / Cascade Semantic |
|---|---|---|---|---|
| `Project` | `Workspace` | 1 : N | `Workspace.projects` collection, `Session.workspaceId` | Workspace can outlive Project; Project removal removes reference |
| `Project` | `Session` | 1 : N | `Session.projectId` foreign key | Deleting a Project terminates and deletes related Sessions |
| `Session` | `ConversationMessage` | 1 : N | `ConversationMessage.sessionId` foreign key | Messages are strictly owned by a Session; cascade delete |
| `Session` | `AgentTask` | 1 : N | `AgentTask.sessionId` (optional) | Tasks are correlated to Session for UI display, but have independent lifecycle |
| `Project` | `AgentTask` | 1 : N | `AgentTask.projectId` (optional) | Tasks reference Project by ID |
| `AgentTask` | `ExecutionResult` | 1 : 1 | `ExecutionResult.taskId`, `AgentTask.executionResultId` | ExecutionResult is strictly bound to its executed Task |
| `Agent` | `AgentTask` | 1 : N | `AgentTask.agentId` | Tasks reference an Agent by ID; Agent exists independently |

---

## 3. Important Architectural Decisions

### 3.1 Task Execution Outside Interactive Sessions
**Decision**: Tasks (`AgentTask`) maintain independent identities and do NOT depend on the existence of an active UI tab or interactive Session.
- `AgentTask` has an optional `sessionId` and optional `projectId`.
- Tasks can be triggered as background batch tasks, automated CI validation runs, or planner-generated workflows without requiring an interactive session.
- Tasks are safely persisted in repositories independent of UI components.

### 3.2 Workspace Directory Isolation
**Decision**: A single physical workspace directory cannot safely serve multiple concurrent autonomous execution agents simultaneously.
- `Workspace` has explicit lifecycle status (`WorkspaceStatus.LOCKED` / `ACTIVE`).
- When an agent is modifying files or running builds/tests, the workspace working state can be locked to prevent concurrent clobbering.

### 3.3 Stable UUIDs vs Database Auto-Increment IDs
**Decision**: All domain entities generate their own UUID strings (`UUID.randomUUID().toString()`) at creation time.
- Identifiers are stable across in-memory instantiation, asynchronous queues, UI view rendering, and SQLite persistence.
- Identity equality (`equals` and `hashCode`) relies strictly on `id`, preventing identity shifts across persistence boundaries.
