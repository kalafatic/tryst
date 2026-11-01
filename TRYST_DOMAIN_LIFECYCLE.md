# TRYST Domain Lifecycle & State Transitions

## 1. Lifecycle Principles

Entities with mutable lifecycles govern their transitions through explicit domain methods.
Direct mutation of status fields is prohibited.
Attempting an illegal transition triggers a `eu.kalafatic.tryst.core.error.TrystDomainException`.
Terminal states are final; once entered, no subsequent status transitions are permitted.

---

## 2. State Machines

### 2.1 Project Lifecycle (`ProjectStatus`)

```text
       [Creation]
           |
           v
       +--------+  archive()   +----------+
       | ACTIVE | -----------> | ARCHIVED |
       +--------+ <----------- +----------+
           |        activate()
           | close()
           v
       +--------+
       | CLOSED | (Terminal)
       +--------+
```

- **States**:
  - `ACTIVE`: Project is open and active for planning, browsing, and session creation.
  - `ARCHIVED`: Project is read-only / archived. Cannot be set as the active project in focus.
  - `CLOSED`: Project is decommissioned. Cannot be reactivated.
- **Terminal States**: `CLOSED`.

---

### 2.2 Workspace Lifecycle (`WorkspaceStatus`)

```text
       [Creation]
           |
           v
       +--------+  deactivate()  +----------+
       | ACTIVE | -------------> | INACTIVE |
       +--------+ <------------- +----------+
         ^    |      activate()
         |    | lock()
 unlock()|    v
       +--------+
       | LOCKED |
       +--------+
           |
           | close()
           v
       +--------+
       | CLOSED | (Terminal)
       +--------+
```

- **States**:
  - `ACTIVE`: Workspace is receptive to project associations, file modifications, and task additions.
  - `INACTIVE`: Workspace is idle or backgrounded.
  - `LOCKED`: Workspace is currently undergoing exclusive agent execution or build/test operation. Project and task additions are rejected.
  - `CLOSED`: Workspace is permanently closed.
- **Terminal States**: `CLOSED`.

---

### 2.3 Session Lifecycle (`SessionStatus`)

```text
       [Creation]
           |
           v
       +--------+  pause()     +--------+
       | ACTIVE | -----------> | PAUSED |
       +--------+ <----------- +--------+
           |        resume()
           | complete()
           v
      +-----------+
      | COMPLETED |
      +-----------+
           |
           | close()
           v
       +--------+
       | CLOSED |
       +--------+
           |
           | terminate()
           v
      +------------+
      | TERMINATED | (Terminal)
      +------------+
```

- **States**:
  - `ACTIVE`: Interactive chat and prompt execution enabled.
  - `PAUSED`: Temporarily suspended interaction.
  - `COMPLETED`: Conversation / execution objective successfully concluded.
  - `CLOSED`: Session finished and archived.
  - `TERMINATED`: Session forcibly terminated or aborted.
- **Terminal States**: `TERMINATED` (and `CLOSED` cannot transition backward).

---

### 2.4 AgentTask Lifecycle (`TaskStatus`)

```text
             [Creation]
                 |
                 v
             +---------+
             | CREATED |
             +---------+
              |       |
      plan()  |       | markReady() / start()
              v       v
         +---------+ +-------+
         | PLANNED | | READY |
         +---------+ +-------+
              |          |
   markReady()|          | start()
              +----+-----+
                   |
                   v
             +-----------+  requestApproval()  +----------------------+
             |  RUNNING  | ------------------> | WAITING_FOR_APPROVAL |
             +-----------+ <------------------ +----------------------+
              |    |           approve()                   |
   complete() |    | fail()                                | fail()
              |    v                                       v
              |  +--------+                           +--------+
              |  | FAILED | (Terminal)                | FAILED | (Terminal)
              |  +--------+                           +--------+
              v
       +-----------+
       | COMPLETED | (Terminal)
       +-----------+

  [Any non-terminal state] --- cancel() ---> +-----------+ (Terminal)
                                             | CANCELLED |
                                             +-----------+
```

- **States**:
  - `CREATED`: Newly instantiated task.
  - `PLANNED`: Task decomposed into plan steps.
  - `READY`: Ready for agent execution pickup.
  - `RUNNING`: Actively being executed by an assigned agent.
  - `WAITING_FOR_APPROVAL`: Paused waiting for human-in-the-loop review or authorization.
  - `COMPLETED`: Task successfully accomplished.
  - `FAILED`: Execution terminated with failure.
  - `CANCELLED`: Aborted prior to completion.
- **Terminal States**: `COMPLETED`, `FAILED`, `CANCELLED`.
- **Cancellation Semantics**: Any task in a non-terminal status (`CREATED`, `PLANNED`, `READY`, `RUNNING`, `WAITING_FOR_APPROVAL`) can transition to `CANCELLED`. Once terminal, cancellation is rejected.

---

### 2.5 Agent Availability Lifecycle (`AgentAvailability`)

```text
       +-----------+  markBusy()    +------+
       | AVAILABLE | -------------> | BUSY |
       +-----------+ <------------- +------+
          ^     |       markAvailable()
          |     | markOffline()
          |     v
       +---------+
       | OFFLINE |
       +---------+
```

- **States**:
  - `AVAILABLE`: Ready to accept tasks.
  - `BUSY`: Currently executing a task.
  - `OFFLINE`: Disconnected or disabled. An offline agent must be marked `AVAILABLE` before being marked `BUSY`.
