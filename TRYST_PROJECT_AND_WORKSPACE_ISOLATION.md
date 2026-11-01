# TRYST Project and Workspace Isolation Architecture

**Document Version:** 1.0.0
**Date:** 2026-11-01
**Implementation Baseline:** PROMPT 03/04

---

## 1. Overview and Core Invariants

In TRYST, project and workspace isolation ensures that multiple users and projects can coexist securely and concurrently without cross-tenant information disclosure, working tree corruption, or directory traversal vulnerabilities.

Isolation is enforced at the domain service boundary (`WorkspaceService`, `PermissionManager`) rather than being an afterthought in web controllers or UI views.

---

## 2. Multi-User Project Scoping

The authoritative domain model `eu.kalafatic.tryst.domain.model.Project` defines user ownership:
- Each `Project` contains `ownerId`, `name`, `description`, and `location` (`Path`).
- Default owner fallback is `Project.DEFAULT_OWNER_ID` (`default-user`).
- Database schema migration V2 added `owner_id` to the `projects` table with an indexed lookup `idx_projects_owner`.

### Authorization Boundary Checks
`PermissionManager.validateUserProjectAccess(userId, project)` verifies that:
1. `userId` is non-null and non-blank.
2. The user matches `project.getOwnerId()`, or has administrative privileges (`admin`).
3. If unauthorized, access is denied immediately via `TrystDomainException`, and an audit record is logged.

HTTP endpoints in `TrystHttpServer` read the user context via header `X-User-Id` (defaulting to `default-user`), filtering project listings and checking authorization prior to inspecting reviews or executing file edits.

---

## 3. Workspace Root Containment and Path Safety

Workspace filesystem operations are guarded by `PermissionManager.validatePathWithinWorkspace(targetPath, workspace)`:
1. **Lexical Normalization:** Evaluates `targetPath.toAbsolutePath().normalize()` and asserts prefix matching against `workspace.getRootPath().toAbsolutePath().normalize()`. This eliminates directory traversal attacks (such as `../../etc/passwd`).
2. **Canonical Real-Path Resolution:** Resolves existing ancestor paths via `toRealPath()` to ensure symbolic links cannot escape the authorized workspace root. If a symlink resolves to a target outside the root, access is rejected.
3. **Scoped Workspace Resolution:** `WorkspaceService.getWorkspaceForProject(projectId)` resolves an isolated `Workspace` instance whose `rootPath` matches `project.getLocation()`, ensuring that operations executed for a task are scoped to its project directory.

---

## 4. Concurrent Access Protection

To prevent concurrent file corruption when multiple threads or background tasks manipulate files within a project directory, `PermissionManager` maintains a `ConcurrentHashMap<String, ReentrantLock>` of canonical workspace locks:
```java
ReentrantLock lock = permissionManager.getWorkspaceLock(workspace.getRootPath());
lock.lock();
try {
    // Isolated file or Git operation
} finally {
    lock.unlock();
}
```
All paths mapping to the same canonical filesystem location acquire the exact same `ReentrantLock` instance.

---

## 5. Automated Verification Reference

- `eu.kalafatic.tryst.tests.ProjectAndWorkspaceIsolationTest`
  - `testUserProjectAuthorizationEnforcement`: Validates user vs. owner authorization and admin override.
  - `testWorkspaceRootContainmentAndTraversalProtection`: Validates directory traversal rejection and symlink escapes.
  - `testConcurrentWorkspaceLocks`: Verifies multi-threaded lock serialization across concurrent workers.
  - `testScopedWorkspaceResolutionPerProject`: Verifies that distinct projects resolve distinct isolated workspace roots.
