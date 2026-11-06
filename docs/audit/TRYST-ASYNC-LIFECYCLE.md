# TRYST Asynchronous Task Lifecycle & State Machine Specification

## 1. Authoritative Task Lifecycle States

The TRYST platform models the autonomous programming task lifecycle across 23 canonical states managed by `TaskLifecycleManager` and validated by `TaskStateTransitionValidator`.

| State | Category | Description | Permitted Transitions |
|---|---|---|---|
| `CREATED` | Initial | Initial intake state upon submission. | `PLANNED`, `READY`, `QUEUED`, `CANCELLED` |
| `PLANNED` | Intake | Optional decomposed step state. | `READY`, `QUEUED`, `CANCELLED` |
| `READY` | Scheduled | Validated and ready for queuing. | `QUEUED`, `CANCELLED` |
| `QUEUED` | Scheduled | Placed in SQLite task queue awaiting worker claim. | `CLAIMED`, `RUNNING`, `CANCELLED` |
| `CLAIMED` | Execution | Atomically claimed by a worker thread (`claimNextTask`). | `RUNNING`, `CLONING`, `CANCELLED`, `FAILED` |
| `RUNNING` | Execution | General active execution state. | `CLONING`, `ANALYZING`, `PLANNING`, `IMPLEMENTING`, `BUILDING`, `TESTING`, `WAITING_FOR_REVIEW`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `CLONING` | Execution | Sandbox preparation & remote Git repository clone/fetch. | `ANALYZING`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `ANALYZING` | Execution | Repository inspection, AST analysis, convention detection. | `PLANNING`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `PLANNING` | Execution | AI implementation plan generation. | `IMPLEMENTING`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `IMPLEMENTING` | Execution | CodeAgent & tool execution loop applying file changes. | `BUILDING`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `BUILDING` | Execution | Automated compilation verification (e.g. `mvn compile`). | `TESTING`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `TESTING` | Execution | Automated test suite execution (e.g. `mvn test`). | `WAITING_FOR_REVIEW`, `WAITING_FOR_APPROVAL`, `CANCELLED`, `FAILED`, `RECOVERY_REQUIRED` |
| `WAITING_FOR_REVIEW` | Review | Diffs extracted and presented to user in Code Review Center. | `APPROVED`, `REWORK_REQUESTED`, `REJECTED`, `CANCELLED` |
| `WAITING_FOR_APPROVAL`| Review | Changes pending human reviewer approval. | `APPROVED`, `REJECTED`, `REWORK_REQUESTED`, `CANCELLED` |
| `REWORK_REQUESTED` | Review | User requested modifications on existing revision. | `QUEUED`, `REWORKING`, `CANCELLED` |
| `REWORKING` | Execution | TaskWorker executing iterative rework on active comments. | `BUILDING`, `WAITING_FOR_REVIEW`, `FAILED`, `CANCELLED`, `RECOVERY_REQUIRED` |
| `APPROVED` | Approved | Exact review revision explicitly approved by human user. | `COMMITTING`, `COMPLETED`, `REWORK_REQUESTED`, `CANCELLED` |
| `REJECTED` | Terminal | Changes rejected by human reviewer. | (Terminal) |
| `COMMITTING` | Delivery | GitService performing authorized commit and commit persistence. | `COMPLETED`, `WAITING_FOR_REVIEW`, `FAILED`, `RECOVERY_REQUIRED` |
| `COMPLETED` | Terminal | Changes committed and workspace marked complete. | (Terminal) |
| `FAILED` | Terminal | Unrecoverable error encountered during workflow. | `QUEUED`, `RECOVERY_REQUIRED` (via operator retry) |
| `CANCELLED` | Terminal | User requested cancellation; all processes killed. | (Terminal) |
| `RECOVERY_REQUIRED` | Recovery | Active state interrupted by sudden crash/restart. | `QUEUED`, `FAILED`, `CANCELLED` |

---

## 2. Asynchronous Execution Guarantees

1. **Browser Disconnect Independence**:
   - HTTP requests return `201 Created` immediately with the assigned `taskId`.
   - Execution runs asynchronously in worker thread pools managed by `TaskOrchestrator`.
   - Progress is persisted durably to SQLite tables `tasks`, `task_events`, and `task_recovery_checkpoints`.

2. **Atomic Worker Claiming**:
   - Multiple background worker threads claim tasks using transactional conditional updates:
     `UPDATE tasks SET status = 'CLAIMED', worker_id = ? WHERE id = ? AND status = 'QUEUED'`.
   - Eliminates duplicate claiming across concurrent threads or workers.

3. **Interruption Recovery**:
   - Upon restart, `TaskRecoveryService.reconcileInterruptedTasks()` scans for active states (`RUNNING`, `CLONING`, `IMPLEMENTING`, `BUILDING`, `TESTING`, `COMMITTING`).
   - Active tasks are classified and transitioned to `RECOVERY_REQUIRED` with durable checkpoints, preventing naive re-execution of non-idempotent operations.
   - Tasks in `WAITING_FOR_REVIEW` and `APPROVED` are preserved without interruption.
