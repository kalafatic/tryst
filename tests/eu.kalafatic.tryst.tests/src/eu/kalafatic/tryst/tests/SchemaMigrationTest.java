package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigrationTest {

    @Test
    void testSequentialMigrationTracksVersionAndCreatesTables(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("migration_test.db");

        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();

            SchemaMigrationService migrationService = new SchemaMigrationService(dbManager);
            assertEquals(0, migrationService.getCurrentVersion());

            migrationService.migrate();
            assertEquals(3, migrationService.getCurrentVersion());

            // Verify all expected tables exist
            Connection conn = dbManager.getConnection();
            DatabaseMetaData metaData = conn.getMetaData();
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }

            assertTrue(tables.contains("schema_version"));
            assertTrue(tables.contains("projects"));
            assertTrue(tables.contains("workspaces"));
            assertTrue(tables.contains("sessions"));
            assertTrue(tables.contains("tasks"));
            assertTrue(tables.contains("conversation_messages"));
            assertTrue(tables.contains("execution_results"));
            assertTrue(tables.contains("agents"));
            assertTrue(tables.contains("code_reviews"));
            assertTrue(tables.contains("review_file_edits"));
            assertTrue(tables.contains("users"));
            assertTrue(tables.contains("user_sessions"));
            assertTrue(tables.contains("audit_events"));
            assertTrue(tables.contains("telemetry_snapshots"));
            assertTrue(tables.contains("notifications"));
            assertTrue(tables.contains("managed_processes"));
        }
    }
}
