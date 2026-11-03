# TRYST Security and Authorization Model

**Document Identifier:** `TRYST-ARCH-SEC-001`
**Status:** APPROVED BASELINE
**Classification:** Security Architecture Specification

---

## 1. Security Architecture Principles

TRYST applies defense in depth across all execution layers:
1. **Server-Enforced RBAC:** Client-side headers (such as `X-User-Id`) cannot bypass authorization. Authentication credentials must be verified on the server.
2. **Cryptographic Integrity:** Credential hashing and Git diff commit authorization rely on standard cryptographic primitives.
3. **Canonical Workspace Containment:** All file and process tools resolve target paths to canonical real paths (`toRealPath()`) to prevent directory traversal and symlink escapes.
4. **Auditability:** Security events, authentication failures, access violations, and administrative actions are persistently recorded in the `audit_events` table.

---

## 2. Authentication and Credential Storage

### 2.1 Password Hashing Specification
- **Algorithm:** PBKDF2 with HMAC-SHA256 (`PBKDF2WithHmacSHA256`).
- **Iterations:** 210,000 rounds.
- **Salt:** 32 cryptographically secure random bytes (64 hex characters) generated via `java.security.SecureRandom`.
- **Key Length:** 256 bits.
- **Verification:** Constant-time byte array comparison (`MessageDigest.isEqual`) to prevent timing side-channel attacks.

### 2.2 Bearer Session Management
- **Entropy:** 256-bit cryptographically secure random values formatted as 64-character hexadecimal strings.
- **Server-Side Token Storage:** Tokens are NEVER stored in plaintext. The database `user_sessions` table stores strictly the SHA-256 hash of the token.
- **Session Sliding:** Valid requests update `last_activity` and extend expiration by 8 hours (`DEFAULT_SESSION_TTL_SECONDS = 28800`).
- **Brute-Force Protection:** 5 consecutive failed login attempts automatically lock the user account (`locked = 1`).

### 2.3 Initial Administrator Provisioning
- If environment variable `TRYST_ADMIN_PASSWORD` or system property `tryst.admin.password` is set, the administrator account is provisioned during startup.
- If no administrator exists and no secret is supplied, `AuthenticationService` generates a high-entropy single-use setup token printed to the secure console log. The first-run web wizard at `/api/setup` requires this token to create the initial admin account.

---

## 3. Role-Based Access Control (RBAC)

TRYST defines 5 hierarchical user roles:

| Role | Description | Permissions |
|---|---|---|
| `ADMIN` | System administrator | Full platform control, database vacuum/integrity, user management, process termination, all projects. |
| `PROJECT_OWNER`| Project creator/lead | Workspace management, project settings, task dispatch, code review approval for owned projects. |
| `DEVELOPER` | Software developer | Task creation, session interaction, model querying, code review participation. |
| `REVIEWER` | Quality assurance/reviewer | Heuristic review inspection, unified diff editing, review approval/rejection. |
| `USER` | Read-only observer | Project inspection, public model listing, notification reading. |

---

## 4. Path Traversal and Symlink Escape Defense

`PermissionManager.validatePathWithinWorkspace(Path targetPath, Workspace workspace)`:
1. **Lexical Normalization:** Converts paths to absolute normalized representations and asserts `targetPath.startsWith(workspace.getRootPath())`.
2. **Canonical Real-Path Resolution:** Resolves physical links on disk using `toRealPath()`. If an existing symlink points outside the authorized workspace root directory, access is rejected immediately and an audit event is recorded.

---

## 5. Cryptographic Git Commit Gating

Autonomous AI agents are strictly forbidden from committing directly to Git branches.
- Commits are executed exclusively via `GitService.commitAuthorizedReview(Path repoDir, CodeReview review, String authorizerId, String commitMessage)`.
- Pre-conditions:
  1. `review.isApproved()` must be `true`.
  2. The working tree unified diff must be generated and its SHA-256 fingerprint computed.
  3. The computed fingerprint must match `review.getApprovedFingerprint()`.
- If any file in the working tree is modified after approval, the fingerprint check fails, blocking the commit and protecting against time-of-check/time-of-use (TOCTOU) bypasses.
