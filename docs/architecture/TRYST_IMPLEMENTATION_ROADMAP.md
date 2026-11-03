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

[PENDING / SUBSEQUENT PHASES]
  P03: Workspace and Sandbox Management
       - Project and workspace containment isolation hardening
       - Elimination of remaining legacy workspace singletons
       - Sandboxed environment preparation and tooling readiness checks

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

## 3. Recommended Next Implementation Step (P03)

- **Target:** Workspace and Sandbox Management (P03).
- **Prerequisites:**
  1. Verified asynchronous task execution infrastructure (P02 completed).
  2. Isolated `TaskExecutionContext` already receiving per-task project workspace paths.
  3. Clean transactional persistence in SQLite (Migration V5 applied).
