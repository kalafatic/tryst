# TRYST Architecture Implementation Roadmap

**Document Identifier:** `TRYST-ARCH-ROADMAP-001`
**Status:** APPROVED BASELINE
**Classification:** Implementation Roadmap

---

## 1. Prioritized Defect and Architectural Debt Inventory

The audit of the codebase and implementation progress tracks the following components:

```
[COMPLETED]
  P01: Architecture Baseline & Governance Alignment
       - Unified namespace (eu.kalafatic.tryst)
       - Clean MVC and Equinox OSGi modularity
       - Authoritative target platform and PDE configuration
       - Database schema migrations V1-V4
       - Embedded review center and administrative perspectives

  P02: Production-Grade Asynchronous Execution Infrastructure
       - Deterministic multi-stage lifecycle (TaskStatus, TaskStateTransitionValidator)
       - Canonical Task aggregate and backward-compatible AgentTask
       - Isolated TaskExecutionContext (no shared mutable currentTask or workspace)
       - Persistent priority TaskQueue and atomic claiming
       - Controlled TaskWorker and central TaskOrchestrator
       - TaskLifecycleManager with persistent TaskEvent auditing (Schema Migration V5)
       - Server restart recovery via TaskRecoveryService
       - Asynchronous HTTP API (/api/tasks, cancellation, events)
       - Concurrency, cancellation, and recovery automated test suites

  P03: Workspace and Sandbox Management
       - Multi-user and multi-task workspace isolation
       - 10-state Workspace lifecycle (ALLOCATED -> CLEANUP_PENDING -> FAILED)
       - Strict canonical path and symlink escape validation
       - SQLite Schema Migration V6 (workspaces metadata persistence)
       - Non-destructive workspace cleanup and crash-restart recovery

[PENDING / SUBSEQUENT PHASES]
  P04: Structured JSON Tool Invocation Schema
       - Formal JSON schema tool calling for Ollama inference
       - Tool validation and hallucination resilience

  P05: Web Client Asset Decoupling
       - Static asset separation into modular resources
       - Bundle classloader asset resolution
```

---

## 2. Completed Phase 2 Deliverables (P02)

- **Entities & Events:** `Task`, `AgentTask`, `TaskStatus`, `TaskEvent`, `TaskPriority`.
- **Validation & Isolation:** `TaskStateTransitionValidator`, `TaskExecutionContext`.
- **Execution & Queue:** `TaskQueue`, `TaskWorker`, `TaskOrchestrator`.
- **Lifecycle & Recovery:** `TaskLifecycleManager`, `TaskRecoveryService`.
- **Persistence:** SQLite Migration V5 (`task_events`, task execution columns, priority indexes), `SqliteTaskRepository`.
- **Application & API Integration:** `ApplicationLifecycleCoordinator` (OSGi services, startup recovery, graceful shutdown) and `TrystHttpServer`.
- **Testing:** `TaskLifecycleTransitionTest`, `TaskQueueConcurrencyTest`, `TaskOrchestratorIntegrationTest`, `AsynchronousTaskExecutionHttpE2ETest`.

---

## 3. Completed Phase 3 Deliverables (P03)

- **Workspace Lifecycle & State Machine:**
  - `WorkspaceStatus` with 10 states: `ALLOCATED`, `INITIALIZING`, `READY`, `ACTIVE`, `WAITING_FOR_REVIEW`, `REWORKING`, `COMPLETED`, `ARCHIVED`, `CLEANUP_PENDING`, `FAILED`.
  - `WorkspaceStateTransitionValidator` enforcing valid deterministic state transitions.
  - State mutators on `Workspace` entity (`allocate`, `initialize`, `markReady`, `activate`, `markWaitingForReview`, `rework`, `complete`, `archive`, `markCleanupPending`, `fail`).
- **Workspace Model & Multi-Tenant Metadata:**
  - Extended `Workspace` with `ownerId`, `taskId`, `repositoryUrl`, `branch`, and flexible `metadata`.
- **Persistence & Migration:**
  - SQLite Migration V6 (`workspaces` table updated with `owner_id`, `task_id`, `status`, `repository_url`, `branch`, `metadata_json`, and indexes).
  - Enhanced `WorkspaceRepository` and `SqliteWorkspaceRepository` with `findByTaskId`, `findByOwnerId`, and `findByStatus`.
- **Sandbox Management & Protection:**
  - `WorkspaceService.allocateTaskWorkspace()` allocating per-task isolated sandboxes (`~/.tryst/workspace/sandboxes/<ownerId>/<taskId>`).
  - Protected non-destructive cleanup: refusing to delete workspaces containing active or review-pending work (`ACTIVE`, `INITIALIZING`, `WAITING_FOR_REVIEW`, `REWORKING`).
  - `WorkspaceRecoveryService` reconciling interrupted workspaces on boot while preserving `WAITING_FOR_REVIEW` work.
  - Canonical real-path resolution, symlink traversal escape prevention, and null-byte defenses in `PermissionManager`.
- **Orchestration Integration:**
  - Dedicated task-scoped workspace resolution in `TaskOrchestrator` and execution state synchronization in `TaskWorker`.
- **Automated Verification:**
  - `WorkspaceLifecycleTest`, `WorkspacePersistenceAndRecoveryTest`, `WorkspaceSandboxIsolationTest`.

---

## 4. Recommended Next Implementation Step (P04)

- **Target:** Structured JSON Tool Invocation Schema (P04).
- **Prerequisites:**
  1. Production-grade workspace and sandbox isolation verified (P03 completed).
  2. Isolated `TaskExecutionContext` bound to dedicated task sandboxes.
  3. Stable AI inference service and tool registry.
