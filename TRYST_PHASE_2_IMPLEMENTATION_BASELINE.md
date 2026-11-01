# TRYST Phase 2 Implementation Baseline

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Author:** TRYST Architecture Team
**Target:** PROMPT 03/04 – Project Isolation, Autonomous Lifecycle, and Interactive Git Review Center

---

## 1. Executive Summary

This document establishes the verified codebase baseline for TRYST prior to the execution of Phase 2 (PROMPT 03/04). It inspects the actual classes, methods, configurations, and test suites in `git commit e5e201a` ("Implement RCP-integrated TCP and HTTP communication services").

All claims in this document are backed by references to actual classes, database tables, and runtime behavior observed in the repository.

---

## 2. Audit Findings Verification Matrix

| Finding / Area | Expected Phase 1 State | Actual State in Codebase | Verification & References |
|---|---|---|---|
| **Equinox / Headless Runtime** | Headless Equinox startup via `IApplication` | Implemented | `TrystServerApplication` implements `org.eclipse.equinox.app.IApplication` under application ID `eu.kalafatic.tryst.application.server`. |
| **Communication Services** | Pure Java 21 HTTP & TCP services | Implemented | `TrystHttpServer` (`TrystHttpService`) and `TrystTcpServer` (`TrystTcpService`) in `eu.kalafatic.tryst.infrastructure.server`. |
| **Persistence & Migrations** | SQLite schema migrations V1 | Implemented | `SchemaMigrationService` manages `schema_version` with V1 initial relational schema (`projects`, `workspaces`, `sessions`, `tasks`, `conversation_messages`, `execution_results`, `agents`). |
| **Authoritative Project / Workspace Ownership** | Per-user project & workspace isolation | **Deficient (FIND-005)** | `WorkspaceService` maintains a single `currentWorkspace`. `Project` and `Workspace` lack user/owner identifiers (`ownerId`). Any request can inspect or mutate global workspace state. |
| **Durable Task Lifecycle** | Complete resilient lifecycle with review gating | **Deficient (FIND-003, FIND-006)** | `TaskStatus` has only `CREATED`, `PLANNED`, `READY`, `RUNNING`, `WAITING_FOR_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`. It lacks `QUEUED`, `CLAIMED`, `WAITING_FOR_REVIEW`, `APPROVED`, `REJECTED`, `COMMITTING`, `RECOVERY_REQUIRED`. Interrupted tasks remain indefinitely in `RUNNING`. |
| **Code Review & Git Commit Gating** | Web-based review center, editable diffs, commit authorization | **Deficient (FIND-006)** | `CodeReviewService` only performs static string inspections on diff text. There is no `CodeReview` domain entity, no persistence table, no editable proposed file changes, no content fingerprinting, and no commit authorization gate before `GitService.commit()`. |
| **Web UI Delivery** | Embedded SPA delivering Review Center and Task Management | **Minimal placeholder** | `TrystHttpServer.StaticAssetHandler` returns hardcoded HTML with basic REST text. No review viewer, diff editor, or interactive project switcher exists in static assets. |

---

## 3. Authoritative Architectural Inventory

### 3.1 Domain Models & Service Contracts (`eu.kalafatic.tryst.domain`)
- **`Project`**: Contains `id`, `name`, `description`, `location`, `status`, `active`, `createdAt`, `updatedAt`. Missing `ownerId`.
- **`Workspace`**: Contains `id`, `rootPath`, `status`, `createdAt`, `updatedAt`, `projects`, `tasks`. Root containment validation exists in `PermissionManager`, but workspace resolution is not multi-project or multi-tenant.
- **`AgentTask`**: Contains `id`, `projectId`, `sessionId`, `title`, `description`, `status` (`TaskStatus`), `priority`, `agentId`, `executionResultId`, `createdAt`, `completedAt`.
- **`WorkspaceService`**: Authoritative for workspace and project selection, but currently models only a single active workspace in memory.
- **`SessionTaskOrchestrator`**: Orchestrates `AgentTask` execution via AI inference and tool invocation. In Phase 1, completions transition straight to `COMPLETED` or `FAILED`.
- **`AutonomousDevelopmentService`**: Bridges task orchestration and `CodeReviewService`.
- **`PermissionManager`**: Validates paths using `Path.startsWith()`. Needs canonicalization (`toRealPath()`), symlink containment, and user project authorization.

### 3.2 Infrastructure & Persistence (`eu.kalafatic.tryst.infrastructure`)
- **`SqliteDatabaseManager`**: Authoritative connection manager with reentrant monitor lock `dbLock`.
- **`SchemaMigrationService`**: Applied migration V1. Needs migration V2 for `owner_id` in `projects` and `code_reviews` / `review_file_edits` tables.
- **`GitService`**: Runs external git CLI commands. Lacks explicit authorization linkage (linking commit hash to review ID, authorizer ID, and approved content fingerprint).
- **`BuildAndTestService`**: Executes `mvn compile` and `mvn test` in background process.

### 3.3 Application Lifecycle & OSGi Registry (`eu.kalafatic.tryst.application`)
- **`ApplicationLifecycleCoordinator`**: Coordinates deterministic transition stages `APPLICATION_START` through `APPLICATION_TERMINATED`. Registers all repositories, domain services, AI client, and communication servers into OSGi service registry.
- Lacks crash recovery / reconciliation on startup for orphaned tasks.

---

## 4. Phase 2 Target Architecture

1. **Project & Workspace Isolation:**
   - Multi-tenant / multi-user scoping: `Project(ownerId, ...)`.
   - `PermissionManager.validateUserProjectAccess(userId, projectId)`.
   - Canonical path resolution against authorized project root (`toRealPath()`), rejecting directory traversal (`../`) and symlink escapes.
   - Synchronized workspace concurrency lock per workspace path.

2. **Autonomous Execution Lifecycle:**
   - Extended lifecycle: `QUEUED -> CLAIMED -> RUNNING -> WAITING_FOR_REVIEW -> APPROVED / REJECTED -> COMMITTING -> COMPLETED / FAILED / CANCELLED / RECOVERY_REQUIRED`.
   - Interrupted tasks recovered on application startup.

3. **Interactive Web Code Review Center:**
   - Durable entities `CodeReview` and `ReviewFileEdit`.
   - REST API endpoints for review listing, unified diff fetching, file editing, re-running build/tests, review approval/rejection, and commit authorization.
   - SHA-256 fingerprinting guaranteeing that only explicitly approved changes matching working-tree content are committed.
   - Rich embedded web frontend delivering project switching, task monitoring, diff navigation, inline file editing, and approval workflow.
