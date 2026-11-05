# TRYST Task Lifecycle and Execution Orchestration

**Document Identifier:** `TRYST-ARCH-TASK-001`
**Status:** APPROVED BASELINE
**Classification:** Operational Architecture Specification

---

## 1. Production Task Lifecycle State Machine

TRYST implements an explicit, deterministic multi-stage lifecycle for autonomous software development tasks:

```
[User Request / Web API / Planner]
                 |
                 v
             +-------+
             |CREATED|
             +-------+
                 |
                 v
             +-------+
             |QUEUED | <----------------------------------+
             +-------+                                    |
                 |                                        |
                 v (Atomic Worker Claim)                  | (Retry Recovery)
             +-------+                                    |
             |CLAIMED|                                    |
             +-------+                                    |
                 |                                        |
                 v                                        |
             +-------+                                    |
             |CLONING|                                    |
             +-------+                                    |
                 |                                        |
                 v                                        |
            +---------+                                   |
            |ANALYZING|                                   |
            +---------+                                   |
                 |                                        |
                 v                                        |
            +--------+                                    |
            |PLANNING|                                    |
            +--------+                                    |
                 |                                        |
                 v                                        |
          +------------+                                  |
          |IMPLEMENTING| <----------------+               |
          +------------+                  |               |
                 |                        |               |
                 v                        | (Rework)      |
            +--------+                    |               |
            |BUILDING|                    |               |
            +--------+                    |               |
                 |                        |               |
                 v                        |               |
            +-------+                     |               |
            |TESTING|                     |               |
            +-------+                     |               |
                 |                        |               |
                 v                        |               |
       +------------------+               |               |
       |WAITING_FOR_REVIEW|               |               |
       +------------------+               |               |
          |       |                       |               |
          |       +--> +----------------+ |               |
          |            |REWORK_REQUESTED|-+               |
          |            +----------------+                 |
          |                                               |
          +------------> +--------+                       |
          | (User Reject)|REJECTED| [Terminal]            |
          v (User Approve+--------+                       |
       +----------+                                       |
       | APPROVED |                                       |
       +----------+                                       |
          |                                               |
          v (Commit Initiated)                            |
       +----------+                                       |
       |COMMITTING|                                       |
       +----------+                                       |
          |                                               |
          v (Git Commit Successful)                       |
       +----------+                                       |
       |COMPLETED | [Terminal]                            |
       +----------+                                       |
                                                          |
----------------------------------------------------------+---------------------
Special Lifecycle Transitions:
1. CANCELLED: An active or queued task can be manually cancelled at any time by an
   authorized user or orchestrator shutdown.
2. FAILED: Any unhandled exception or critical tool failure moves the task to FAILED.
3. RECOVERY_REQUIRED: If the TRYST server process halts (crash, restart, kill) while a task
   is in active execution (CLAIMED, CLONING, ANALYZING, PLANNING, IMPLEMENTING,
   BUILDING, TESTING, REWORKING, COMMITTING) or QUEUED, startup task reconciliation
   automatically detects the interrupted state and marks the task RECOVERY_REQUIRED.
   Tasks in WAITING_FOR_REVIEW and APPROVED preserve their review status across restarts.
   Tasks in RECOVERY_REQUIRED can be retried back to QUEUED.
```

---

## 2. State Transition Triggers and Invariants

| From State | To State | Trigger | Preconditions & Invariants |
|---|---|---|---|
| `CREATED` | `QUEUED` | User / Dispatcher | Task enqueued in persistent SQLite task queue. |
| `QUEUED` | `CLAIMED` | `TaskQueue.claimNext` | Worker atomically claims task with priority ordering and row lock. |
| `CLAIMED` | `CLONING` | `TaskWorker` | Workspace directory, git containment, and project structure validated. |
| `CLONING` | `ANALYZING` | `TaskWorker` | Repository analyzed for file trees, dependencies, and requirements. |
| `ANALYZING` | `PLANNING` | `TaskWorker` | AI prompt formulated; task plan and tool strategy generated. |
| `PLANNING` | `IMPLEMENTING` | `TaskWorker` | Autonomous loop performs file edits and tool invocations within sandbox. |
| `IMPLEMENTING` | `BUILDING` | `TaskWorker` | Project compilation triggered. |
| `BUILDING` | `TESTING` | `TaskWorker` | Test suites executed; summaries captured. |
| `TESTING` | `WAITING_FOR_REVIEW`| `TaskWorker` | Diff extracted; CodeReview initialized; awaits human review. |
| `WAITING_FOR_REVIEW`| `APPROVED` | User (Review Center) | Human user approves specific revision; no unresolved blocking comments allowed; captures SHA-256 fingerprint & approvedRevision. |
| `WAITING_FOR_REVIEW`| `REJECTED` | User (Review Center) | Reviewer rejects diff with recorded explanation. |
| `WAITING_FOR_REVIEW`| `REWORK_REQUESTED` | User (Review Center) | Reviewer provides feedback; task rework count incremented. |
| `REWORK_REQUESTED` | `REWORKING` / `QUEUED` | Orchestrator | Task re-enters execution loop to address feedback. |
| `APPROVED` | `COMMITTING` | Review Center / Commit Action | Human commit authorization initiated; git status checked immediately before commit. |
| `COMMITTING` | `COMPLETED` | `GitService` | Working tree diff verified against approved fingerprint; git commit succeeds; GitCommitRecord persisted; duplicate commits prevented. |
| `* (non-terminal)` | `CANCELLED` | User / Orchestrator | User aborts task execution or shutdown stops worker. |
| `* (active)` | `FAILED` | `TaskWorker` | Unhandled error, compilation failure, or context limit exceeded. |
| `* (active/queued)` | `RECOVERY_REQUIRED` | `TaskRecoveryService` | Server startup reconciles interrupted tasks left running across restarts. |

---

## 3. Asynchronous Orchestration Architecture

1. **Decoupled HTTP Tier:**
   The HTTP server only handles submitting tasks, querying task status, inspecting task audit events, and initiating cancellations. It never runs development loops synchronously.
2. **Context Isolation (`TaskExecutionContext`):**
   Every task runs within its own dedicated context with isolated project workspace paths, user principal ID, worker ID, and independent cancellation token. No shared mutable `currentTask` or `currentWorkspace` pointers exist.
3. **Controlled Concurrency (`TaskOrchestrator`):**
   `TaskOrchestrator` maintains a fixed-size worker pool with configurable concurrency (defaulting to CPU cores / 2) and an active task context registry.
4. **Persistent Priority Queue (`TaskQueue`):**
   SQLite `tasks` table serves as the persistent queue backing, ordering claims by `priority` (URGENT > HIGH > NORMAL > LOW) and FIFO timestamp, using atomic transactional update-select claiming.
5. **Audited Transitions (`TaskLifecycleManager`):**
   Every transition validates invariants via `TaskStateTransitionValidator`, transactionally persists the task state, persists an audit row to `task_events`, and notifies listeners on the typed `EventBus`.
6. **Restart Recovery (`TaskRecoveryService`):**
   Interrupted executions are safely reconciled on startup to `RECOVERY_REQUIRED`, preventing ghost worker collisions and providing retry endpoints.
