package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteBackupService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseBackupTest {

    @Test
    public void testBackupCreationAndRestoration(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("original.db");
        Path backupDir = tempDir.resolve("backups");

        String projectId;
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            new SchemaMigrationService(dbManager).migrate();

            SqliteProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
            Project project = new Project("OriginalProject", Path.of("/tmp/orig"));
            projectRepo.save(project);
            projectId = project.getId();

            SqliteBackupService backupService = new SqliteBackupService(dbManager);
            Path backupFile = backupService.createBackup(backupDir);

            assertTrue(Files.exists(backupFile));
            assertTrue(Files.size(backupFile) > 0);

            // Reopen backup file directly as a new SqliteDatabaseManager to verify restore integrity
            try (SqliteDatabaseManager restoredDb = new SqliteDatabaseManager(backupFile)) {
                restoredDb.initialize();
                SqliteProjectRepository restoredRepo = new SqliteProjectRepository(restoredDb);
                Optional<Project> restored = restoredRepo.findById(projectId);
                assertTrue(restored.isPresent());
                assertEquals("OriginalProject", restored.get().getName());
            }
        }
    }
}
