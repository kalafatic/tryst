# TRYST Persistence Architecture

## 1. Overview
The TRYST persistence architecture provides a robust, decoupled, and relational local storage mechanism using SQLite and standard JDBC. It is designed to preserve application state across restarts without introducing heavy ORM frameworks (like Hibernate/JPA) or external server dependencies.

## 2. Layer Separation

```
PRESENTATION (eu.kalafatic.tryst.ui)
     |
     v
APPLICATION SERVICES (eu.kalafatic.tryst.application)
     |
     v
DOMAIN MODEL & REPOSITORY CONTRACTS (eu.kalafatic.tryst.domain)
     |
     v
SQLITE REPOSITORIES & INFRASTRUCTURE (eu.kalafatic.tryst.infrastructure)
     |
     v
SQLITE DATABASE (Authoritative location: ~/.tryst/tryst.db)
```

- **Domain Model & Repository Contracts (`eu.kalafatic.tryst.domain`)**:
  - Contains pure domain entities (`Project`, `Workspace`, `AgentTask`, `Session`, `ConversationMessage`, `AgentConfiguration`, `ExecutionResult`).
  - Contains persistence-agnostic repository interfaces (`ProjectRepository`, `WorkspaceRepository`, `SessionRepository`, `TaskRepository`, `ConversationMessageRepository`, `ExecutionResultRepository`, `AgentRepository`).
  - Contains `UnitOfWork` interface for atomic transactional boundaries.
  - Strictly free from any JDBC, SQL, SQLite, or persistence annotations.

- **Infrastructure Layer (`eu.kalafatic.tryst.infrastructure`)**:
  - Implements the domain repository contracts with explicit parameterized SQL using SQLite and JDBC.
  - Owns the `SqliteDatabaseManager`, `SchemaMigrationService`, and `SqliteBackupService`.
  - Configures SQLite pragmas: `PRAGMA foreign_keys = ON;`, `PRAGMA journal_mode = WAL;`, `PRAGMA busy_timeout = 5000;`.
  - Provides synchronized access and transactional control.

- **Application Services (`eu.kalafatic.tryst.application`)**:
  - Coordinates database initialization, migrations, and service registration during `CORE_SERVICE_INITIALIZATION` lifecycle stage.
  - Coordinates database closing during `SERVICE_DISPOSAL`.

## 3. Database Location and Ownership
- Authoritative location: `ApplicationConfiguration.configurationDirectory().resolve("tryst.db")` (default: `~/.tryst/tryst.db`).
- Not located in the installation folder, source tree, or individual project workspaces.
- Access is strictly owned by `SqliteDatabaseManager`.
