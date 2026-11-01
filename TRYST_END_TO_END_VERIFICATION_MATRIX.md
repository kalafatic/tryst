# TRYST – End-to-End Execution & Scenario Verification Matrix

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Baseline Git Commit:** `539768259664f6695bf44f7efce06615bca6fbeb`

---

## 1. Overview & Verification Methodology

This matrix traces actual execution flows through the codebase for each required operational scenario (A through K).

Each scenario documents:
- **Actual Entry Point:** Method, class, and interface initiating the flow.
- **Responsible Components:** Collaborating services and modules.
- **Method-Call Path:** Concrete invocation chain reconstructed from source code.
- **Persistence Operations:** SQL statements, schema interactions, and durability points.
- **State Transitions:** Domain entity lifecycle changes.
- **Thread & Concurrency Model:** Worker threads, executors, or UI loops involved.
- **Error Handling & Failure Points:** Potential failure modes and recovery behaviors.
- **Test Evidence:** Concrete test class and method verifying this flow (or unverified gap).
- **Status:** Complete / Partial / Missing.

---

## 2. End-to-End Scenario Evaluations

### Scenario A – Server Startup
- **Intended Flow:** RCP runtime starts → OSGi modules activate → Configuration loads → Database initializes & migrates → Embedded HTTP server starts → Web application becomes available.
- **Actual Flow:**
  - `TrystApplication.start(IApplicationContext)`
  - `coordinator.transitionTo(PLATFORM_INITIALIZATION)`
  - `coordinator.transitionTo(CONFIGURATION_INITIALIZATION)` (loads `ApplicationConfiguration.createDefault()`)
  - `PlatformUI.createDisplay()` (Desktop GUI display)
  - `PlatformUI.createAndRunWorkbench(display, advisor)`
  - `advisor.initialize()` -> `coordinator.transitionTo(CORE_SERVICE_INITIALIZATION)` (initializes SQLite, runs `SchemaMigrationService.migrate()`, registers OSGi services)
  - `coordinator.transitionTo(APPLICATION_READY)`
  - `advisor.postStartup()` -> `coordinator.transitionTo(WORKBENCH_RUNNING)`
- **Persistence:** Connects to SQLite at `~/.tryst/tryst.db`; executes `CREATE TABLE IF NOT EXISTS schema_version ...` and V1 migrations.
- **Test Evidence:** `ApplicationLifecycleTest.testCompleteLifecycleSequence()`, `DatabaseInitializationTest.testDatabaseInitializationAndPragmas()`, `SchemaMigrationTest.testSequentialMigrationTracksVersionAndCreatesTables()`.
- **Status:** ⚠️ **Partial**. Core service startup, database initialization, and OSGi registrations are fully functional and tested, but entry point is coupled to SWT Workbench and starts no HTTP server.

---

### Scenario B – User Login
- **Intended Flow:** Browser sends credentials → Auth endpoint → PBKDF2 validation → `AuthenticationSession` created & saved → Bearer token issued.
- **Actual Flow:** None. No auth endpoint, user table, or token generator exists.
- **Persistence:** None.
- **Test Evidence:** None (unverified gap).
- **Status:** ❌ **Missing**. Target for Phase 1 (PROMPT 02/04).

---

### Scenario C – Task Submission
- **Intended Flow:** User submits task request via web → API validation → Task persistence in SQLite → Queue scheduling → Execution dispatch.
- **Actual Flow:**
  - Desktop: User enters title in `MainWorkspaceView` -> `WorkspaceController.handleCreateTask(title, desc)`
  - `WorkspaceService.createTask(title, desc)`
  - Instantiates `AgentTask(UUID, projectId, sessionId, title, desc, IDLE, now, null)`
  - `taskRepository.save(task)` (SQL `INSERT OR REPLACE INTO tasks ...`)
  - `notifyTaskAdded(task)` -> UI table refresh
- **Persistence:** Persisted to SQLite `tasks` table with status `IDLE`.
- **Test Evidence:** `WorkspaceServiceTest.testCreateTaskNotifiesListener()`, `SqliteRepositoryTest.testTaskAndExecutionResultRepository()`.
- **Status:** ⚠️ **Partial**. Task persistence and validation work in domain/repository layers, but submission is only accessible via SWT modal dialog. Web REST submission missing.

---

### Scenario D – Autonomous Development Loop
- **Intended Flow:** Task picked up → Agent gathers context → Ollama inference → Structured tool execution (files/git) → Iteration loop → Progress streaming.
- **Actual Flow:**
  - `WorkspaceController.runTaskWithAgent(task)` -> `AutonomousDevelopmentService.executeAutonomousDevelopment(task, ...)`
  - `SessionTaskOrchestrator.executeTaskWithAgent(task, ...)`
  - `task.start()` -> `taskRepository.save(task)` (status `RUNNING`)
  - Loop up to `maxIterations`:
    - `AiContextManager.buildSystemInstructions(...)` & `assembleConversationContext(...)`
    - `OllamaClient.generateResponse(...)` via HTTP to local Ollama
    - Stream callback receives token chunks
    - Regex parses lines starting with `TOOL:` -> `executeToolCommand(...)`
    - Executes `FileTools.ReadFileTool`, `WriteFileTool`, or `GitTools.GitDiffTool`
    - Checks path containment via `PermissionManager.validatePathWithinWorkspace(...)`
  - On loop exit: `task.complete()` or `task.fail()`, saves `ExecutionResult` to SQLite.
- **Persistence:** `tasks` updated to `COMPLETED`/`FAILED`; `execution_results` inserted into SQLite.
- **Thread Model:** Dispatched via `CompletableFuture.supplyAsync()`.
- **Test Evidence:** `EndToEndAutonomousWorkflowIntegrationTest.testEndToEndAutonomousWorkflow()`, `OllamaClientTest.testGenerateResponse()`, `ToolAndWorkspaceSecurityTest.testFileToolsWithinAuthorizedWorkspace()`.
- **Status:** 🟢 **Functional**. Core loop executes and passes tests. Gap: tool parsing is line-prefix based rather than JSON-structured; streaming output is not persisted incrementally.

---

### Scenario E – Build and Test Execution
- **Intended Flow:** Agent files modified → Build & Test service invoked → Subprocess compiles & tests → Log and status captured.
- **Actual Flow:**
  - `BuildAndTestService.runBuild(projectDir)` -> executes `ProcessBuilder("mvn", "clean", "compile")`
  - `BuildAndTestService.runTests(projectDir)` -> executes `ProcessBuilder("mvn", "test")`
  - Reads output and error streams; evaluates exit code.
  - Returns `BuildResult(exitCode, output, error, duration)`.
- **Persistence:** Duration and exit code saved in `ExecutionResult`.
- **Test Evidence:** `GitAndCodeReviewTest.testGitServiceOperations()`, `EndToEndAutonomousWorkflowIntegrationTest`.
- **Status:** 🟢 **Functional**. Subprocess execution and exit code handling verified.

---

### Scenario F – Asynchronous Independence
- **Intended Flow:** User submits task → User closes browser / shuts down workstation → Server continues autonomous execution → Results persisted.
- **Actual Flow:**
  - Currently, closing the desktop UI window calls `PlatformUI.getWorkbench().close()`, terminating the JVM.
  - If JVM terminates while `SessionTaskOrchestrator` is running, the task remains orphaned in `RUNNING` status indefinitely.
- **Persistence:** In-flight tasks not reconciled upon next launch.
- **Test Evidence:** `ApplicationRestartRecoveryTest.testStateRestorationAfterSimulatedRestart()` proves saved entities survive restart, but orphaned running task recovery is missing.
- **Status:** ⚠️ **Broken in Desktop / Partial in Domain**. Domain is asynchronous, but lifecycle coupling to desktop window prevents true independent server execution. Target for Phase 1 (PROMPT 02/04).

---

### Scenario G – Return and Review
- **Intended Flow:** User logs in later → Finds completed task → Inspects generated diff in web viewer → Edits files inline → Validates changes.
- **Actual Flow:**
  - `CodeReviewService.reviewChanges(diff, testOutput, buildSuccess)` scans diff string for secrets and `System.exit`.
  - Generates `ReviewReport(approved, defectCount, warningCount, findings, summary)`.
  - No interactive web review viewer exists; no inline file editor exists.
- **Persistence:** `ReviewReport` is not saved to a dedicated database table.
- **Test Evidence:** `GitAndCodeReviewTest.testCodeReviewServiceDetectsDefectsAndSecurityIssues()`.
- **Status:** ⚠️ **Partial**. Heuristic backend inspection exists and passes tests, but user-facing web review and inline editing are missing. Target for Phase 2 (PROMPT 03/04).

---

### Scenario H – Explicit Approval and Commit
- **Intended Flow:** User approves review in web client → Server verifies review fingerprint matches working tree → Executes `git commit` on feature branch → Task marked `COMPLETED`.
- **Actual Flow:**
  - `GitService.commit(repoDir, message)` executes native `git commit -m <msg>`.
  - However, `AutonomousDevelopmentService` does not gate this behind human approval (either auto-completes or leaves unstaged).
- **Persistence:** Git commit created in repository; task status updated.
- **Test Evidence:** `GitAndCodeReviewTest.testGitServiceOperations()`.
- **Status:** ⚠️ **Partial**. Raw Git commit works via `GitService`, but human approval gate and web commit trigger are missing. Target for Phase 2 (PROMPT 03/04).

---

### Scenario I – Failure and Crash Recovery
- **Intended Flow:** Server crashes or restarts during agent task execution → On boot, startup reconciler discovers orphaned tasks → Reconciles status to `FAILED` or re-queues.
- **Actual Flow:**
  - Database reopens cleanly thanks to SQLite WAL journal mode and `PRAGMA busy_timeout = 5000`.
  - `SchemaMigrationService` checks version idempotently.
  - However, no recovery reconciler inspects `tasks` table for uncompleted `RUNNING` tasks.
- **Persistence:** SQLite WAL recovers database consistency; task data remains as it was at crash time.
- **Test Evidence:** `DatabaseInitializationTest.testRepeatedStartupIsIdempotentAndSafe()`, `ApplicationRestartRecoveryTest.testStateRestorationAfterSimulatedRestart()`.
- **Status:** ⚠️ **Partial**. Storage engine recovers cleanly, but task state machine recovery reconciler is missing. Target for Phase 1 (PROMPT 02/04).

---

### Scenario J – Server Administration
- **Intended Flow:** Administrator logs in → Inspects active sessions, tasks, processes, JVM metrics, and security audit log.
- **Actual Flow:** None.
- **Persistence:** None.
- **Test Evidence:** None.
- **Status:** ❌ **Missing**. Target for Phase 3 (PROMPT 04/04).

---

### Scenario K – Multi-User Isolation
- **Intended Flow:** User 1 and User 2 access server concurrently → Different workspaces, isolated project paths, distinct credentials, separate task queues.
- **Actual Flow:** Single global workspace; `PermissionManager` enforces boundary for only that one workspace.
- **Persistence:** No user IDs associated with projects or tasks in schema V1.
- **Test Evidence:** `ConcurrencyTest.testConcurrentWritesAreSafelySerialized()` proves database transaction serialization works, but multi-user isolation is not present.
- **Status:** ❌ **Missing**. Target for Phase 1 (Schema & Auth) and Phase 2 (Workspace Isolation).

---

## 3. Scenario Verification Summary

| Scenario | Operational Flow | Existing Verification | Identified Gap | Planned Resolution |
|---|---|---|---|---|
| **A** | Server Startup | Unit/Integration Tests Pass (LifeCycle, DB) | Coupled to GUI Display; starts no HTTP | Phase 1 (Headless Server + HTTP) |
| **B** | User Login | None | No user or auth subsystem | Phase 1 (Auth Subsystem) |
| **C** | Task Submission | Unit Tests Pass (Workspace, Repo) | Only accessible via SWT dialog | Phase 1 (REST API Submission) |
| **D** | Autonomous Dev Loop | Integration Test Passes (Ollama + Tools) | String parsing; memory streaming | Phase 2 (JSON Tools & Stream Persistence) |
| **E** | Build and Test | Integration Tests Pass (Subprocess) | Needs `./mvnw` detection | Phase 2 (Wrapper Detection) |
| **F** | Async Independence | Partial (Entity restart tested) | Window closure halts process | Phase 1 (Headless Daemon & Recovery) |
| **G** | Return and Review | Unit Tests Pass (Diff Rule Scanner) | Web review & inline editing missing | Phase 2 (Review Center UI & Edits) |
| **H** | Approval & Commit | Unit Tests Pass (Git CLI Commit) | No human approval checkpoint | Phase 2 (Approval Commit Gating) |
| **I** | Failure & Recovery | Integration Tests Pass (DB WAL recovery) | Task state machine reconciliation missing | Phase 1 (Startup Task Reconciler) |
| **J** | Administration | None | No metrics or admin APIs | Phase 3 (Admin Subsystem) |
| **K** | Multi-User Isolation | Partial (DB write concurrency tested) | Single workspace, no RBAC | Phase 1 (Auth/Roles) & Phase 2 (Tenancy) |
