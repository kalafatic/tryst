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

        return migrations;
    }
}
