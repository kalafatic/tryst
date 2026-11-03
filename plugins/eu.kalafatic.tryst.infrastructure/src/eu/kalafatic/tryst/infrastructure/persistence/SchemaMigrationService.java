package eu.kalafatic.tryst.infrastructure.persistence;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Migration engine tracking schema versions in 'schema_version'
 * and applying versioned migrations sequentially and transactionally.
 */
public class SchemaMigrationService {

    public record Migration(int version, String description, List<String> sqlStatements) {}

    private final SqliteDatabaseManager databaseManager;

    public SchemaMigrationService(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    public synchronized void migrate() {
        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            ensureSchemaVersionTable(conn);

            int currentVersion = getCurrentVersion(conn);
            List<Migration> availableMigrations = getMigrations();

            for (Migration migration : availableMigrations) {
                if (migration.version() > currentVersion) {
                    applyMigration(conn, migration);
                }
            }
        }
    }

    private void ensureSchemaVersionTable(Connection conn) {
        String sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                description TEXT NOT NULL,
                applied_at TEXT NOT NULL
            );
        """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new TrystInfrastructureException("Failed to create schema_version table", e);
        }
    }

    public int getCurrentVersion() {
        synchronized (databaseManager.getLock()) {
            return getCurrentVersion(databaseManager.getConnection());
        }
    }

    private int getCurrentVersion(Connection conn) {
        ensureSchemaVersionTable(conn);
        String sql = "SELECT COALESCE(MAX(version), 0) FROM schema_version;";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new TrystInfrastructureException("Failed to read schema_version", e);
        }
    }

    private void applyMigration(Connection conn, Migration migration) {
        TrystLog.info(SchemaMigrationService.class,
                "Applying schema migration V" + migration.version() + ": " + migration.description());

        databaseManager.runInTransaction(() -> {
            try {
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : migration.sqlStatements()) {
                        stmt.execute(sql);
                    }
                }

                String insertSql = "INSERT INTO schema_version (version, description, applied_at) VALUES (?, ?, ?);";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    pstmt.setInt(1, migration.version());
                    pstmt.setString(2, migration.description());
                    pstmt.setString(3, Instant.now().toString());
                    pstmt.executeUpdate();
                }
            } catch (SQLException e) {
                throw new TrystInfrastructureException(
                        "Migration V" + migration.version() + " failed: " + migration.description(), e);
            }
        });

        TrystLog.info(SchemaMigrationService.class,
                "Schema migration V" + migration.version() + " applied successfully.");
    }

    private List<Migration> getMigrations() {
        List<Migration> migrations = new ArrayList<>();

        // V1: Initial Normalized Relational Schema
        List<String> v1Statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL DEFAULT 'default-user',
                    name TEXT NOT NULL,
                    description TEXT,
                    location TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_projects_active ON projects(active);",
                "CREATE INDEX IF NOT EXISTS idx_projects_owner ON projects(owner_id);",

                """
                CREATE TABLE IF NOT EXISTS workspaces (
                    id TEXT PRIMARY KEY,
                    root_path TEXT NOT NULL,
                    active_project_id TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (active_project_id) REFERENCES projects(id) ON DELETE SET NULL
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_sessions_project_id ON sessions(project_id);",
                "CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);",

                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    project_id TEXT,
                    session_id TEXT,
                    title TEXT NOT NULL,
                    description TEXT,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    completed_at TEXT,
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE SET NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_tasks_project_id ON tasks(project_id);",
                "CREATE INDEX IF NOT EXISTS idx_tasks_session_id ON tasks(session_id);",
                "CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);",

                """
                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_messages_session_created ON conversation_messages(session_id, created_at);",

                """
                CREATE TABLE IF NOT EXISTS execution_results (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    exit_code INTEGER NOT NULL,
                    output TEXT,
                    error_output TEXT,
                    duration_ms INTEGER NOT NULL,
                    completed_at TEXT NOT NULL,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_results_task_id ON execution_results(task_id);",

                """
                CREATE TABLE IF NOT EXISTS agents (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL UNIQUE,
                    model_name TEXT NOT NULL,
                    system_prompt TEXT,
                    configuration_json TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """
        );
        migrations.add(new Migration(1, "Initial relational schema", v1Statements));

        // V2: Project Isolation & Code Review Center Schema
        List<String> v2Statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS code_reviews (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    branch TEXT NOT NULL,
                    status TEXT NOT NULL,
                    initial_diff TEXT,
                    effective_diff TEXT,
                    content_fingerprint TEXT,
                    approved_fingerprint TEXT,
                    commit_hash TEXT,
                    approved_by TEXT,
                    rejected_by TEXT,
                    rejection_reason TEXT,
                    build_status TEXT,
                    test_summary TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_code_reviews_project ON code_reviews(project_id);",
                "CREATE INDEX IF NOT EXISTS idx_code_reviews_task ON code_reviews(task_id);",
                "CREATE INDEX IF NOT EXISTS idx_code_reviews_status ON code_reviews(status);",

                """
                CREATE TABLE IF NOT EXISTS review_file_edits (
                    id TEXT PRIMARY KEY,
                    review_id TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    original_content TEXT,
                    modified_content TEXT,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (review_id) REFERENCES code_reviews(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_review_file_edits_review ON review_file_edits(review_id);"
        );
        migrations.add(new Migration(2, "Project isolation and code review persistence", v2Statements));

        // V3: Production Platform - Users, Sessions, Audit, Telemetry, Notifications, and Managed Processes
        List<String> v3Statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'USER',
                    active INTEGER NOT NULL DEFAULT 1,
                    locked INTEGER NOT NULL DEFAULT 0,
                    failed_attempts INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);",
                "CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);",

                """
                CREATE TABLE IF NOT EXISTS user_sessions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    client_ip TEXT,
                    user_agent TEXT,
                    expires_at TEXT NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    last_activity TEXT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id);",
                "CREATE INDEX IF NOT EXISTS idx_user_sessions_token ON user_sessions(token_hash);",
                "CREATE INDEX IF NOT EXISTS idx_user_sessions_revoked ON user_sessions(revoked);",

                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id TEXT PRIMARY KEY,
                    event_time TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    target_id TEXT,
                    project_id TEXT,
                    task_id TEXT,
                    outcome TEXT NOT NULL,
                    details_json TEXT
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_audit_events_time ON audit_events(event_time);",
                "CREATE INDEX IF NOT EXISTS idx_audit_events_actor ON audit_events(actor_id);",
                "CREATE INDEX IF NOT EXISTS idx_audit_events_action ON audit_events(action);",
                "CREATE INDEX IF NOT EXISTS idx_audit_events_project ON audit_events(project_id);",

                """
                CREATE TABLE IF NOT EXISTS telemetry_snapshots (
                    id TEXT PRIMARY KEY,
                    timestamp TEXT NOT NULL,
                    cpu_system REAL,
                    cpu_process REAL,
                    memory_heap_used INTEGER,
                    memory_heap_max INTEGER,
                    memory_system_used INTEGER,
                    disk_workspace_used INTEGER,
                    disk_workspace_total INTEGER,
                    active_sessions INTEGER,
                    queued_tasks INTEGER,
                    running_tasks INTEGER,
                    ai_latency_ms REAL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_telemetry_time ON telemetry_snapshots(timestamp);",

                """
                CREATE TABLE IF NOT EXISTS notifications (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    message TEXT NOT NULL,
                    reference_type TEXT,
                    reference_id TEXT,
                    is_read INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_notifications_user_read ON notifications(user_id, is_read);",

                """
                CREATE TABLE IF NOT EXISTS managed_processes (
                    id TEXT PRIMARY KEY,
                    task_id TEXT,
                    pid INTEGER NOT NULL,
                    command_class TEXT NOT NULL,
                    working_dir TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    exit_code INTEGER,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_managed_processes_task ON managed_processes(task_id);",
                "CREATE INDEX IF NOT EXISTS idx_managed_processes_status ON managed_processes(status);"
        );
        migrations.add(new Migration(3, "Production platform security, observability and operational intelligence", v3Statements));

        // V4: AI Providers and Model Publication Catalog
        List<String> v4Statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS ai_providers (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    provider_type TEXT NOT NULL,
                    endpoint TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    health_status TEXT NOT NULL DEFAULT 'UNKNOWN',
                    last_checked_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_ai_providers_enabled ON ai_providers(enabled);",

                """
                CREATE TABLE IF NOT EXISTS ai_models (
                    id TEXT PRIMARY KEY,
                    provider_id TEXT NOT NULL,
                    model_identifier TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    capabilities_json TEXT,
                    context_limit INTEGER NOT NULL DEFAULT 4096,
                    available INTEGER NOT NULL DEFAULT 1,
                    published INTEGER NOT NULL DEFAULT 1,
                    min_role TEXT NOT NULL DEFAULT 'USER',
                    last_verified_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (provider_id) REFERENCES ai_providers(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_ai_models_provider ON ai_models(provider_id);",
                "CREATE INDEX IF NOT EXISTS idx_ai_models_available ON ai_models(available);",
                "CREATE INDEX IF NOT EXISTS idx_ai_models_published ON ai_models(published);"
        );
        migrations.add(new Migration(4, "AI providers and model publication catalog", v4Statements));

        // V5: Asynchronous Task Execution Infrastructure & Event Audit
        List<String> v5Statements = List.of(
                """
                ALTER TABLE tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL';
                """,
                """
                ALTER TABLE tasks ADD COLUMN agent_id TEXT;
                """,
                """
                ALTER TABLE tasks ADD COLUMN worker_id TEXT;
                """,
                """
                ALTER TABLE tasks ADD COLUMN execution_result_id TEXT;
                """,
                """
                ALTER TABLE tasks ADD COLUMN review_id TEXT;
                """,
                """
                ALTER TABLE tasks ADD COLUMN rework_count INTEGER NOT NULL DEFAULT 0;
                """,
                """
                ALTER TABLE tasks ADD COLUMN failure_reason TEXT;
                """,
                "CREATE INDEX IF NOT EXISTS idx_tasks_worker_id ON tasks(worker_id);",
                "CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(priority);",

                """
                CREATE TABLE IF NOT EXISTS task_events (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    status TEXT NOT NULL,
                    message TEXT NOT NULL,
                    details TEXT,
                    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_task_events_task ON task_events(task_id);",
                "CREATE INDEX IF NOT EXISTS idx_task_events_timestamp ON task_events(timestamp);"
        );
        migrations.add(new Migration(5, "Asynchronous task execution infrastructure and event logs", v5Statements));

        return migrations;
    }
}
