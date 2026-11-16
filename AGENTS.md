# TRYST – Agent Instructions

## 1. Project Identity

TRYST is an independent Java 21 Eclipse RCP / Equinox / OSGi application and server platform.

Its purpose is to provide a Jules-like AI-assisted software development workflow:

1. User selects or enters a Git repository and submits a task.
2. TRYST prepares an isolated repository/workspace/sandbox.
3. A selected coding agent performs the task.
4. TRYST records execution state, output and changes.
5. The user monitors progress, reviews diffs, comments and requests rework.
6. The user can finalize the result through the existing Git/approval workflow.

TRYST is independent of EVO. Do not introduce an EVO runtime dependency or merge their application lifecycles. Shared concepts may be integrated only through explicit, existing contracts.

## 2. Fundamental Development Principles

- TRYST is an existing, evolving codebase. Improve its existing architecture; do not rewrite or replace it.
- Preserve public behavior, APIs, task/session flows, persistence, server lifecycle and compatibility unless explicitly instructed otherwise.
- Prefer minimal, targeted fixes over broad refactoring.
- Understand the affected functionality in the context of the entire application, not just the initially identified class.
- Reuse existing services and abstractions. Do not introduce parallel architectures, duplicate managers, registries, event buses or competing sources of truth.
- Do not delete files or perform broad cleanup unless explicitly requested.
- Distinguish confirmed facts from hypotheses. Never invent files, methods, endpoints, database fields or test results.
- When asked to implement, implement and verify the change; do not stop after analysis or present a proposal as completed work.
- If blocked, identify the exact technical obstacle and use another valid inspection or implementation route before declaring a blocker.

## 3. Mandatory Repository-Wide Development Workflow

**This workflow applies automatically to EVERY development task.**

The user does not need to request architectural analysis, dependency analysis, impact analysis or regression prevention separately.

A seemingly small change can affect multiple bundles, services, events, database operations or UI components. Never assume that the size of a requested change determines the scope of its impact.

### 3.1 Before Every Task

1. Identify the actual TRYST repository root and understand the task.
2. Read the relevant project instructions and architecture documentation.
3. Identify the subsystem responsible for the requested behavior.
4. Inspect the actual implementation, not just class names or documentation.
5. Search the repository for relevant callers, implementations, interfaces and consumers.
6. Identify direct and indirect dependencies.
7. Investigate relevant OSGi services, EventBus relationships, extension points, asynchronous execution and lifecycle dependencies.
8. Identify affected persistence, API, configuration and frontend contracts where applicable.
9. Determine which other application components could be affected.
10. Identify appropriate verification and regression checks.

**Do not begin modifying code before understanding the affected architectural context.**

### 3.2 Repository-Wide Awareness

Always consider the complete application architecture.

Do not assume that a functionality belongs exclusively to one Java class or bundle.

Follow relevant dependencies across:

- Eclipse plugins and OSGi bundles.
- Java interfaces and implementations.
- Service registrations and consumers.
- Event publishers and subscribers.
- Extension points and extensions.
- HTTP/API request and response contracts.
- Frontend JavaScript and backend handlers.
- Database repositories and migrations.
- Asynchronous workers and execution stages.
- Shared infrastructure and lifecycle services.

Use repository-wide searches to find relevant references.

For changes involving shared services, public interfaces, events, persistence, lifecycle or application startup, perform broader impact analysis across all relevant consumers.

For strictly local changes, use a focused investigation, but still verify that the change does not violate wider architectural contracts.

Do not mechanically inspect unrelated files just to claim that the entire repository was examined.

### 3.3 During Implementation

- Preserve the existing architecture and public behavior.
- Implement the smallest complete and correct change.
- Reuse existing services and utilities.
- Do not introduce parallel implementations.
- Do not perform unrelated refactoring.
- Do not modify unrelated components.
- Preserve existing naming, coding conventions and bundle boundaries.
- Do not silently change API contracts, state transitions or persistence semantics.
- If a larger architectural change appears necessary, explain why before proceeding.

### 3.4 After Implementation

1. Inspect every file modified by the task.
2. Review the complete set of changes.
3. Check affected callers, consumers and interfaces.
4. Check relevant service, event and lifecycle behavior.
5. Run relevant tests.
6. Run the appropriate Maven/Tycho build where feasible.
7. Verify database compatibility when persistence is affected.
8. Verify frontend behavior when UI or API contracts are affected.
9. Check for unintended changes outside the requested scope.
10. Report actual results, failures and skipped checks.

Never claim successful compilation, tests or runtime behavior without actual execution evidence.

### 3.5 Analysis-Only Tasks

When the user requests investigation, diagnosis, explanation or architectural analysis without implementation:

- Inspect the relevant implementation and dependencies.
- Trace the actual execution path.
- Identify direct and indirect impact.
- Distinguish confirmed findings from assumptions.
- Provide source locations and evidence.
- Do not modify source files unless explicitly requested.

## 4. Architecture Documentation and Persistent Knowledge

The project architecture must be understood and maintained as persistent knowledge rather than reconstructed from scratch for every task.

Use the following documentation when it exists:

- `docs/architecture/ARCHITECTURE_INDEX.md` – architecture documentation entry point.
- `docs/architecture/PROJECT_ARCHITECTURE.md` – project structure, bundles, subsystems and architectural boundaries.
- `docs/architecture/DEPENDENCY_MAP.md` – bundle, service, class and event dependencies.
- `docs/architecture/EXECUTION_FLOWS.md` – important application execution flows.
- `docs/architecture/REGRESSION_IMPACT_MAP.md` – sensitive components and regression risks.

### Documentation Rules

- Read the relevant documentation before investigating an affected subsystem.
- Verify important architectural information against actual source code.
- Treat documentation as a navigation aid, not an unquestionable source of truth.
- If documentation and implementation disagree, investigate and report the discrepancy.
- Do not assume that missing documentation means a subsystem does not exist.
- If the documentation is incomplete or missing, investigate the actual implementation and identify the gap.
- Do not invent architecture based on assumptions.

### Keeping Documentation Current

When a change modifies architectural relationships, update the relevant documentation.

This includes changes to:

- Bundle dependencies.
- Service responsibilities or registrations.
- Event publishers and subscribers.
- Application lifecycle.
- Execution flows.
- Persistence relationships.
- Shared interfaces.
- Major subsystem boundaries.

Do not update architecture documentation for purely local changes that have no architectural impact.

Documentation must describe actual implementation, not intended or proposed architecture.

## 5. Technology Constraints

The project uses:

- Java 21.
- Eclipse RCP, Equinox / OSGi, SWT/JFace and Maven/Tycho.
- SQLite for the existing persistence layer.
- Java HttpClient and the existing embedded HTTP/server infrastructure.
- Existing HTML/CSS and browser-compatible JavaScript for the web frontend.

Do not introduce:

- Spring or Spring Boot.
- Node.js backend/runtime.
- Python backend/runtime.
- External message brokers.
- Unrelated frameworks.

Follow the existing bundle structure, `MANIFEST.MF`, `plugin.xml`, target platform and Tycho conventions.

TRYST remains a standalone application. Do not add a dependency on EVO.

## 6. Architecture and Service Lifecycle

- Understand and preserve existing service boundaries and OSGi lifecycle.
- Reuse existing repository, sandbox, task, workflow, notification, agent, server and persistence services.
- Keep business logic in appropriate existing services, not duplicated in HTTP handlers or frontend code.
- Respect service registration, dependency injection, activation/deactivation and controlled startup/shutdown.
- Do not introduce a custom dependency-injection framework, service locator, second service registry or parallel lifecycle controller.
- Before changing constructors or service dependencies, inspect all creation sites, OSGi registrations, factories and tests.
- Preserve the separation between the TRYST RCP application and its embedded server components while keeping them in the existing Equinox/OSGi runtime.

When investigating lifecycle issues, trace initialization, service availability, activation order, shutdown and resource cleanup.

## 7. Domain Model and Identity

Keep these concepts distinct and follow the existing domain model:

- Task.
- Session.
- Repository.
- Repository mirror/pool.
- Workspace.
- Sandbox.
- Agent.
- Execution and execution stage.
- Change set and diff.
- Review and comment.
- Commit/finalization.

A task's identity and repository/session relationships must remain stable throughout its lifecycle.

Do not create duplicate task registries or infer one domain identity from another when the existing model stores them separately.

## 8. Task Lifecycle

The task lifecycle is central to TRYST.

Trace and preserve the actual implementation of:

1. Request submission and validation.
2. Task creation and identity assignment.
3. Database persistence and transaction completion.
4. Repository preparation and clone/fetch.
5. Workspace and sandbox preparation.
6. Agent selection and execution.
7. Progress, logs and status updates.
8. Change collection and diff generation.
9. User review, comments and rework.
10. Approval/finalization and the existing commit workflow.

Rules:

- A task must not be reported as accepted before its required persistence operation succeeds.
- A queued or scheduled task must not be represented as running or completed prematurely.
- Preserve existing task and session state machines and valid transitions.
- Do not add a competing lifecycle manager or duplicate state machine.
- Handle cancellation, failure, retries and resource cleanup consistently with existing conventions.

When changing any lifecycle stage, investigate all subsequent and dependent stages.

## 9. Navigator and UI State Synchronization

The Navigator must represent the actual persisted application state.

- A newly accepted task must become visible in the Navigator without requiring a manual page reload or application restart.
- Existing tasks must load correctly after startup and refresh.
- Task nodes must not be duplicated.
- Stable task/session identity must be used when inserting or updating nodes.
- Status changes, completion and failures must be reflected.
- Selecting a node must open the correct task/session details.
- Refreshing must preserve selection and expansion where the existing model supports it.

When investigating synchronization defects, trace the complete path:

1. Frontend submission.
2. Backend request handling.
3. Task creation and persistence.
4. Backend response and returned identifiers.
5. Event publication or existing notification mechanism.
6. Frontend event subscription/response handling.
7. Navigator model update and refresh.
8. SWT UI-thread dispatch.

Rules:

- Reuse the existing event system and Navigator model.
- Do not introduce a second event bus, polling loop, task registry or parallel cache.
- Publish events at the correct point relative to successful persistence.
- Make event handling idempotent where duplicate delivery is possible.
- Respect SWT `Display`/UI-thread rules.
- Do not assume that an HTTP success response automatically updates the Navigator.
- Verify all event publishers and subscribers before adding a new event type.

## 10. Backend and Communication

- Reuse existing HTTP/API endpoints, request/response models and server infrastructure.
- Preserve API compatibility unless a task explicitly requires a contract change.
- Keep frontend and backend contracts consistent; inspect every affected consumer when changing a response or endpoint.
- Respect asynchronous execution and avoid unnecessary blocking in request threads.
- Propagate errors clearly; do not silently swallow exceptions or return success for failed operations.
- Respect existing TCP/WebSocket mechanisms where they are already used; do not introduce another communication channel without need.
- Validate inputs at the appropriate boundary and preserve existing authorization/security behavior.
- Development-mode behavior must remain explicit and must not accidentally become the production default.

## 11. SQLite and Migrations

SQLite is the authoritative persistence layer where the existing domain uses it.

- Inspect the current schema, migration history, repositories/DAOs, constraints and transaction boundaries before changing persistence.
- Migrations are forward-only: never edit an already released/applied migration to change historical schema state.
- Add a new migration when a schema change is required.
- Preserve existing transaction semantics and data integrity.
- Check connection ownership and lifecycle carefully; do not close shared connections accidentally.
- Avoid holding transactions or database connections across long-running agent operations.
- Ensure persistence completes before publishing events that promise the entity exists.
- Test migrations against the existing migration chain, not only an isolated fresh schema.

Any persistence change must be checked for its impact on existing repositories, services, task/session relationships and application startup.

## 12. Git, Repositories and Sandbox Isolation

- Reuse the existing `RepositoryPoolManager`, repository mirror/pool services, `SandboxProvider` and workspace lifecycle.
- Do not execute coding agents against the user's original working repository unless explicitly authorized by the established workflow.
- Preserve repository identity, remote, branch identity, working tree isolation and commit history.
- Handle default branch discovery correctly; do not assume `main` or `master` without checking the existing implementation/repository metadata.
- Force-clean clone and pull-before-execution behavior must follow explicit user options and existing safety checks.
- Never silently discard user changes or overwrite a working tree.
- Keep repository preparation, sandbox execution and finalization as distinct lifecycle stages.
- Do not introduce a second Git/repository abstraction parallel to the existing one.

### Git Operation Restriction

Git is controlled exclusively by the user through Eclipse EGit.

**Never execute Git, GitHub CLI or GitHub operations.**

This prohibition includes:

- `git status`
- `git diff`
- `git log`
- `git show`
- `git add`
- `git commit`
- `git push`
- `git pull`
- `git fetch`
- `git clone`
- `git checkout`
- `git switch`
- `gh` commands
- Any other Git or GitHub operation.

Do not stage, commit, push, pull, fetch, clone, switch branches or modify repository history.

For change review, inspect the actual files modified during the current task and their contents. Do not use Git commands to obtain a diff.

Do not circumvent this restriction through shell scripts, libraries or other tools.

## 13. Coding-Agent Integration and Ollama

TRYST must be able to work with configurable coding agents without coupling its core workflow to one provider.

- Reuse an existing agent abstraction/adapter if present.
- Keep provider-specific startup, CLI/API invocation, process lifecycle, input/output streaming, cancellation, progress, error handling and result collection behind the adapter boundary.
- Do not hardcode a single agent or model into task orchestration.
- Preserve configurable local Ollama endpoint/model selection and existing model settings.
- Verify actual installed model identifiers and agent capabilities rather than assuming a display name proves availability.
- Do not report an agent task as complete until its process/result has been collected and the expected validation stages have run.
- Do not introduce another task/session manager to support an agent.

When changing agent integration, investigate the impact on task orchestration, execution state, cancellation, logging, progress reporting and result collection.

## 14. Frontend and User Experience

- Preserve the existing HTML/CSS/JavaScript architecture and API client conventions.
- Do not introduce React, Angular, Vue or another frontend framework unless explicitly requested.
- Keep the interface professional, practical and consistent with the existing Eclipse-like layout.
- Preserve Navigator, console, editor pages, navigation, status indicators, context menus and existing functionality.
- Avoid unnecessary visual redesign or removal of existing controls.
- Ensure event handlers and referenced functions actually exist; check browser console errors and network responses when debugging.
- Avoid inline-handler or script-loading changes that break function scope or initialization order.
- Display real task states and errors; do not fabricate progress percentages or success states.
- Maintain consistent status/progress components across editor pages where the existing architecture provides them.

For frontend changes, investigate both the browser-side implementation and the corresponding backend/API behavior.

## 15. Server and Network Conventions

Preserve TRYST's independent server lifecycle and current intended port conventions:

| Server | Port |
|---|---:|
| HTTP | 38080 |
| API | 38180 |
| TCP | 38280 |
| Management | 38380 |
| WebSocket | 38480 |
| Auxiliary | 38580 |
| Debug | 38680 |

- Use existing configuration, port allocation and persistence mechanisms rather than hardcoding ports in multiple places.
- If a requested port is occupied, use the established offset/allocation behavior; do not silently fall back to an unrelated default such as `8080`.
- Respect configured bind address/network mode and startup/shutdown lifecycle.
- Verify actual bound ports and server logs when diagnosing connectivity.
- Do not create duplicate server lifecycle controllers.

When changing server behavior, check startup, shutdown, port allocation, configuration persistence and all affected clients.

## 16. Implementation and Verification Requirements

For implementation tasks, follow this sequence:

1. Identify the actual TRYST repository root and relevant bundles.
2. Read relevant architecture documentation.
3. Inspect source files, callers, interfaces, service registrations, migrations, frontend consumers and tests.
4. Trace the real execution path and confirm the root cause.
5. Identify direct and indirect dependencies and regression risks.
6. Define the smallest complete fix consistent with the existing architecture.
7. Implement the requested change.
8. Review all modified files and ensure no unrelated changes were introduced.
9. Run focused tests and the relevant Maven/Tycho build where feasible.
10. Verify OSGi wiring, lifecycle, API contracts and database migration behavior when affected.
11. For frontend changes, verify JavaScript loading, browser console, network requests and actual UI behavior where possible.
12. Update architecture documentation if architectural relationships changed.
13. Report only work and verification that actually occurred.

For analysis-only tasks, do not modify source files.

Do not claim compilation, tests, runtime behavior or implementation success unless actually executed and verified.

If a check cannot be run, state why and mark it unverified.

Respect the Git operation restriction in Section 12 throughout the entire process.

## 17. Completion Report

For implementation tasks, report:

- Confirmed root cause or requirement.
- Modified files and relevant classes/methods.
- Architectural reasoning and impact.
- Dependencies and consumers investigated.
- Potential regression risks.
- Database migration changes, if any.
- Build and test commands actually executed.
- Actual build/test results.
- Runtime verification, if performed.
- Remaining issues and anything not verified.

For analysis-only tasks, report:

- Scope of investigation.
- Relevant source locations.
- Actual execution path.
- Confirmed findings.
- Dependencies and affected components.
- Potential regression risks.
- Hypotheses and unresolved questions.
- Recommended minimal changes, if requested.

Never report a proposed change as implemented.

A task is complete only when the requested work has actually been performed and the stated verification has actually occurred.

## 18. Governing Principle

TRYST must provide a reliable, observable and safe AI-assisted development workflow.

Preserve the existing platform, task lifecycle, persistence, Navigator synchronization, repository isolation and user review process.

**Think across the entire application. Investigate before changing. Implement minimally. Verify the impact.**

The user must never need to repeat these development and regression-prevention instructions or provide a separate analysis prompt.