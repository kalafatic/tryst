# TRYST – AI Development Environment Architecture

## Overview
TRYST is an AI-powered autonomous software development environment desktop application inspired by Google Jules, built as an independent Eclipse Rich Client Platform (RCP) application. It enforces strict Model-View-Controller (MVC) architecture, pure Java 21 standard capabilities, OSGi service modularity, and integration with local Ollama inference models.

## Completed Platform Foundation Architecture

### 1. Centralized Configuration Management (`eu.kalafatic.tryst.core.config`)
- **`ApplicationConfiguration`**: Strongly-validated record holding core paths (`userHome`, `configDirectory`, `workspaceDirectory`), AI configuration (Ollama endpoint, default model, timeouts), and execution limits (iteration caps, context/output tokens, retry limits, sandbox mode).
- **`ConfigurationService`**: Manages loading, validation, disk persistence (`tryst.properties`), and live update notifications to registered listeners.

### 2. Typed Application Event Bus (`eu.kalafatic.tryst.core.event`)
- **`TrystTypedEvent`**: Root interface for typed immutable events with unique `eventId`, `timestamp`, and optional `correlationId`.
- **`EventBus`**: Thread-safe publisher-subscriber engine offering exception isolation and type-safe routing.
- Standard events include: `ConfigurationChangedEvent`, `SessionEvent`, `TaskStateChangedEvent`, `AgentExecutionEvent`, `ToolExecutionEvent`, and `BuildAndTestEvent`.

### 3. Real Ollama & AI Infrastructure (`eu.kalafatic.tryst.infrastructure.ai`, `eu.kalafatic.tryst.domain.ai`)
- **`OllamaClient`**: Real HTTP client implemented with Java 21 `java.net.http.HttpClient` (no third-party web frameworks). Supports connection checks (`/api/tags`), model listing and inspection, chat generation (`/api/chat`), and line-delimited streaming.
- **`AiContextManager`**: Constructs prompt messages under token budget constraints, embedding workspace context, active project structure, and rolling conversation history.

### 4. Agent Architecture, Sessions & Task Orchestration (`eu.kalafatic.tryst.domain.service`)
- **`SessionTaskOrchestrator`**: Manages persistent `Session` and `AgentTask` lifecycles.
- **Agent Execution Loop**: Drives autonomous task resolution: receives request -> gathers context -> invokes AI inference -> detects and executes tools -> persists results -> evaluates status.
- Task status transitions (`IDLE` -> `RUNNING` -> `COMPLETED` / `FAILED` / `CANCELLED`) are enforced and persisted into SQLite.

### 5. Tool Foundation & Workspace Security (`eu.kalafatic.tryst.domain.tool`, `eu.kalafatic.tryst.domain.security`)
- **`Tool` & `ToolRegistry`**: Unified interface for tool discovery, validation, and execution.
- Tools included:
  - `read_file`, `write_file`, `list_directory`, `create_directory`
  - `git_diff`
- **`PermissionManager`**: Enforces strict path traversal defenses ensuring file operations stay inside the authorized workspace root, with comprehensive audit logging.

### 6. Git, Build & Test Engine (`eu.kalafatic.tryst.infrastructure.vcs`, `eu.kalafatic.tryst.infrastructure.build`)
- **`GitService`**: Non-destructive Git operations including status, diff, staged diff, branch listing, branch creation, staging, and commit.
- **`BuildAndTestService`**: Isolated process execution of Maven (`compile`, `test`) capturing exit code, logs, duration, and error output without blocking the workbench.

### 7. Code Review & Autonomous Development (`eu.kalafatic.tryst.domain.review`, `eu.kalafatic.tryst.domain.service`)
- **`CodeReviewService`**: Analyzes diffs and build/test logs for hardcoded secrets, unsafe calls (`System.exit`), empty catch blocks, and test failures, providing structured findings (`ReviewReport`).
- **`AutonomousDevelopmentService`**: Coordinates the entire workflow loop from user request to reviewed, verified code changes.

### 8. Workbench User Interface (`eu.kalafatic.tryst.ui`)
- **`MainWorkspaceView`**: Workspace context, session switcher, new session/task actions, single-click autonomous task execution, real-time streaming output, and cancellation.
- **`WorkspaceController`**: Coordinates presentation actions with `WorkspaceService`, `SessionTaskOrchestrator`, `AutonomousDevelopmentService`, and `AsyncExecutionService`.
- **`ConsoleOutputView`**: Live system, build, and event logging.
- **`ProjectExplorerView`**: Workspace project tree navigation.

### 9. Application Lifecycle (`eu.kalafatic.tryst.application.lifecycle`)
- `ApplicationLifecycleCoordinator` deterministically coordinates stages: `APPLICATION_START` -> `PLATFORM_INITIALIZATION` -> `CONFIGURATION_INITIALIZATION` -> `CORE_SERVICE_INITIALIZATION` -> `APPLICATION_READY` -> `WORKBENCH_RUNNING` -> `APPLICATION_SHUTDOWN` -> `SERVICE_DISPOSAL` -> `APPLICATION_TERMINATED`.
- Services are registered into the native OSGi service registry and resolved by UI via `ServiceTracker`.
