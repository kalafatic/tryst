# TRYST Domain Model

## 1. Domain Overview & Principles

TRYST is an independent Java 21 / Eclipse RCP / OSGi autonomous AI-assisted software development application.
The domain model defines the core entities, value objects, and lifecycle invariants for managing autonomous development tasks.

Key architectural design principles adhered to:
- **Clean Architecture & MVC Separation**: The domain layer (`eu.kalafatic.tryst.domain`) contains pure domain logic and is completely independent of UI/SWT/JFace, database implementations (JDBC/SQLite), inference runtimes (Ollama), and build tools.
- **Clear Entity Ownership**: Root entities own their boundaries; relations avoid cyclic references.
- **Stable Identifiers**: Entities use application-assigned UUID strings (`java.util.UUID`), independent of persistence keys.
- **Controlled State Transitions**: Entity status mutations occur via explicit domain methods enforcing lifecycle rules and throwing `TrystDomainException` on invalid operations.
- **Defensive Invariant Validation**: Entities enforce non-null and non-blank rules at construction time.

---

## 2. Core Entities & Value Objects

### 2.1 Project (`eu.kalafatic.tryst.domain.model.Project`)
Represents a software development project managed within TRYST.
- **Responsibilities**: Identity, name, description, physical repository location (`Path`), active project flag, timestamps, and lifecycle status.
- **Fields**:
  - `id`: Unique project UUID string.
  - `name`: Non-blank human-readable name.
  - `description`: Project description.
  - `location`: Filesystem `Path` where the project is situated.
  - `status`: `ProjectStatus` (`ACTIVE`, `ARCHIVED`, `CLOSED`).
  - `active`: Boolean indicating if this is the active project in focus.
  - `createdAt`, `updatedAt`: `Instant` timestamps.
- **Behaviors**: `activate()`, `archive()`, `close()`, `setActive(boolean)`.

### 2.2 Workspace (`eu.kalafatic.tryst.domain.model.Workspace`)
Represents the working environment boundary for file operations, builds, and agent execution.
- **Responsibilities**: Root filesystem path, workspace lifecycle status, containment of loaded projects, and tracking of active agent tasks.
- **Fields**:
  - `id`: Unique workspace UUID string.
  - `rootPath`: Filesystem `Path` root directory.
  - `status`: `WorkspaceStatus` (`ACTIVE`, `INACTIVE`, `LOCKED`, `CLOSED`).
  - `createdAt`, `updatedAt`: `Instant` timestamps.
  - `projects`: Immutable view of loaded projects.
  - `tasks`: Immutable view of workspace agent tasks.
- **Behaviors**: `activate()`, `deactivate()`, `lock()`, `close()`, `addProject(Project)`, `removeProject(String)`, `setActiveProject(String)`, `addTask(AgentTask)`.

### 2.3 Session (`eu.kalafatic.tryst.domain.model.Session`)
Represents a persistent conversational and execution context bound to a project and workspace.
- **Responsibilities**: Session identity, project association (`projectId`), workspace association (`workspaceId`), session name, execution status, and audit timestamps.
- **Fields**:
  - `id`: Unique session UUID string.
  - `projectId`: Associated project identifier (non-blank).
  - `workspaceId`: Associated workspace identifier (optional).
  - `name`: Session title / label.
  - `status`: `SessionStatus` (`ACTIVE`, `PAUSED`, `COMPLETED`, `CLOSED`, `TERMINATED`).
  - `createdAt`, `updatedAt`: `Instant` timestamps.
- **Behaviors**: `pause()`, `resume()`, `complete()`, `close()`, `terminate()`, `touch()`.

### 2.4 AgentTask (`eu.kalafatic.tryst.domain.model.AgentTask`)
Represents a discrete unit of work requested by the user or generated during planning.
- **Responsibilities**: Task title and description, project and session association, execution priority, agent assignment, execution result reference, and lifecycle state transitions.
- **Fields**:
  - `id`: Unique task UUID string.
  - `projectId`: Associated project identifier.
  - `sessionId`: Associated session identifier.
  - `title`: Non-blank task title.
  - `description`: Detailed task specification.
  - `status`: `TaskStatus` (`CREATED`, `PLANNED`, `READY`, `RUNNING`, `WAITING_FOR_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`).
  - `priority`: `TaskPriority` (`LOW`, `NORMAL`, `HIGH`, `URGENT`).
  - `agentId`: Assigned AI agent identifier.
  - `executionResultId`: Reference to the execution result.
  - `createdAt`, `completedAt`: `Instant` timestamps.
- **Behaviors**: `plan()`, `markReady()`, `start()`, `requestApproval()`, `approve()`, `complete()`, `fail()`, `cancel()`, `assignAgent(agentId)`, `setPriority(priority)`.

### 2.5 Agent (`eu.kalafatic.tryst.domain.model.Agent`)
Represents an AI execution persona with defined role, specialization, and availability. Distinct from an LLM inference model.
- **Responsibilities**: Agent identity, functional specialization, operational availability, and capability catalog.
- **Fields**:
  - `id`: Unique agent UUID string.
  - `name`: Agent identifier name.
  - `description`: Functional description.
  - `agentType`: `AgentType` (`CODER`, `ARCHITECT`, `REVIEWER`, `ORCHESTRATOR`, `GENERAL`).
  - `availability`: `AgentAvailability` (`AVAILABLE`, `BUSY`, `OFFLINE`).
  - `capabilities`: Set of capability strings (e.g. `java21`, `git-merge`).
  - `createdAt`, `updatedAt`: `Instant` timestamps.
- **Behaviors**: `markAvailable()`, `markBusy()`, `markOffline()`, `addCapability(String)`, `hasCapability(String)`.

### 2.6 ConversationMessage (`eu.kalafatic.tryst.domain.model.ConversationMessage`)
Represents an immutable conversational turn within a session.
- **Responsibilities**: Message content, session correlation, participant role, and execution metadata.
- **Fields**:
  - `id`: Unique message UUID string.
  - `sessionId`: Parent session identifier.
  - `role`: `MessageRole` (`USER`, `ASSISTANT`, `SYSTEM`, `TOOL`).
  - `content`: Message payload string.
  - `metadata`: Immutable key-value map (e.g., model name, token usage).
  - `createdAt`: `Instant` timestamp.

### 2.7 ExecutionResult (`eu.kalafatic.tryst.domain.model.ExecutionResult`)
Represents the outcome of executing an agent task, tool, build, or test operation.
- **Responsibilities**: Task correlation, exit code, execution summary, stdout/stderr streams, diagnostic information, and duration.
- **Fields**:
  - `id`: Unique result UUID string.
  - `taskId`: Parent task identifier.
  - `exitCode`: Integer exit code (`0` for success).
  - `summary`: High-level execution summary.
  - `output`: Standard output string.
  - `errorOutput`: Standard error output string.
  - `diagnosticInfo`: Diagnostic analysis or stack trace.
  - `durationMs`: Execution duration in milliseconds.
  - `completedAt`: `Instant` completion timestamp.
- **Behaviors**: `isSuccess()`.

### 2.8 ConsoleMessage (`eu.kalafatic.tryst.domain.model.ConsoleMessage`)
Value object representing application-wide console logs and status feedback.
- **Fields**: `timestamp`, `level` (`INFO`, `WARN`, `ERROR`), `source`, `message`.
