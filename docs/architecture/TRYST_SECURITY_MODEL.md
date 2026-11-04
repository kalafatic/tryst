# TRYST Security and Authorization Model

**Document Identifier:** `TRYST-ARCH-SEC-001`
**Status:** APPROVED BASELINE
**Classification:** Security Architecture Specification

---

## 1. Security Architecture Principles

TRYST applies defense in depth across all execution layers:
1. **Server-Enforced RBAC & Explicit Decision Model:** Client-side headers (such as `X-User-Id`) cannot bypass authorization. Authentication credentials must be verified on the server. Explicit authorization evaluates `PermissionDecision` (`ALLOW`, `DENY`), where **`DENY` strictly takes precedence over `ALLOW`**.
2. **Cryptographic Integrity:** Credential hashing and Git diff commit authorization rely on standard cryptographic primitives.
3. **Canonical Workspace Containment:** All file and process tools resolve target paths to canonical real paths (`toRealPath()`) to prevent directory traversal and symlink escapes, rejecting null-byte injection (`\0`) and out-of-sandbox access.
4. **Auditability:** Security events, authentication failures, access violations, and administrative actions are persistently recorded in the `audit_events` table.
5. **No Direct Browser Process Execution:** Browser requests never directly spawn shell processes; tool execution is strictly orchestrated by background workers bound to isolated workspace sandboxes.

---

## 2. Authentication and Credential Storage

### 2.1 Password Hashing Specification
- **Algorithm:** PBKDF2 with HMAC-SHA256 (`PBKDF2WithHmacSHA256`).
- **Iterations:** 210,000 rounds.
- **Salt:** 32 cryptographically secure random bytes (64 hex characters) generated via `java.security.SecureRandom`.
- **Key Length:** 256 bits.
- **Verification:** Constant-time byte array comparison (`MessageDigest.isEqual`) to prevent timing side-channel attacks.

### 2.2 Bearer Session Management & CSRF Protection
- **Entropy:** 256-bit cryptographically secure random values formatted as 64-character hexadecimal strings.
- **Server-Side Token Storage:** Tokens are NEVER stored in plaintext. The database `user_sessions` table stores strictly the SHA-256 hash of the token.
- **Session Sliding:** Valid requests update `last_activity` and extend expiration by 8 hours (`DEFAULT_SESSION_TTL_SECONDS = 28800`).
- **Brute-Force Protection:** Sliding-window `RateLimiter` enforces request rate quotas on login and sensitive endpoints. In addition, 5 consecutive failed login attempts automatically lock the user account (`locked = 1`).
- **CSRF Defense:** Cookie-authenticated mutating requests (POST/PUT/DELETE/PATCH) require an accompanying `X-CSRF-Token` validated in constant time via `CsrfTokenManager`. Bearer tokens sent in `Authorization` headers are intrinsically exempt.

### 2.3 Initial Administrator Provisioning
- If environment variable `TRYST_ADMIN_PASSWORD` or system property `tryst.admin.password` is set, the administrator account is provisioned during startup.
- If no administrator exists and no secret is supplied, `AuthenticationService` generates a high-entropy single-use setup token printed to the secure console log. The first-run web wizard at `/api/setup` requires this token to create the initial admin account.

---

## 3. Role-Based Access Control (RBAC) & Service Boundaries

TRYST defines 5 hierarchical user roles:

| Role | Description | Permissions |
|---|---|---|
| `ADMIN` | System administrator | Full platform control, database vacuum/integrity, user management, process termination, all projects and workspaces. |
| `PROJECT_OWNER`| Project creator/lead | Workspace management, project settings, task dispatch, code review approval for owned projects. |
| `DEVELOPER` | Software developer | Task creation, session interaction, model querying, code review participation, Git push. |
| `REVIEWER` | Quality assurance/reviewer | Review inspection, unified diff editing, review comments. Cannot commit or execute tasks. |
| `USER` | Read-only observer | Project inspection, public model listing, notification reading. Cannot execute tasks or edit code. |

### 3.1 Service Boundary Authorization Checks
All domain services enforce independent security checks:
- **`PermissionManager.validateUserProjectAccess`**: Asserts project ownership or administrator privileges.
- **`PermissionManager.validateWorkspaceAccess`**: Prevents cross-tenant access to isolated task workspaces.
- **`PermissionManager.validateTaskAccess`**: Checks user role permissions (`CANCEL_TASKS`, `EDIT_PROPOSED_CHANGES`, etc.) and task ownership.
- **`PermissionManager.validateReviewAccess`**: Enforces permissions on review editing, approval, and rework requests.
- **`PermissionManager.validateGitOperation`**: Gates repository checkout, diffing, committing, and remote push actions.

---

## 4. Path Traversal and Symlink Escape Defense

`PermissionManager.validatePathWithinWorkspace(Path targetPath, Workspace workspace)`:
1. **Null-Byte Defense:** Explicitly detects and rejects null bytes (`\0`) in paths.
2. **Lexical Normalization:** Converts paths to absolute normalized representations and asserts `targetPath.startsWith(workspace.getRootPath())`.
3. **Canonical Real-Path Resolution:** Resolves physical links on disk using `toRealPath()`. If an existing symlink points outside the authorized workspace root directory, access is rejected immediately and an audit event is recorded.

---

## 5. Cryptographic Git Commit Gating

Autonomous AI agents are strictly forbidden from committing directly to Git branches.
- Commits are executed exclusively via `GitService.commitAuthorizedReview(Path repoDir, CodeReview review, String authorizerId, String commitMessage)`.
- Pre-conditions:
  1. `review.isApproved()` must be `true`.
  2. The working tree unified diff must be generated and its SHA-256 fingerprint computed.
  3. The computed fingerprint must match `review.getApprovedFingerprint()`.
- If any file in the working tree is modified after approval, the fingerprint check fails, blocking the commit and protecting against time-of-check/time-of-use (TOCTOU) bypasses.

---

## 6. GitHub Credentials and Remote VCS Security

1. **Token Protection at Rest:**
   - GitHub Personal Access Tokens and OAuth tokens are NEVER stored in plaintext in memory or in the database.
   - Tokens are encrypted at rest using server-side AES-256-GCM (`DefaultGitHubCredentialEncryptionService`) with 100,000-iteration PBKDF2 key derivation.
2. **Token Masking:**
   - Tokens are NEVER rendered in plaintext in web responses, API payloads, Eclipse RCP views, process execution logs, or diagnostic dumps.
   - Masking ensures only prefixes and non-sensitive trailing characters are shown (e.g., `ghp_****a1b2`).
3. **Least-Privilege and Push Authorization:**
   - Remote pushes to GitHub branches require explicit human authorization via `GitHubService.pushAuthorized`.
   - Pushes are strictly blocked unless an associated `CodeReview` is in `APPROVED` status with matching cryptographic content fingerprint.
   - Remote change conflicts are proactively checked prior to pushing (`hasRemoteConflicts`).
4. **Execution Boundaries:**
   - Background Git processes run non-interactively with `GIT_TERMINAL_PROMPT=0` and `GIT_ASKPASS=echo` to prevent malicious or accidental credential prompting and worker thread starvation.

---

## 7. HTTP Security and Endpoint Protection

- **HTTP Security Headers:**
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `Permissions-Policy: camera=(), microphone=(), geolocation=()`
- **Rate Limiting:**
  - Dynamic sliding-window rate limiters protect authentication (`/api/auth/login`) and mutating task APIs.
- **Audit Trail:**
  - All permission evaluations (allowed and denied), login attempts, and boundary violations are recorded in the persistent `audit_events` table and in-memory audit logs.
