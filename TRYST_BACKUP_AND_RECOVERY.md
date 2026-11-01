# TRYST Backup and Recovery

## 1. Overview
The database backup mechanism in TRYST is implemented by `SqliteBackupService`. It ensures that database backups are taken safely without corrupting active WAL write transactions.

## 2. Backup Strategy
- **SQLite Online Backup (`VACUUM INTO`)**:
  - The service executes `VACUUM INTO '<destination>'`.
  - SQLite creates an atomic, consistent, defragmented point-in-time snapshot of the database file at the destination.
- **Fallback Mechanism**:
  - If `VACUUM INTO` is unsupported, the service calls `PRAGMA wal_checkpoint(TRUNCATE)` within a synchronized lock to flush WAL logs, followed by an atomic filesystem copy.

## 3. Backup File Locations and Naming
- Backups are stored in isolated destination directories (outside the live database file).
- File naming format: `tryst_backup_<ISO-8601-TIMESTAMP>.db`.

## 4. Restoration Procedure
1. Shut down the TRYST application completely to release database file locks.
2. Navigate to the authoritative data directory (default: `~/.tryst/`).
3. Back up or remove the existing `tryst.db` and any associated `-wal` and `-shm` files.
4. Copy the desired `tryst_backup_<timestamp>.db` into `~/.tryst/tryst.db`.
5. Restart TRYST. Upon startup, `SqliteDatabaseManager` verifies integrity and `SchemaMigrationService` validates the schema version.
