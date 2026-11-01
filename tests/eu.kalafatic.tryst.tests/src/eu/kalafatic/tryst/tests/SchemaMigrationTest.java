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

import static org.junit.jupiter.api.Assertions.*;

public class SchemaMigrationTest {

    @Test
    public void testSequentialMigrationTracksVersionAndCreatesTables(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("migration.db");

        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();

            SchemaMigrationService migrationService = new SchemaMigrationService(dbManager);
            assertEquals(0, migrationService.getCurrentVersion());

            migrationService.migrate();
            assertEquals(1, migrationService.getCurrentVersion());

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
        }
    }
}
