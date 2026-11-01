# TRYST Database Migrations

## 1. Migration Strategy

TRYST uses a deterministic, code-based schema versioning engine managed by `SchemaMigrationService`.

- Current schema version is stored in the `schema_version` table.
- Migrations are applied in strictly sequential order.
- Each migration executes within an atomic transaction. If any DDL or DML statement fails, the transaction is rolled back and an exception is raised, aborting startup and preserving the previous state.
- `CREATE TABLE IF NOT EXISTS` is supplemented with explicit migration version checks.

## 2. Implemented Migrations

### Version 1: Initial Relational Schema
- Creates tables: `projects`, `workspaces`, `sessions`, `tasks`, `conversation_messages`, `execution_results`, `agents`.
- Creates foreign key constraints with appropriate deletion behavior:
  - `sessions` -> `projects`: ON DELETE CASCADE
  - `tasks` -> `projects`: ON DELETE SET NULL (avoids deleting execution history)
  - `tasks` -> `sessions`: ON DELETE SET NULL
  - `conversation_messages` -> `sessions`: ON DELETE CASCADE
  - `execution_results` -> `tasks`: ON DELETE CASCADE
  - `workspaces` -> `projects`: ON DELETE SET NULL
- Creates performance indexes on all foreign keys and frequently queried status fields.

## 3. How to Add New Migrations
1. Add a new `Migration(N, "Description", statements)` to the list in `SchemaMigrationService.getMigrations()`.
2. Ensure statements are idempotent where possible and test the sequential execution from version 1 to N.
