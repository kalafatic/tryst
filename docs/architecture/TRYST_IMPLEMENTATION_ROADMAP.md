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

  P07: Comprehensive Security Architecture & Defense-in-Depth
       - Explicit PermissionDecision (ALLOW, DENY) with strict DENY precedence
       - Server-side RBAC and service boundary validations across projects, workspaces, tasks, and reviews
       - Sliding-window rate limiting on sensitive authentication and task endpoints
       - Cryptographic CSRF token management and verification for cookie-authenticated sessions
       - HTTP Security Headers (X-Content-Type-Options, X-Frame-Options, CSP, Referrer-Policy, Permissions-Policy)
       - Client header spoofing protection (rejecting unauthenticated X-User-Id overrides)
       - Canonical workspace containment, symlink escape protection, and null-byte defenses
       - Automated authorization and penetration test suite (SecurityArchitectureAndPenetrationTest)

  P10: Robust Build and Quality Assurance Subsystem (Phase 10)
       - Multi-technology & build system detection (Maven, Gradle, npm, Cargo, Python, custom .tryst-build.json)
       - Command resolution for build and automated tests without assuming Maven
       - Isolated execution in assigned sandbox with strict path containment via PermissionManager
       - Controlled environment variables and binary whitelisting
       - Subprocess tracking and cross-workspace access prevention via ProcessExecutionManager
       - Configurable timeouts and cooperative cancellation
       - Exit code capture, stdout/stderr separation, and streaming log diagnostics
       - Compilation and test failure classification
       - Structured QualityReport distinguishing NOT_EXECUTED, SUCCESSFUL, FAILED, CANCELLED, TIMED_OUT
       - Gated review revision results ensuring builds without tests are not reported as passed tests

[PENDING / SUBSEQUENT PHASES]
  P11: Structured JSON Tool Invocation Schema
       - Formal JSON schema tool calling for Ollama inference
       - Tool validation and hallucination resilience

  P12: Web Client Asset Decoupling
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
  - Comprehensive Persistence & Fault Recovery Implementation (Phase 8).

---

## 8. Completed Comprehensive Persistence & Fault Recovery Implementation (Phase 8 / Current Protocol)

- **Completed Requirements:**
  - Full survival and recovery from browser disconnection, client reconnection, JVM restart, Eclipse RCP restart, interrupted AI requests, interrupted Git operations, interrupted builds, database connection failures, and unexpected OS termination.
  - Recovery Checkpointing & Interrupted Operation Classification:
    - Introduced `TaskRecoveryCheckpoint` immutable model and SQLite schema migration V9 creating `task_recovery_checkpoints` table with indexes and foreign keys.
    - Introduced `InterruptedOperationClassification` taxonomy (`INTERRUPTED_GIT_OPERATION`, `INTERRUPTED_AI_REQUEST`, `INTERRUPTED_BUILD`, `INTERRUPTED_DATABASE_TX`, `CLIENT_DISCONNECTION`, `OS_TERMINATION`).
    - Introduced `TaskRecoveryDecision` (`SAFE_TO_RESUME`, `RECONCILE_AND_RETRY`, `REQUIRES_USER_INTERVENTION`) preventing naive replay of non-idempotent operations.
    - Extended `TaskRecoveryService` with granular assessment, checkpointing, and safe recovery decision logic.
  - Database Consistency & Referential Integrity:
    - Extended `SqliteDatabaseManager` with `verifyDatabaseConsistency()` executing `PRAGMA foreign_key_check`, `PRAGMA quick_check`, and `PRAGMA integrity_check`.
    - Added WAL checkpointing `checkpointWal()` (`PASSIVE`, `FULL`, `TRUNCATE`).
  - Online Backup & Verification:
    - Extended `SqliteBackupService` with backup verification (`verifyBackup`) and restore functionality (`restoreFromBackup`).
  - Retention & Archival Policies:
    - Implemented `RetentionPolicy` and `DataRetentionService` with `SqliteRetentionStore` running transactional pruning of expired user sessions, stale telemetry snapshots, old audit records, and task events.
  - REST & Administration Endpoints:
    - Added `/api/admin/database/integrity` (run consistency and foreign key checks).
    - Added `/api/admin/database/retention` (execute transactional retention policy pruning).
- **Modified Modules:**
  - `eu.kalafatic.tryst.domain`: `TaskRecoveryCheckpoint`, `TaskRecoveryDecision`, `InterruptedOperationClassification`, `TaskRecoveryCheckpointRepository`, `RetentionPolicy`, `DataRetentionService`, `TaskRecoveryService`, `TaskWorker`, `TaskOrchestrator`.
  - `eu.kalafatic.tryst.infrastructure`: `SchemaMigrationService` (V9), `SqliteTaskRecoveryCheckpointRepository`, `SqliteRetentionStore`, `SqliteDatabaseManager`, `SqliteBackupService`, `TrystHttpServer`.
  - `eu.kalafatic.tryst.application`: `ApplicationLifecycleCoordinator`.
  - `eu.kalafatic.tryst.tests`: Added `ComprehensivePersistenceAndRecoveryTest`, updated `SchemaMigrationTest`.
- **New Interfaces and Dependencies:**
  - `TaskRecoveryCheckpointRepository`, `DataRetentionService.RetentionStore`.
- **Known Deviations:**
  - None. All architectural constraints, PDE configurations, and SQLite schema migrations are preserved.
- **Recommended Next Implementation Step:**
  - Centralized Notification & Real-Time Communication Subsystem (Phase 9).

---

## 9. Completed Centralized Notification and Real-Time Communication Subsystem (Phase 9 / Current Protocol)

- **Completed Requirements:**
  - Centralized, authoritative notification dispatch independent of active browser connections.
  - Complete coverage for all 12 asynchronous task lifecycle events:
    1. `TASK_ACCEPTED`: Dispatched when task is submitted and queued in `TaskOrchestrator`.
    2. `REPOSITORY_CLONED`: Dispatched in `TaskWorker` after repository environment is prepared.
    3. `AI_ANALYSIS_COMPLETED`: Dispatched in `TaskWorker` after workspace and conventions analysis.
    4. `IMPLEMENTATION_STARTED`: Dispatched in `TaskWorker` before incremental file modifications.
    5. `BUILD_COMPLETED`: Dispatched in `TaskWorker` after compiler verification passes.
    6. `TESTS_FAILED`: Dispatched in `TaskWorker` when validation fails test verification.
    7. `REVIEW_READY`: Dispatched in `TaskWorker` when reviewable diff enters `WAITING_FOR_REVIEW`.
    8. `REVIEW_COMMENTS_RECEIVED`: Dispatched in `TrystHttpServer` when reviewers submit feedback comments.
    9. `REWORK_COMPLETED`: Dispatched in `TaskWorker` upon completing autonomous rework iterations.
    10. `APPROVAL_REQUIRED`: Dispatched when human code review authorization is required before commit.
    11. `COMMIT_COMPLETED`: Dispatched in `TrystHttpServer` upon authorized Git commit.
    12. `TASK_FAILED`: Dispatched in `TaskWorker` on unrecoverable execution failure.
  - Per-user notification ownership and strict multi-tenant isolation.
  - Persistent read/unread status and unread counter queries.
  - Sequential notification history and task-specific querying (`/api/notifications?taskId=...`).
  - Server-side duplicate notification prevention using atomic `dedup_key` indexing and `saveIfAbsent()`.
  - Configurable notification preferences: per-user toggle for browser notifications, in-app notifications, and granular event type filtering (`notification_preferences` table).
  - Real-time updates via Server-Sent Events (`GET /api/notifications/stream`) with `Last-Event-ID` reconnection replay of missed notifications.
  - Modern web UI notification bell, badge counter, dropdown panel, and native browser Notification API integration (`Notification.requestPermission()`).
- **Modified Modules:**
  - `eu.kalafatic.tryst.core`: `ApplicationEvents` (`NotificationBroadcastEvent`).
  - `eu.kalafatic.tryst.domain`: `Notification`, `NotificationType`, `NotificationPreference`, `NotificationRepository`, `NotificationPreferenceRepository`, `NotificationService`, `TaskOrchestrator`, `TaskWorker`.
  - `eu.kalafatic.tryst.infrastructure`: `SchemaMigrationService` (V10), `SqliteNotificationRepository`, `SqliteNotificationPreferenceRepository`, `TrystHttpServer`.
  - `eu.kalafatic.tryst.application`: `ApplicationLifecycleCoordinator`.
  - `eu.kalafatic.tryst.tests`: Added `CentralizedNotificationSubsystemTest`, updated `SchemaMigrationTest`, `DatabaseInitializationTest`, `ServerBootstrapAndNetworkingTest`.
- **New Interfaces and Dependencies:**
  - `NotificationPreferenceRepository`.
- **Known Deviations:**
  - None. All architectural constraints, PDE configurations, and SQLite schema migrations are preserved.
- **Recommended Next Implementation Step:**
  - Robust Build and Quality Assurance Subsystem (Phase 10).

---

## 10. Completed Robust Build and Quality Assurance Subsystem (Phase 10 / Current Protocol)

- **Completed Requirements:**
  - Technology and build system detection across diverse ecosystems (Maven with `./mvnw`/`mvn`, Gradle with `./gradlew`/`gradle`, Node/npm, Cargo/Rust, Python, and custom workspace-level configuration).
  - Project-specific build and test configuration support via `.tryst-build.json` without introducing third-party build orchestrators.
  - Safe subprocess execution confined strictly to the assigned workspace sandbox with path validation via `PermissionManager.validatePathWithinWorkspace()`.
  - Process ownership tracking and prevention of cross-workspace process access via `ProcessExecutionManager`.
  - Security constraints: binary whitelisting, stripping of sensitive credential environment variables (tokens, keys, secrets), and non-interactive environment flags (`CI=true`, `GIT_TERMINAL_PROMPT=0`).
  - Configurable timeouts and cooperative cancellation cleanly destroying process trees.
  - Explicit capture of exit codes, stdout, and stderr with real-time log streaming.
  - Distinguishing compilation failures from test failures, and generating structured `QualityReport`.
  - Every `ReviewRevision` records validated build status and test results, ensuring successful builds without tests are explicitly recorded with `NOT_EXECUTED` rather than falsely reported as passed tests.
  - Integration with `TaskOrchestrator`, `TaskWorker`, `WorkspaceService`, `TrystHttpServer`, and `ApplicationLifecycleCoordinator`.
- **Modified Modules:**
  - `eu.kalafatic.tryst.domain`: Added package `eu.kalafatic.tryst.domain.build` with `BuildSystemType`, `BuildExecutionStatus`, `StageExecutionResult`, `BuildConfiguration`, `QualityReport`, `BuildTechnologyDetector`, `BuildAndQualityService`. Updated `ResultValidator`, `TaskWorker`, and `TaskOrchestrator`. Updated `MANIFEST.MF`.
  - `eu.kalafatic.tryst.infrastructure`: Added `DefaultBuildTechnologyDetector`. Hardened `BuildAndTestService` implementing `BuildAndQualityService`. Updated `TrystHttpServer` review build-test endpoint.
  - `eu.kalafatic.tryst.application`: Wired `BuildAndQualityService` into `TaskOrchestrator` and OSGi Service Registry in `ApplicationLifecycleCoordinator`.
  - `eu.kalafatic.tryst.tests`: Added `BuildAndQualityAssuranceSubsystemTest`.
- **New Interfaces and Dependencies:**
  - `BuildAndQualityService`, `BuildTechnologyDetector`.
- **Outstanding Requirements:**
  - None.
- **Known Deviations:**
  - None.
- **Recommended Next Implementation Step:**
  - Structured JSON Tool Invocation Schema (Phase 11).
