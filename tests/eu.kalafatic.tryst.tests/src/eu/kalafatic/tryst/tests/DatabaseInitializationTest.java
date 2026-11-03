package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseInitializationTest {

    @Test
    void testDatabaseInitializationAndPragmas(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("test.db");
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(dbManager);
            migration.migrate();
            assertEquals(5, migration.getCurrentVersion());
        }
    }

    @Test
    void testRepeatedStartupIsIdempotentAndSafe(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("idempotent.db");

        // First startup
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(dbManager);
            migration.migrate();
            assertEquals(5, migration.getCurrentVersion());
        }

        // Second startup - reopen same DB
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            SchemaMigrationService migration = new SchemaMigrationService(dbManager);
            migration.migrate(); // Should safely do nothing
            assertEquals(5, migration.getCurrentVersion());
        }
    }
}
