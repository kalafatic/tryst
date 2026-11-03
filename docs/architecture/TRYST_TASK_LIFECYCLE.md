# TRYST Task Lifecycle and Execution Orchestration

**Document Identifier:** `TRYST-ARCH-TASK-001`
**Status:** APPROVED BASELINE
**Classification:** Operational Architecture Specification

---

## 1. Task Lifecycle State Machine

TRYST implements a deterministic 14-state lifecycle for autonomous software development tasks:

```
[User Request / Web API]
        |
        v
    +-------+
    |CREATED|
    +-------+
        |
        v
    +-------+
    |PLANNED|
    +-------+
        |
        v
    +-------+
    | READY |
    +-------+
        |
        v
    +-------+
    |QUEUED |
    +-------+
        |
        v
    +-------+
    |CLAIMED|
    +-------+
        |
        v
    +-------+
    |RUNNING| <-----+ (Iterative Tool Loop)
    +-------+       |
     |   |   \------+
     |   |
     |   +------------------------------------+
     |                                        |
     v (Review required)                      v (Tool execution error / failure)
+------------------+                      +--------+
|WAITING_FOR_REVIEW|                      | FAILED | [Terminal]
+------------------+                      +--------+
     |        |
     |        +---------------> +--------+
     | (User rejects)           |REJECTED| [Terminal]
     v (User approves)          +--------+
+----------+
| APPROVED |
+----------+
     |
     v (Commit initiated)
+----------+
|COMMITTING|
+----------+
     |
     v (Git commit successful)
+----------+
|COMPLETED | [Terminal]
+----------+

--------------------------------------------------------------------------------
Special Lifecycle Transitions:
1. CANCELLED: An active task can be manually cancelled at any time by an authorized user.
2. RECOVERY_REQUIRED: If the TRYST server process halts (crash, restart, kill) while a task
   is in RUNNING, CLAIMED, or COMMITTING status, startup task reconciliation automatically
   detects the interrupted state and marks the task RECOVERY_REQUIRED.
   Tasks in WAITING_FOR_REVIEW and APPROVED preserve their review status across restarts.
```

---

## 2. State Transition Triggers and Invariants

| From State | To State | Trigger | Preconditions & Invariants |
|---|---|---|---|
| `CREATED` | `PLANNED` | Planner Agent | Initial steps and execution strategy created. |
| `PLANNED` | `READY` | Planner Agent / User | Required resources, workspace, and model verified. |
| `READY` | `QUEUED` | Dispatcher | Task enqueued in async worker executor queue. |
| `QUEUED` | `CLAIMED` | Worker Thread | Background execution thread claims task lock. |
| `CLAIMED` | `RUNNING` | Session Orchestrator | Autonomous loop starts prompting AI and executing tools. |
| `RUNNING` | `WAITING_FOR_REVIEW`| Orchestrator / Diff Engine | Agent produced working tree modifications; automated checks completed. |
| `WAITING_FOR_REVIEW`| `APPROVED` | User (Review Center) | Human reviewer approves diff; captures SHA-256 content fingerprint. |
| `WAITING_FOR_REVIEW`| `REJECTED` | User (Review Center) | Reviewer rejects diff with recorded explanation. |
| `APPROVED` | `COMMITTING` | Review Center / Commit Action| Commit authorization initiated. |
| `COMMITTING` | `COMPLETED` | `GitService` | Working tree diff verified against approved fingerprint; `git commit` succeeds. |
| `RUNNING` | `FAILED` | Orchestrator | Unrecoverable build failure, unhandled tool error, or context limit exceeded. |
| `*` | `CANCELLED` | User Action | User explicitly aborts active task execution. |
| `RUNNING`/`CLAIMED`/`COMMITTING` | `RECOVERY_REQUIRED` | Server Startup Coordinator | Interrupted task detected in database upon application restart. |

---

## 3. Autonomous Execution Orchestration Loop

1. **Context Initialization:**
   `AiContextManager` constructs the prompt including project structure, active task details, tools definition, and previous conversation history.
2. **Inference Execution:**
   `OllamaClient` invokes local Ollama API via standard Java 21 `HttpClient`.
3. **Tool Invocation & Containment:**
   Agent responses requesting file inspection, file editing, directory listing, or diff inspection are validated by `PermissionManager` to ensure workspace containment.
4. **Post-Execution Review Creation:**
   Once the agent signals task completion, `GitService` extracts the unified diff. A `CodeReview` entity is initialized, and automated heuristic scanners check for hardcoded secrets or syntax errors.
5. **State Advancement:**
   Task transitions to `WAITING_FOR_REVIEW`, alerting reviewers through `NotificationService` and publishing an event to `EventBus`.
