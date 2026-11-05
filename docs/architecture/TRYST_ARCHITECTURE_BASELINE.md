# TRYST Target Architecture Baseline

**Document Identifier:** `TRYST-ARCH-BASELINE-001`
**Status:** APPROVED BASELINE
**Classification:** Definitive Architecture Specification
**Java Compliance:** Java 21 LTS
**Target Platform:** Eclipse RCP / Equinox OSGi (Eclipse 2024-03)
**Maven / Tycho:** Tycho 4.0.8

---

## 1. Executive Summary and System Context

**TRYST** is a self-hosted, autonomous AI software development platform inspired by Google Jules, built natively on the Java 21 Eclipse Rich Client Platform (RCP) and Equinox OSGi runtime. It operates as an autonomous development engine capable of:
- Managing AI agents and prompt-to-code pipelines (primarily backed by local Ollama instances).
- Orchestrating tasks, sessions, and multi-step plan execution.
- Interacting safely with local Git repositories and executing builds/tests.
- Enforcing cryptographically verifiable code reviews before commits are applied.
- Serving an interactive, responsive web application for administration and code reviews.
- Running concurrently in both interactive Eclipse RCP desktop mode and headless server daemon mode.
- Maintaining project and workspace isolation across multiple tenants.

TRYST deliberately avoids heavy frameworks like Spring, Spring Boot, Node.js, Python, or Electron, relying on modern Java 21 language and platform capabilities, standard library HTTP engines, embedded SQLite persistence, and Equinox OSGi modularity.

Furthermore, TRYST Core is completely decoupled from EVO, permitting future integration into EVO as an optional pluggable consumer without introducing any upstream dependency on EVO.

---

## 2. Core Architectural Principles and Constraints

1. **Strict Namespace Discipline:**
   All packages, bundles, feature IDs, and Maven artifacts use exclusively the `eu.kalafatic.tryst` namespace. No legacy or alternative identifiers (`eu.tryst`, `com.tryst`, `org.tryst`) are permitted.
2. **Strict MVC Separation:**
   - **Model:** Independent of UI and presentation (`eu.kalafatic.tryst.domain.model`).
   - **View:** SWT/JFace viewers and perspectives in RCP (`eu.kalafatic.tryst.ui.view`), and lightweight HTML/CSS/vanilla JS in the web tier. Views never execute business logic or mutate state directly.
   - **Controller:** Translates user actions into service operations (`eu.kalafatic.tryst.ui.controller` and HTTP REST handlers).
3. **Decoupled Equinox OSGi Modularity:**
   Services communicate through clear public Java interfaces registered in the native OSGi Service Registry. UI and domain bundles discover services dynamically via `ServiceTracker` or OSGi Declarative Services, avoiding static singleton coupling.
4. **Pure Java 21 Standard Library Capabilities:**
   Network communication (HTTP server and client) uses Java 21 standard components (`com.sun.net.httpserver` and `java.net.http.HttpClient`) with zero third-party web frameworks.
5. **Relational Persistence with Zero ORM Overhead:**
   SQLite 3 with standard JDBC is used. Transactions are explicitly demarcated via `UnitOfWork` / `SqliteDatabaseManager`. Schema evolution is strictly managed via sequential `SchemaMigrationService` migrations.
6. **Defense in Depth and Project Isolation:**
   File access is confined to project workspace roots using canonical real paths (`Path.toRealPath()`) to thwart directory traversal and symlink escapes. Git commits require an approved `CodeReview` whose SHA-256 fingerprint matches the working tree diff.

---

## 3. High-Level Architectural Overview

```
 +-------------------------------------------------------------------------------+
 |                       Presentation & Delivery Tier                            |
 |  +-------------------------------------+  +--------------------------------+  |
 |  |     Eclipse RCP Workbench (SWT)     |  |       Web Client (HTML/JS)     |  |
 |  | MainPerspective / AdminPerspective  |  | Review Center & Admin Dashboard|  |
 |  | ProjectExplorer, Browser, Logs UI   |  | Pure Vanilla JavaScript & CSS  |  |
 |  +-------------------------------------+  +--------------------------------+  |
 +---------------------------------------+---------------------------------------+
                                         |
                                         v
 +-------------------------------------------------------------------------------+
 |                       Infrastructure Tier (eu.kalafatic.tryst.infrastructure) |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  |  TrystHttpServer   |  |   GitService       |  |   BuildAndTestService   |  |
 |  |  - REST Endpoints  |  |   - Status / Diff  |  |   - Multi-technology QA |  |
 |  |  - Static Assets   |  |   - Gated Commits  |  |     sandbox build & test|  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  |    OllamaClient    |  | SqliteDatabaseMgr  |  |  Sqlite Repositories    |  |
 |  |  - Pure Java 21    |  | - WAL Mode, UoW    |  |  - Users, Tasks, Reviews|  |
 |  |    HttpClient      |  | - Schema Migrations|  |  - Telemetry, Audit Logs|  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 +---------------------------------------+---------------------------------------+
                                         |
                                         v
 +-------------------------------------------------------------------------------+
 |                           Domain Tier (eu.kalafatic.tryst.domain)             |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  |   Domain Models    |  |  Security & Auth   |  |   Operational Services  |  |
 |  |  - Project, User   |  |  - Authentication  |  |   - TelemetryService    |  |
 |  |  - AgentTask       |  |  - PasswordHasher  |  |   - MonitoringService   |  |
 |  |  - CodeReview      |  |  - PermissionMgr   |  |   - ProcessExecutionMgr |  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  |  Autonomous Dev    |  | SessionTaskOrch.   |  |   Tool Registry         |  |
 |  |  - Task Execution  |  | - Context Manager  |  |   - FileTools           |  |
 |  |  - Review Trigger  |  | - Agent Execution  |  |   - GitTools            |  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 +---------------------------------------+---------------------------------------+
                                         |
                                         v
 +-------------------------------------------------------------------------------+
 |                             Core Tier (eu.kalafatic.tryst.core)               |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  |   Async Execution  |  |  Event Bus         |  |   Configuration         |  |
 |  |  - Thread Pool     |  |  - In-memory typed |  |   - Runtime Registry    |  |
 |  |  - Managed workers |  |    event bus       |  |   - tryst.properties    |  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  +--------------------+  +--------------------+  +-------------------------+  |
 |  | Lifecycle Stages   |  | Server Readiness   |  |   Error Hierarchy       |  |
 |  |  - Deterministic   |  | - Health indicators|  |   - Domain, Infra, App  |  |
 +-------------------------------------------------------------------------------+
```

---

## 4. Module Specifications and Responsibilities

### 4.1 `eu.kalafatic.tryst.core`
- **Responsibility:** Foundational runtime primitives, logging, configuration management, asynchronous thread pools, in-memory typed event dispatching, server readiness tracking, and root exception hierarchies.
- **Public Interfaces / Types:**
  - `EventBus`, `TrystTypedEvent`, `ApplicationEvents`
  - `AsyncExecutionService`, `ManagedAsyncExecutionService`
  - `ApplicationConfiguration`, `ConfigurationService`, `RuntimeEndpointRegistry`
  - `ServerReadinessService`, `ApplicationLifecycleStage`, `ApplicationLifecycleListener`
  - `TrystLog`, `TrystDomainException`, `TrystInfrastructureException`, `TrystApplicationException`
- **Dependencies:** Standard Java SE 21, `org.eclipse.osgi`, `org.eclipse.equinox.common`, `org.eclipse.core.runtime`. Zero internal project dependencies.
- **Lifecycle Ownership:** First bundle initialized. Instantiates daemon executor pool and endpoint registry.
- **Threading Model:** Bounded worker pool (`Executors.newFixedThreadPool`) with graceful termination; event bus delivers events on the publishing thread or worker pool.
- **Persistent Data:** Operational configuration stored at `~/.tryst/tryst.properties`.
- **Failure Handling:** Fails fast on unrecoverable configuration errors.

### 4.2 `eu.kalafatic.tryst.domain`
- **Responsibility:** Pure business logic, aggregate roots, domain value objects, repository contracts, tool abstractions, RBAC permissions, password hashing, and operational monitoring services.
- **Public Interfaces / Types:**
  - Models: `Project`, `Workspace`, `AgentTask`, `Session`, `Agent`, `ConversationMessage`, `ExecutionResult`, `CodeReview`, `ReviewFileEdit`, `User`, `UserSession`, `AuditEvent`, `Notification`, `TelemetrySnapshot`, `ManagedProcessRecord`.
  - Repositories: `ProjectRepository`, `WorkspaceRepository`, `TaskRepository`, `SessionRepository`, `ConversationMessageRepository`, `ExecutionResultRepository`, `AgentRepository`, `CodeReviewRepository`, `UserRepository`, `UserSessionRepository`, `AuditEventRepository`, `NotificationRepository`, `TelemetryRepository`, `UnitOfWork`.
  - Services: `WorkspaceService`, `SessionTaskOrchestrator`, `AutonomousDevelopmentService`, `CodeReviewService`, `AuthenticationService`, `PermissionManager`, `PasswordHasher`, `AiContextManager`, `AiInferenceService`, `AiModelRegistryService`, `ServerMonitoringService`, `TelemetryService`, `AiMonitoringService`, `ProcessExecutionManager`, `NotificationService`, `LogDiagnosticService`, `BuildAndQualityService`, `BuildTechnologyDetector`.
  - Tools: `Tool`, `ToolRegistry`, `FileTools`, `GitTools`.
- **Dependencies:** `eu.kalafatic.tryst.core`. Strict prohibition of any UI or presentation packages.
- **Lifecycle Ownership:** Managed by `ApplicationLifecycleCoordinator` during `CORE_SERVICE_INITIALIZATION`.
- **Threading Model:** Stateless service logic with thread-safe data structures (`ConcurrentHashMap`, `CopyOnWriteArrayList`, `ReentrantLock`).
- **Persistent Data:** Defined strictly via Repository abstractions.
- **Failure Handling:** Validates invariants using `TrystDomainException`. Rejects unauthorized access with explicit security audit entries.

### 4.3 `eu.kalafatic.tryst.infrastructure`
- **Responsibility:** Technology-specific implementations of domain contracts: SQLite persistence, schema migrations, Git command execution, isolated Maven build/test runners, embedded HTTP/TCP servers, and Ollama AI inference.
- **Public Interfaces / Types:**
  - Persistence: `SqliteDatabaseManager`, `SchemaMigrationService`, `SqliteBackupService`, and SQLite repository implementations (`SqliteProjectRepository`, `SqliteTaskRepository`, `SqliteCodeReviewRepository`, `SqliteUserRepository`, `SqliteGitHubCredentialRepository`, etc.).
  - AI: `OllamaClient`.
  - VCS & Build: `GitService`, `BuildAndTestService`, `DefaultBuildTechnologyDetector`, `DefaultGitHubService`, `DefaultGitHubCredentialEncryptionService`.
  - Server: `TrystHttpServer`, `TrystHttpService`, `TrystTcpServer`, `TrystTcpService`.
- **Dependencies:** `eu.kalafatic.tryst.core`, `eu.kalafatic.tryst.domain`, `org.sqlite.JDBC`, `slf4j.api`.
- **Lifecycle Ownership:** HTTP and TCP servers, database connections, and background backup tasks are managed and shutdown cleanly by `ApplicationLifecycleCoordinator`.
- **Threading Model:** `HttpServer` uses dedicated thread executors; `SqliteDatabaseManager` enforces single-writer / serialized transaction boundaries via internal monitor locks (`databaseManager.getLock()`).
- **Persistent Data:** SQLite database at `~/.tryst/tryst.db` with WAL mode enabled.
- **Failure Handling:** Wraps SQL, process, and I/O failures in `TrystInfrastructureException`. Recovers automatically from dynamic port collisions via fallback port ranges.

### 4.4 `eu.kalafatic.tryst.application`
- **Responsibility:** Application entry points, Equinox OSGi bundle activation, deterministic lifecycle stages, desktop RCP Workbench advisors, and headless server execution.
- **Public Interfaces / Types:**
  - `TrystApplication` (dual-mode launcher: checks `-headless` or `tryst.headless=true` and delegates to server mode or starts SWT display).
  - `TrystServerApplication` (headless Equinox `IApplication` executing daemon loop).
  - `ApplicationLifecycleCoordinator` (coordinates stages, boots SQLite, migrates schema, registers OSGi services).
  - `ApplicationWorkbenchAdvisor`, `ApplicationWorkbenchWindowAdvisor`, `ApplicationActionBarAdvisor`.
- **Dependencies:** `eu.kalafatic.tryst.core`, `eu.kalafatic.tryst.domain`, `eu.kalafatic.tryst.infrastructure`, `org.eclipse.ui`, `org.eclipse.equinox.app`.
- **Lifecycle Ownership:** Owns top-level OSGi bundle lifecycle and system shutdown hooks.
- **Threading Model:** Main execution thread manages OSGi application lifecycle; dispatches workbench tasks to SWT event loop or blocks headless server thread until shutdown signal.
- **Persistent Data:** Coordinates persistence initialization.
- **Failure Handling:** Intercepts unhandled exceptions, reconciles interrupted tasks to `RECOVERY_REQUIRED`, and ensures clean release of OSGi service registrations.

### 4.5 `eu.kalafatic.tryst.ui`
- **Responsibility:** Native Eclipse RCP views, controllers, status lines, and administration perspectives.
- **Public Interfaces / Types:**
  - Controllers: `ProjectExplorerController`, `WorkspaceController`, `ConsoleOutputController`.
  - Perspectives: `TrystMainPerspective`, `TrystAdministrationPerspective`.
  - Views: `ProjectExplorerView`, `MainWorkspaceView`, `ConsoleOutputView`, `EmbeddedBrowserView`, `ServerOverviewView`, `UsersAndSessionsView`, `UserDetailView`, `SessionDetailView`, `TasksAndAgentsView`, `ProjectsAndGitView`, `AiMonitoringView`, `TelemetryView`, `ManagedProcessesView`, `AuditAndLogsView`, `DatabaseAdminView`.
  - Services Locator: `TrystUiServices`.
- **Dependencies:** `eu.kalafatic.tryst.core`, `eu.kalafatic.tryst.domain`, `eu.kalafatic.tryst.infrastructure`, `org.eclipse.ui`, `org.eclipse.jface`.
- **Lifecycle Ownership:** `Bundle-ActivationPolicy: lazy`; views acquire services via dynamic OSGi service trackers.
- **Threading Model:** SWT UI thread for UI rendering; background operations offloaded to `AsyncExecutionService` with UI updates dispatched via `Display.asyncExec()`.
- **Persistent Data:** None (UI state only; delegates to domain models).
- **Failure Handling:** Displays informative error dialogs and logs to `ConsoleOutputView` without crashing the RCP shell.

---

## 5. Domain Model and Task Lifecycle Architecture

### 5.1 Domain Model Principles
- Every aggregate entity uses a UUID String for identity.
- Invariants are checked at creation and mutation; illegal states throw `TrystDomainException`.
- Explicit state transition methods (e.g., `task.start()`, `task.markWaitingForReview()`, `task.markApproved()`) replace generic setters.
- All timestamps use `java.time.Instant`.

### 5.2 Task Lifecycle State Machine
Tasks follow an explicit lifecycle supporting asynchronous, autonomous execution:

```
                  +-----------------------------------+
                  |              CREATED              |
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |              PLANNED              |
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |               READY               |
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |              QUEUED               |
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |              CLAIMED              |
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |              RUNNING              |
                  +-----------------------------------+
                     /              |              \
                    /               |               \
                   v                v                v
  +--------------------+  +--------------------+  +--------------------+
  |WAITING_FOR_APPROVAL|  | WAITING_FOR_REVIEW |  |       FAILED       |
  +--------------------+  +--------------------+  +--------------------+
            |                       |
            |                       v
            |             +--------------------+
            |             |      APPROVED      |
            |             +--------------------+
            |                       |
            |                       v
            |             +--------------------+
            |             |     COMMITTING     |
            |             +--------------------+
            \                       |
             \                      v
              \           +--------------------+
               ---------> |     COMPLETED      |
                          +--------------------+

  Any active state (RUNNING, CLAIMED, COMMITTING) on system restart -> RECOVERY_REQUIRED
  Any active state on manual abort -> CANCELLED
```

---

## 6. Security, Authentication, and Workspace Isolation

1. **Password Hashing:** PBKDF2 with HMAC-SHA256, 210,000 iterations, 32-byte salt, 256-bit derived key.
2. **Session Security:** 256-bit cryptographically secure bearer tokens stored server-side strictly as SHA-256 hashes. Sliding session expiration with brute-force lockout (5 failed attempts).
3. **Role-Based Access Control (RBAC):**
   - Roles: `ADMIN`, `PROJECT_OWNER`, `DEVELOPER`, `REVIEWER`, `USER`.
   - Granular permissions enforced at the application service boundary via `SecurityContext`.
4. **Workspace Containment:**
   - Real-path canonical resolution (`Path.toRealPath()`) validates that all file operations remain strictly inside the authorized workspace root.
   - Symlink escape attempts are detected and rejected.
5. **Git Commit Gating:**
   - Automatic commits by AI agents are strictly forbidden.
   - Commits require a human-approved `CodeReview`.
   - SHA-256 diff fingerprint verification ensures that if the working tree changes after approval, the commit is aborted.

---

## 7. Web Application and API Architecture

- **HTTP Engine:** Pure Java 21 `com.sun.net.httpserver.HttpServer`.
- **Port Management:** Configured port with automatic fallback range (e.g. 8080-8180) and ephemeral port 0 on collision. Bound URLs published to `RuntimeEndpointRegistry`.
- **Canonical Decoupled URL:** Browser connects to `http://localhost/tryst`, which routes to the dynamic internal listener.
- **REST API Endpoints:**
  - `/api/status`: System health and component readiness.
  - `/api/setup`: Single-use setup token administrator provisioning.
  - `/api/auth/login`, `/api/auth/logout`, `/api/auth/me`: Session lifecycle.
  - `/api/models`: Role-filtered published AI models.
  - `/api/projects`: Project and workspace management.
  - `/api/tasks`: Autonomous task creation and dispatch.
  - `/api/reviews`: Diff inspection, file-level modifications, review approval/rejection, and gated commit execution.
  - `/api/notifications`: User notification feed and acknowledgments.
  - `/api/admin/*`: Database VACUUM/PRAGMA checks, telemetry, process control, and log diagnostics.
- **Frontend Delivery:** Static assets (HTML, CSS, vanilla JS) served directly from memory or bundle resources, providing responsive Code Review and Administration dashboards with zero external runtime dependencies.

---

## 8. Persistence Architecture

- **Engine:** SQLite 3 in Write-Ahead Logging (`WAL`) mode with `synchronous = NORMAL` and `foreign_keys = ON`.
- **Database Location:** `~/.tryst/tryst.db`.
- **Schema Migrations:**
  - **V1:** Core schema (`projects`, `workspaces`, `sessions`, `tasks`, `conversation_messages`, `execution_results`, `agents`).
  - **V2:** Project isolation & Code Review Center (`code_reviews`, `review_file_edits`).
  - **V3:** Security & operational intelligence (`users`, `user_sessions`, `audit_events`, `telemetry_snapshots`, `notifications`, `managed_processes`).
  - **V4:** AI provider catalog and model publication (`ai_providers`, `ai_models`).
  - **V5:** Asynchronous task execution infrastructure & event logs (`tasks` columns, `task_events`).
  - **V6:** Production workspace and sandbox lifecycle isolation (`workspaces` columns).
  - **V7:** Secure GitHub credentials storage encrypted at rest (`github_credentials`).
  - **V8:** Interactive Web Code Review Center revisions, comments, and AI explanation tracking (`review_revisions`, `review_comments`, `code_reviews` extensions).
  - **V9:** Task recovery checkpoints and granular persistence (`task_recovery_checkpoints`).
  - **V10:** Centralized notification preferences, deduplication, and sequential stream replay (`notification_preferences`, `notifications` deduplication and sequence columns).
  - **V11:** Final review approval, blocking comments, and Git delivery traceability (`code_reviews.approved_revision`, `review_comments.blocking`, `git_commits` table).
  - **V12:** Intelligent task intake and supplementary instructions (`task_instructions` table and indexes).
- **Backup and Maintenance:** Hot SQLite backups via `SqliteBackupService`, automated integrity checks (`PRAGMA integrity_check`), and compaction (`VACUUM`).

---

## 9. Integration with EVO (Decoupling Guarantee)

TRYST Core has **zero compile-time and zero runtime dependencies on EVO**.
- TRYST exposes its services over standard OSGi interfaces and REST APIs.
- When deployed within an environment containing EVO, an optional adapter bundle (`eu.kalafatic.tryst.evo.adapter`) can bridge EVO requests to TRYST's OSGi services or HTTP endpoints.
- If EVO is absent, TRYST runs completely standalone without warnings or degraded functionality.

---

## 10. Prioritized Defect and Architectural Debt List

| Priority | Issue / Debt Description | Architectural Impact | Target Resolution |
|---|---|---|---|
| **P1** | Tool execution uses string prefix parsing (`TOOL:name PARAM:...`) rather than structured JSON. | Can cause prompt drift or formatting parsing failures with smaller LLM models. | Adopt structured JSON tool calling schema in `AiContextManager` and `CodeAgent`. |
| **P2** | Static HTML/JS UI is embedded as multiline Java String literals in `TrystHttpServer`. | Limits modular development of web client assets and automated web linting. | Extract HTML/CSS/JS into bundle resources served via classloader stream in `StaticAssetHandler`. |
| **P3** | In-memory `workspaceService.getCurrentWorkspace()` persists as legacy state. | Potential race condition if multiple users operate different projects simultaneously. | RESOLVED (Phase 3): WorkspaceService allocates dedicated isolated task sandboxes (`allocateTaskWorkspace`), project-scoped roots, and enforces non-destructive cleanup rules. |
| **P4** | Git operations spawn external processes via `ProcessBuilder`. | Dependent on system `git` executable installed on the host. | Add startup check in `ServerReadinessService` verifying host `git` presence and version. |
| **P5** | AI provider transport coupled directly with domain logic. | Difficult to mock or switch AI engines cleanly. | RESOLVED (Phase 5): Segregated `AIProvider` transport from agent logic, introducing `TaskAnalyzer`, `ImplementationPlanner`, `CodeAgent`, `CodeChangeExecutor`, `ResultValidator`, and `OllamaProvider`. |
