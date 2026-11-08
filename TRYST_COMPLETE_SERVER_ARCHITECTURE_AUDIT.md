# TRYST – Complete Server Architecture & Initialization Audit

## 1. Executive Summary & Audit Context
This audit reconstructs the authoritative architecture of TRYST across the entire Eclipse RCP / Equinox OSGi runtime. TRYST is a self-hosted autonomous software engineering platform where the Eclipse RCP product is the server host. It embeds an HTTP/WebSocket server running within the Equinox runtime, shares persistence, domain services, security, and lifecycle management between native Eclipse RCP administration views and the web-based code review and task operations console.

This document details:
1. Application entry points and Equinox startup sequence.
2. Configuration loading and precedence hierarchy.
3. Persistence layer, SQLite manager, and schema migrations (V1–V3).
4. AI infrastructure, Ollama integrations, and inference routing.
5. Native Eclipse RCP administration, UI bundle activation, and OSGi service resolution.

---

## 2. Application Startup & Lifecycle Architecture

### 2.1 Entry Points
TRYST defines two Equinox application entry points implementing `org.eclipse.equinox.app.IApplication`:
1. **Interactive Desktop RCP (`TrystApplication`)**:
   - Class: `eu.kalafatic.tryst.application.TrystApplication`
   - Extension Point: `org.eclipse.core.runtime.applications` -> id `eu.kalafatic.tryst.application.application`.
   - Driven by `PlatformUI.createAndRunWorkbench(display, new ApplicationWorkbenchAdvisor(coordinator))`.
   - Manages workbench display loop, workbench window advisors, and RCP UI perspectives.
2. **Headless Equinox Server (`TrystServerApplication`)**:
   - Class: `eu.kalafatic.tryst.application.TrystServerApplication`
   - Extension Point: `org.eclipse.core.runtime.applications` -> id `eu.kalafatic.tryst.application.server`.
   - Runs headless Equinox execution without requiring an X11/Wayland display or SWT event loop.
   - Blocks on a `CountDownLatch` and attaches a graceful JVM shutdown hook to drive orderly service disposal.

### 2.2 Lifecycle Coordinator (`ApplicationLifecycleCoordinator`)
All runtime services are coordinated deterministically by `eu.kalafatic.tryst.application.lifecycle.ApplicationLifecycleCoordinator`.
The coordinator drives strict state transitions defined in `eu.kalafatic.tryst.core.lifecycle.ApplicationLifecycleStage`:
- `APPLICATION_START`: Initial instantiation of the coordinator.
- `PLATFORM_INITIALIZATION`: Instantiates the event bus (`eu.kalafatic.tryst.core.event.EventBus`).
- `CONFIGURATION_INITIALIZATION`: Resolves `~/.tryst` and initializes `ConfigurationService`.
- `CORE_SERVICE_INITIALIZATION`:
  1. Opens SQLite database (`~/.tryst/tryst.db`) via `SqliteDatabaseManager`.
  2. Applies schema migrations via `SchemaMigrationService`.
  3. Initializes repositories (`SqliteUserRepository`, `SqliteProjectRepository`, `SqliteTaskRepository`, `SqliteSessionRepository`, etc.).
  4. Runs task reconciliation: recovers interrupted tasks (`RUNNING`, `CLAIMED`, `COMMITTING` -> `RECOVERY_REQUIRED`).
  5. Instantiates domain services (`WorkspaceService`, `PermissionManager`, `AuthenticationService`, `GitService`, `CodeReviewService`, `AutonomousDevelopmentService`).
  6. Provisions initial administrator if no administrator exists.
  7. Instantiates and starts observability services (`ServerMonitoringService`, `TelemetryService`, `AiMonitoringService`, `ProcessExecutionManager`, `NotificationService`, `LogDiagnosticService`).
  8. Instantiates and starts communication services (`TrystTcpServer`, `TrystHttpServer`).
  9. Registers all services into the OSGi Service Registry (`bundleContext.registerService(...)`).
- `APPLICATION_READY`: System is operational.
- `WORKBENCH_RUNNING`: Workbench UI loop active.
- `APPLICATION_SHUTDOWN`: Shutdown sequence initiated.
- `SERVICE_DISPOSAL`: Closes listeners, stops HTTP/TCP listeners, stops telemetry and executors, and closes SQLite database.
- `APPLICATION_TERMINATED`: Clean terminal state.

---

## 3. Configuration Management

### 3.1 Authoritative Configuration Model
Defined in `eu.kalafatic.tryst.core.config.ApplicationConfiguration` (Java record) and managed by `ConfigurationService`.
Authoritative locations:
- Base configuration directory: `~/.tryst/`
- Properties file: `~/.tryst/tryst.properties`
- Workspace root: `~/.tryst/workspace/`

### 3.2 Configuration Fields and Defaults
- `applicationName`: `"TRYST – AI Development Environment"`
- `version`: `"0.1.0"`
- `ollamaEndpoint`: `"http://localhost:11434"`
- `defaultModel`: `"codellama"`
- `requestTimeoutSeconds`: `60`
- `maxIterations`: `5`
- `maxContextTokens`: `4096`
- `maxOutputTokens`: `2048`
- `maxRetryAttempts`: `3`
- `secureSandboxEnabled`: `true`
- `serverHost`: `"127.0.0.1"`
- `httpPort`: `38080` (authoritative default, migrated from legacy 8080)
- `tcpPort`: `38280` (authoritative default, migrated from legacy 9090/8280)

### 3.3 Dynamic Changes & Listeners
`ConfigurationService` provides `updateConfiguration(ApplicationConfiguration)` which saves changes back to `tryst.properties` and notifies registered `Consumer<ApplicationConfiguration>` listeners via copy-on-write callbacks.

---

## 4. Persistence Architecture & Schema Migrations

### 4.1 SQLite Connection and Concurrency
- Implemented in `eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager`.
- JDBC connection URL: `jdbc:sqlite:<user.home>/.tryst/tryst.db`.
- Pragmas configured:
  - `PRAGMA foreign_keys = ON;`
  - `PRAGMA busy_timeout = 5000;`
  - `PRAGMA journal_mode = WAL;` (Write-Ahead Logging for high concurrency)
  - `PRAGMA synchronous = NORMAL;`
- Concurrency and transactional isolation:
  - Synchronized across a shared lock (`dbLock`).
  - Unit of Work (`supplyInTransaction` / `runInTransaction`) controls auto-commit and rolls back on exception.

### 4.2 Migration Engine
- Implemented in `eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService`.
- Schema history table: `schema_version (version INT PRIMARY KEY, description TEXT, applied_at TEXT)`.
- Existing migrations:
  - `V1`: Core entities (`projects`, `workspaces`, `sessions`, `tasks`, `conversation_messages`, `execution_results`, `agents`).
  - `V2`: Code Review Center (`code_reviews`, `review_file_edits`).
  - `V3`: Security & Operations (`users`, `user_sessions`, `audit_events`, `telemetry_snapshots`, `notifications`, `managed_processes`).

---

## 5. AI Infrastructure & Inference Routing

### 5.1 Service Contract & Implementation
- Domain contract: `eu.kalafatic.tryst.domain.ai.AiInferenceService`.
- Implementation: `eu.kalafatic.tryst.infrastructure.ai.OllamaClient`.
- Pure Java 21 `java.net.http.HttpClient` communicating directly with local or remote Ollama HTTP API:
  - Tags discovery: `GET /api/tags`
  - Chat completions: `POST /api/chat`
  - Streaming completions: Line-by-line NDJSON consumption via `streamResponse`.
- Prompt context window tracking: `AiContextManager` calculates token consumption and manages context budgeting within `maxContextTokens`.

---

## 6. Native Eclipse RCP Administration Architecture

### 6.1 OSGi Service Resolution (`TrystUiServices`)
- The UI bundle `eu.kalafatic.tryst.ui` has `Bundle-ActivationPolicy: lazy`.
- Rather than relying on `TrystUiPlugin.getDefault()` (which can be null if views are activated before or outside the activator lifecycle), `TrystUiServices` uses `FrameworkUtil.getBundle(TrystUiServices.class).getBundleContext()` and dynamic `ServiceTracker<S, S>` instances to resolve services directly from the Equinox OSGi Service Registry.
- All administration views (`ServerOverviewView`, `UsersAndSessionsView`, `ProjectsAndGitView`, `TasksAndAgentsView`, `DatabaseAdminView`, `AiMonitoringView`, `TelemetryView`, `AuditAndLogsView`, `ManagedProcessesView`) query `TrystUiServices`.

### 6.2 Administration Perspectives
- Defined in `TrystAdministrationPerspective` (`eu.kalafatic.tryst.ui.perspective.administration`).
- Configures 4 folder quadrants displaying metrics, AI health, telemetry, database management, users, projects, tasks, and managed child processes.

---

## 7. Missing Capabilities Implemented for Complete Server Initialization

1. **Extended Dynamic Networking & Bound Port Allocation**:
   Support for port collision detection, runtime OS-assigned port allocation, and advertising the true bound port and external URL in `ApplicationConfiguration`, `ConfigurationService`, and `TrystHttpServer`.
2. **Database Integrity Verification**:
   Automatic `PRAGMA integrity_check;` on database initialization in `SqliteDatabaseManager`.
3. **Secure Initial Administrator Bootstrap & Token Setup**:
   `AuthenticationService` detecting missing administrator, generating a protected one-time setup token, and exposing `/api/setup` and `/api/setup/status` in `TrystHttpServer`.
4. **AI Provider Discovery and Catalog Publication**:
   `AiModelRegistryService` managing models, capabilities, availability, and user role-based access filtering.
5. **Embedded Browser Administration View**:
   `EmbeddedBrowserView` in `eu.kalafatic.tryst.ui.view` using SWT `Browser` dynamically pointed to the server's runtime endpoint.
6. **Detailed Session Administration**:
   `SessionDetailView` displaying end-to-end session operational context: project, tasks, agents, AI model, and execution status.
