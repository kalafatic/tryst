# TRYST Database and Administrator Bootstrap

## 1. Automatic SQLite Persistence
TRYST stores persistent data in `~/.tryst/tryst.db` using standard JDBC via `SqliteDatabaseManager`.
Key attributes:
- `PRAGMA foreign_keys = ON;`
- `PRAGMA journal_mode = WAL;`
- `PRAGMA busy_timeout = 5000;`
- `PRAGMA integrity_check;` executed automatically on connection startup.

## 2. Versioned Migrations
- `V1`: Core workspace, projects, sessions, tasks, messages, execution results, agents.
- `V2`: Code Review Center, file diffs, approvals.
- `V3`: RBAC users, sessions, audit events, telemetry snapshots, notifications, managed child processes.
- `V4`: AI providers, model publication catalog.

## 3. Secure Administrator Bootstrap & Wizard
When the server initializes and finds no existing administrator:
1. If `TRYST_ADMIN_PASSWORD` (environment) or `tryst.admin.password` (JVM property) is provided, the initial administrator (`admin`) is created with PBKDF2WithHmacSHA256 password hashing (210,000 iterations, 32-byte salt).
2. If neither secret is present, `AuthenticationService` generates a cryptographic 256-bit one-time setup token.
3. The server sets `setupRequired = true` on `/api/status`.
4. A first-run setup wizard interacts with `/api/setup` to provision the administrator using the single-use token.
5. Upon successful setup, the one-time token is cleared, preventing replay attacks or authentication bypass.
