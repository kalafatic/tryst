package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseInitializationTest {

    @Test
    public void testDatabaseInitializationAndPragmas(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("db/init_test.db");
        assertFalse(Files.exists(dbPath));

        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            assertTrue(Files.exists(dbPath));

            Connection conn = dbManager.getConnection();
            assertNotNull(conn);
            assertFalse(conn.isClosed());

            try (Statement stmt = conn.createStatement()) {
                // Verify foreign keys are enabled
                try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }

                // Verify journal mode is WAL
                try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
                    assertTrue(rs.next());
                    assertEquals("wal", rs.getString(1).toLowerCase());
                }

                // Verify busy timeout is configured (5000ms)
                try (ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout;")) {
                    assertTrue(rs.next());
                    assertEquals(5000, rs.getInt(1));
                }
            }
        }
    }

    @Test
    public void testRepeatedStartupIsIdempotentAndSafe(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("idempotent.db");

        // First startup
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(dbManager);
            migration.migrate();
            assertEquals(1, migration.getCurrentVersion());
        }

        // Second startup - reopen same DB
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(dbManager);
            migration.migrate();
            assertEquals(1, migration.getCurrentVersion());
        }
    }
}
