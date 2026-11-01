# TRYST Autonomous Development Lifecycle and Persistent State

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Implementation Baseline:** PROMPT 03/04

---

## 1. Lifecycle States

The autonomous development lifecycle in TRYST manages agent tasks across their entire progression from submission to human review and commit.

Authoritative enum definitions:
- `eu.kalafatic.tryst.domain.model.TaskStatus`
- `eu.kalafatic.tryst.domain.model.ExecutionStatus`

The lifecycle distinguishes:
- `CREATED`: Initial task entity instantiated.
- `PLANNED`: Agent or orchestrator has generated the task execution plan.
- `READY`: Ready for scheduling and agent assignment.
- `QUEUED`: Enqueued in persistent storage waiting for worker execution.
- `CLAIMED`: Claimed by a specific agent instance (`task.claim(agentId)`).
- `RUNNING`: Agent actively executing tools and modifying workspace files.
- `WAITING_FOR_APPROVAL`: Interactive tool execution paused pending user input.
- `WAITING_FOR_REVIEW`: Tool execution and build/test phases completed; changes packaged for Code Review Center inspection.
- `APPROVED`: Explicit human authorization granted for the current review package.
- `REJECTED`: Review rejected by the user.
- `COMMITTING`: Git commit and hash recording actively in progress.
- `COMPLETED`: Successfully committed or completed.
- `FAILED`: Execution or build/test failure occurred.
- `CANCELLED`: Interrupted by user cancellation.
- `RECOVERY_REQUIRED`: Task was in an active state when server crashed or restarted; requires manual or automated reconciliation.

---

## 2. State Transition Machine

All transitions are strictly validated in `AgentTask`:
- Invalid transitions throw `TrystDomainException` to prevent inconsistent states.
- Terminal states (`COMPLETED`, `FAILED`, `CANCELLED`) reject subsequent modifications.
- State transitions are persisted immediately to SQLite through `SqliteTaskRepository` and broadcast to listeners via `EventBus` (`TaskStateChangedEvent`).

```
  [CREATED] ──> [PLANNED] ──> [READY] ──> [QUEUED] ──> [CLAIMED]
                                                            │
                                                            ▼
                                                       [RUNNING] ── (Tool/Build)
                                                            │
                                                            ▼
                                                  [WAITING_FOR_REVIEW]
                                                       │        │
                                          (Reject)    │        │  (Approve)
                                                      ▼        ▼
                                                 [REJECTED]  [APPROVED]
                                                                  │
                                                                  ▼
                                                             [COMMITTING]
                                                                  │
                                                                  ▼
                                                             [COMPLETED]
```

---

## 3. Crash and Restart Recovery

When the application terminates unexpectedly (process kill, hardware shutdown, or unhandled crash), tasks in active intermediate states (`RUNNING`, `CLAIMED`, `COMMITTING`) must not remain silently hanging.

During `ApplicationLifecycleCoordinator.onCoreServiceInitialization()`:
```java
private void reconcileInterruptedTasks() {
    List<AgentTask> allTasks = taskRepository.findAll();
    for (AgentTask task : allTasks) {
        if (task.getTaskStatus() == TaskStatus.RUNNING
            || task.getTaskStatus() == TaskStatus.CLAIMED
            || task.getTaskStatus() == TaskStatus.COMMITTING) {
            task.markRecoveryRequired();
            taskRepository.save(task);
        }
    }
}
```
Tasks in `WAITING_FOR_REVIEW` or `APPROVED` are durable in SQLite and remain in their respective states across restarts without being invalidated.

---

## 4. Automated Verification Reference

- `eu.kalafatic.tryst.tests.CodeReviewAndCommitAuthorizationTest`
  - `testTaskStateTransitionsAndRecoveryRequired`: Validates full state transition sequence and `markRecoveryRequired()` behavior.
- `eu.kalafatic.tryst.tests.ApplicationRestartRecoveryTest`: Validates state persistence across restarts.
- `eu.kalafatic.tryst.tests.EndToEndAutonomousWorkflowIntegrationTest`: Validates autonomous execution ending in `WAITING_FOR_REVIEW`.
