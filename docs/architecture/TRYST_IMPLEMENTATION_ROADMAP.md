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

  P05: AI Development Execution Subsystem
       - Clear separation between AI provider transport and agent responsibilities
       - AIProvider abstraction & OllamaProvider (HTTP API /api/tags, /api/chat, streaming)
       - ModelRegistry, ModelSelectionService, PromptContextBuilder
       - TaskAnalyzer, ImplementationPlanner, CodeAgent, CodeChangeExecutor, ResultValidator
       - 10-step autonomous development workflow integrated into TaskWorker lifecycle
       - Timeouts, streaming diagnostics, cancellation, retries, and AiFailureClassification
       - Mock and live Ollama automated test suites

  P06: Interactive Web Code Review & Autonomous AI Rework Workflow
       - Multi-revision tracking and immutable ReviewRevision snapshots
       - Anchored ReviewComments with stable line/block/file context and resolution states
       - Web diff line selection and inline/global feedback composer with code context
       - Context-aware on-demand AI code change explanations
       - Scope-constrained iterative AI rework loop preserving public APIs and history
       - Cross-revision delta comparisons (arbitrary revision vs base revision)
       - In-app notification delivery on rework completion
       - Synchronized Workspace lifecycle (WAITING_FOR_REVIEW -> REWORKING -> WAITING_FOR_REVIEW)

[PENDING / SUBSEQUENT PHASES]
  P07: Structured JSON Tool Invocation Schema
       - Formal JSON schema tool calling for Ollama inference
       - Tool validation and hallucination resilience

  P07: Web Client Asset Decoupling
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

## 5. Completed AI Execution Subsystem Deliverables (Phase 5)

- **Domain Contracts & Subsystem Components:**
  - `AIProvider`: Transport abstraction decoupling AI provider communication from agent responsibilities.
  - `ModelRegistry` & `AiModelRegistryService`: Model registration, catalog updates, role-filtered model discovery.
  - `ModelSelectionService`: Task and role-aware model selection with fallback defaults.
  - `PromptContextBuilder`: Assembles prioritized, token-bounded context with workspace structure and file snippets.
  - `TaskAnalyzer`: Inspects request, detects build systems (Maven/Gradle/npm), identifies source files and conventions.
  - `ImplementationPlanner`: Formulates ordered multi-step implementation plans.
  - `CodeAgent`: Coordinates execution, tool commands, streaming callbacks, timeouts, cancellations, and retries.
  - `CodeChangeExecutor`: Executes file modifications strictly bounded within authorized workspace sandboxes and tracks modified files.
  - `ResultValidator`: Validates build compilation and test verification stages with failure classification.
  - `AiFailureClassification`: Detailed failure taxonomy (`TIMEOUT`, `CANCELLATION`, `BUILD_FAILURE`, `TEST_FAILURE`, etc.).
- **Infrastructure Provider:**
  - `OllamaProvider`: Pure Java 21 `HttpClient` client for `/api/tags` and `/api/chat` (synchronous and streaming).
  - `OllamaClient`: Backward-compatible adapter delegating to `OllamaProvider`.
- **TaskWorker Lifecycle Integration:**
  - Complete 10-step development workflow in `TaskWorker`:
    1. Analyze user request (`TaskAnalyzer`).
    2. Inspect repository structure & identify source files.
    3. Analyze dependencies and conventions.
    4. Generate implementation plan (`ImplementationPlanner`).
    5. Execute changes incrementally (`CodeAgent`, `CodeChangeExecutor`).
    6. Track every modified file.
    7. Run build verification (`ResultValidator`).
    8. Run relevant tests verification (`ResultValidator`).
    9. Persist analysis, implementation plans, execution events, and result logs into `task_events` and `execution_results`.
    10. Produce reviewable result with diff for `WAITING_FOR_REVIEW`.
- **Automated Verification:**
  - `AiExecutionSubsystemIntegrationTest` testing 10-step workflow, mock AI provider, model selection, timeouts, cancellations, retries, failure classifications, and Ollama HTTP endpoints.

---

## 6. Completed Interactive Web-Based Code Review Subsystem (Phase 6 / PROMPT 06)

- **Domain Contracts & Review Engine (`eu.kalafatic.tryst.domain.review`):**
  - `ReviewRevision`: Immutable snapshot of review iterations (diff, explanation, build/test status, revision number, timestamp).
  - `ReviewComment`: Persistent comments anchored to stable review context (`taskId`, `reviewId`, `revisionNumber`, `filePath`, `diffSide`, `startLine`, `endLine`, `codeContext`, `commentType`, `resolved`, `includeInRework`).
  - `DiffModel` & `DiffParser`: Unified and side-by-side diff models with hunk headers, old/new line mapping, file statistics (+/-), and cross-revision delta tracking (identifying newly introduced lines).
  - `CodeReview`: Extended with `aiExplanation` and `revisionCount`.
  - `CodeReviewRepository`: Extended with revision snapshot persistence and stable-context comment CRUD operations.
- **Persistence & Schema Migration V8 (`eu.kalafatic.tryst.infrastructure.persistence`):**
  - Added schema migration V8 creating `review_revisions` and `review_comments` tables with foreign keys and indexes, and adding `ai_explanation` and `revision_count` columns to `code_reviews`.
  - Implemented persistence and querying in `SqliteCodeReviewRepository`.
- **Autonomous Task Rework Loop:**
  - `TaskWorker`: Automatically captures `ReviewRevision` snapshots with AI implementation explanation, and filters unresolved comments marked for rework (`includeInRework=true`) to inject structured review rework instructions into the AI prompt.
  - `TaskOrchestrator`: Implemented `requestRework` method transitioning tasks from `WAITING_FOR_REVIEW` to `REWORK_REQUESTED` to `QUEUED`.
- **REST API & Web Review Center (`TrystHttpServer`):**
  - REST endpoints:
    - `GET /api/reviews/{id}/details`: Complete review metadata, file change statistics, revision list, and comments.
    - `GET /api/reviews/{id}/diff`: Structured unified or side-by-side rows with line numbers and delta comparison.
    - `GET /api/reviews/{id}/comments`, `POST /api/reviews/{id}/comments`, `PATCH /api/reviews/{id}/comments/{id}`, `DELETE /api/reviews/{id}/comments/{id}`: Full comment lifecycle management.
    - `POST /api/reviews/{id}/explain`: AI on-demand explanation of code modifications.
    - `POST /api/reviews/{id}/rework`: One-click rework dispatch.
  - IDE-inspired Web Review UI: Dark theme, file tree with change stats (+/-), unified/side-by-side toggle, interactive line selection, inline/file commenting, AI explanation drawer, and rework controls.
- **Automated Verification:**
  - `DiffParserTest`: Unified and side-by-side parsing, line mapping, hunk parsing, and revision delta comparison.
  - `ReviewCommentPersistenceTest`: CRUD operations, stable context anchoring, resolution, and rework filtering.
  - `WebCodeReviewSubsystemTest`: End-to-end HTTP integration testing for details, diff, comments, AI explain, rework queuing, and concurrent comment creation.

---

## 7. Completed Iterative Review and AI Rework Workflow Deliverables (P06 / Current Protocol)

- **Completed Requirements:**
  - Full feedback loop inside the web diff interface: line range selection (e.g. 120–135), code context anchoring, and inline/global instruction composition.
  - Multi-comment persistence per file and review with resolution/reopening toggles and rework inclusion flags (`includeInRework`).
  - Scoped prompt formatting in `TaskWorker` passing active review comments, line ranges, and strict scope constraints (avoid duplicate DB queries, preserve existing public APIs, keep unrelated code untouched).
  - Traceable revision history: every rework cycle increments revision count and creates a new immutable `ReviewRevision` without overwriting history.
  - Dynamic revision comparison: `/api/reviews/{id}/diff` supports comparing any target revision against any base revision via `revision` and `baseRevision` query parameters.
  - User notifications: `NotificationService` automatically notifies the project owner when rework completes and a new revision is published.
  - Workspace lifecycle synchronization: `Workspace` transitions through `WAITING_FOR_REVIEW -> REWORKING -> WAITING_FOR_REVIEW` with persistent state preservation.
- **Modified Modules:**
  - `eu.kalafatic.tryst.domain`: `TaskWorker`, `TaskOrchestrator`.
  - `eu.kalafatic.tryst.infrastructure`: `TrystHttpServer`.
  - `eu.kalafatic.tryst.application`: `ApplicationLifecycleCoordinator`.
  - `eu.kalafatic.tryst.tests`: Added `IterativeReviewAndReworkWorkflowTest`.
- **New Interfaces and Dependencies:**
  - Integrated `NotificationService` into `TaskOrchestrator` and `TaskWorker`.
- **Known Deviations:**
  - None. All architectural constraints, PDE configurations, and SQLite schema migrations are preserved.
- **Recommended Next Implementation Step:**
  - Structured JSON Tool Invocation Schema & Autonomous Self-Healing Tool Execution (P07).
