# TRYST Authentication and Authorization Architecture

## 1. Overview
TRYST enforces strict, server-verified identity, authentication, and role-based access control (RBAC). Arbitrary client header spoofing (`X-User-Id`) has been completely superseded by cryptographic bearer token validation and timing-attack resistant password verification.

## 2. Core Components

### 2.1 Password Hashing (`PasswordHasher`)
- **Algorithm**: `PBKDF2WithHmacSHA256`
- **Iterations**: 210,000 calibrated iterations
- **Key Length**: 256-bit derived key
- **Salt**: 32-byte cryptographically secure random salt generated via `java.security.SecureRandom`
- **Comparison**: `MessageDigest.isEqual` ensures constant-time verification to eliminate timing attacks.

### 2.2 Session Management (`AuthenticationService` & `UserSessionRepository`)
- **Session Tokens**: 256-bit high-entropy random byte arrays generated via `SecureRandom` and hex-encoded (64 characters).
- **Token Hashing**: Bearer tokens are stored server-side as SHA-256 hashes (`token_hash`) in the `user_sessions` SQLite table. Raw tokens are never stored in plaintext and never leaked in logs.
- **Session Fixation**: Session tokens are rotated upon successful login.
- **Expiration & Sliding Window**: Configured with an 8-hour lifetime (`DEFAULT_SESSION_TTL_SECONDS = 28800`). Sessions are refreshed on activity and immediately terminated on revocation or logout.
- **Brute Force Protection**: Failed login attempts increment `failed_attempts` on the user account; 5 consecutive failures lock the account (`is_locked = 1`).

### 2.3 Role-Based Access Control (RBAC)
User roles and assigned permissions:
- **`ADMIN`**: Complete administrative authority over users, sessions, configuration, audit history, database backups, and server observability.
- **`PROJECT_OWNER`**: Full project control, task creation, editing diffs, approving reviews, and committing changes.
- **`DEVELOPER`**: Workspace editing, review editing, approving reviews, and executing commits.
- **`REVIEWER`**: Read-only inspection of diffs, test summaries, and project state. Cannot edit or commit.
- **`USER`**: Basic project and task execution privileges without administrative access.

Permissions:
`MANAGE_USERS`, `MANAGE_ROLES`, `MANAGE_SERVER_CONFIG`, `INSPECT_SERVER_HEALTH`, `VIEW_AUDIT_LOGS`, `CREATE_PROJECTS`, `ACCESS_PROJECTS`, `EXECUTE_TASKS`, `CANCEL_TASKS`, `MANAGE_AGENTS`, `EDIT_PROPOSED_CHANGES`, `APPROVE_REVIEWS`, `COMMIT_APPROVED_CHANGES`, `MANAGE_SESSIONS`.

### 2.4 Service-Boundary Enforcement
Authorization checks are performed in application services via `PermissionManager` and `SecurityContext`, ensuring non-HTTP callers and OSGi consumers are subject to identical boundary guarantees.
