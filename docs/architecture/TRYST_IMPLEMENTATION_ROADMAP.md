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

  P04: GitHub Integration & Remote VCS Sandbox Management
       - Secure GitHub token validation and authentication via pure Java 21 HttpClient
       - Discovered repository (public/private) and branch management
       - Server-side AES-256-GCM credential encryption at rest and token masking
       - SQLite Schema Migration V7 (github_credentials table)
       - Isolated sandbox clone, fetch, branch creation, and conflict detection
       - Deterministic Git execution with GIT_TERMINAL_PROMPT=0
       - Explicit user-authorized push gated behind approved CodeReview
       - Rest API (/api/github/*) and UI controls

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

## 4. Completed GitHub Integration Deliverables (Phase 4 / VCS)

- **Domain Models & Contracts:** `GitHubCredential`, `GitHubRepository`, `GitHubBranch`, `GitHubCredentialEncryptionService`, `GitHubService`, `GitHubCredentialRepository`.
- **Encryption & Masking:** `DefaultGitHubCredentialEncryptionService` enforcing AES-256-GCM encryption with PBKDF2/HMAC-SHA256 key derivation at rest and strict token masking (`ghp_****...`).
- **REST & Git Operations:** `DefaultGitHubService` implementing pure Java 21 `HttpClient` operations for discovery (public/private repos, branches) and delegation to `GitService`.
- **Git Engine Hardening:** Extended `GitService` with `cloneOrFetch`, `checkout`, `createBranch`, `push`, remote conflict detection, interrupted clone recovery, and process argument/output sanitization with `GIT_TERMINAL_PROMPT=0`.
- **Persistence & Migration:** SQLite Migration V7 (`github_credentials` table with foreign key cascade to `users` and indexes on `user_id` and `github_username`), `SqliteGitHubCredentialRepository`.
- **Lifecycle & Orchestrator:** Integrated into `TaskWorker` during `CLONING` stage, wired in `TaskOrchestrator`, `ApplicationLifecycleCoordinator`, `TrystUiServices`.
- **REST API & Presentation:** Added `/api/github` endpoints to `TrystHttpServer`, added Web dashboard connect modal & push button, updated `ProjectsAndGitView` SWT view.
- **Verification:** Automated tests in `GitHubIntegrationTest` validating encryption, discovery, cloning, conflicts, interrupted clone recovery, and push authorization.

---

## 5. Recommended Next Implementation Step

- **Target:** Structured JSON Tool Invocation Schema.
- **Prerequisites:**
  1. Production-grade workspace and sandbox isolation verified (P03 completed).
  2. Isolated `TaskExecutionContext` bound to dedicated task sandboxes and GitHub VCS integration.
  3. Stable AI inference service and tool registry.
