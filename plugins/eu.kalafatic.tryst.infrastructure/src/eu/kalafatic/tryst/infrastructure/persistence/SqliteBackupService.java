package eu.kalafatic.tryst.infrastructure.persistence;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Service providing safe, consistent database backups.
 * Ensures SQLite write locks / VACUUM INTO is used so no active writing corrupts the backup.
 */
public class SqliteBackupService {

    private final SqliteDatabaseManager databaseManager;

    public SqliteBackupService(SqliteDatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager must not be null");
    }

    /**
     * Creates an authoritative backup at the given target path.
     */
    public Path createBackup(Path destinationDirectory) {
        Objects.requireNonNull(destinationDirectory, "destinationDirectory must not be null");
        try {
            Files.createDirectories(destinationDirectory);
        } catch (IOException e) {
            throw new TrystInfrastructureException("Failed to create backup directory: " + destinationDirectory, e);
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-");
        Path backupFile = destinationDirectory.resolve("tryst_backup_" + timestamp + ".db");

        synchronized (databaseManager.getLock()) {
            Connection conn = databaseManager.getConnection();
            try (Statement stmt = conn.createStatement()) {
                // Use SQLite VACUUM INTO for a safe, atomic online backup
                String sql = "VACUUM INTO '" + backupFile.toAbsolutePath().normalize() + "'";
                stmt.execute(sql);
                TrystLog.info(SqliteBackupService.class, "Database backup created successfully at " + backupFile);
                return backupFile;
            } catch (Exception e) {
                // If VACUUM INTO fails (e.g. older SQLite version or read-only directory), attempt safe file copy with checkpoint
                try {
                    try (Statement checkpointStmt = conn.createStatement()) {
                        checkpointStmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
                    }
                    Files.copy(databaseManager.getDatabasePath(), backupFile, StandardCopyOption.REPLACE_EXISTING);
                    TrystLog.info(SqliteBackupService.class, "Database fallback copy backup created at " + backupFile);
                    return backupFile;
                } catch (Exception copyEx) {
                    copyEx.addSuppressed(e);
                    throw new TrystInfrastructureException("Failed to perform backup to " + backupFile, copyEx);
                }
            }
        }
    }
}
