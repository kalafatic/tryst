package eu.kalafatic.tryst.infrastructure.persistence;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.repository.UnitOfWork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authoritative SQLite database connection manager and transaction coordinator.
 * Configures connection pragmas (foreign keys, WAL mode, busy timeout)
 * and serializes database transaction access.
 */
public class SqliteDatabaseManager implements UnitOfWork, AutoCloseable {

    private final Path databasePath;
    private final String jdbcUrl;
    private Connection connection;
    private final Object dbLock = new Object();

    public SqliteDatabaseManager(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null");
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
    }

    public void initialize() {
        synchronized (dbLock) {
            try {
                if (databasePath.getParent() != null) {
                    Files.createDirectories(databasePath.getParent());
                }

                // Explicit driver registration for OSGi dynamic classloading
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    throw new TrystInfrastructureException("SQLite JDBC driver not found on classpath", e);
                }

                this.connection = DriverManager.getConnection(jdbcUrl);
                configureConnectionPragmas(connection);
                verifyDatabaseIntegrity(connection);

                TrystLog.info(SqliteDatabaseManager.class, "SQLite database connection established and verified at " + databasePath);
            } catch (SQLException | IOException e) {
                throw new TrystInfrastructureException("Failed to initialize SQLite database at " + databasePath, e);
            }
        }
    }

    public boolean verifyDatabaseIntegrity() {
        synchronized (dbLock) {
            return verifyDatabaseIntegrity(getConnection());
        }
    }

    private boolean verifyDatabaseIntegrity(Connection conn) {
        try (Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("PRAGMA integrity_check;")) {
            if (rs.next()) {
                String result = rs.getString(1);
                if ("ok".equalsIgnoreCase(result)) {
                    TrystLog.info(SqliteDatabaseManager.class, "SQLite database integrity verified: ok");
                    return true;
                } else {
                    TrystLog.error(SqliteDatabaseManager.class, "SQLite database integrity check failed: " + result);
                    throw new TrystInfrastructureException("Database integrity check failed: " + result);
                }
            }
            return true;
        } catch (SQLException e) {
            throw new TrystInfrastructureException("Error executing integrity_check on SQLite database", e);
        }
    }

    private void configureConnectionPragmas(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Enable foreign keys
            stmt.execute("PRAGMA foreign_keys = ON;");
            // Set busy timeout to 5000ms
            stmt.execute("PRAGMA busy_timeout = 5000;");
            // Set WAL journal mode
            stmt.execute("PRAGMA journal_mode = WAL;");
            // Normal synchronous mode for good durability and high performance with WAL
            stmt.execute("PRAGMA synchronous = NORMAL;");
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public Connection getConnection() {
        synchronized (dbLock) {
            if (connection == null) {
                initialize();
            }
            return connection;
        }
    }

    public Object getLock() {
        return dbLock;
    }

    @Override
    public void runInTransaction(Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        supplyInTransaction(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T supplyInTransaction(Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        synchronized (dbLock) {
            Connection conn = getConnection();
            boolean initialAutoCommit;
            try {
                initialAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
            } catch (SQLException e) {
                throw new TrystInfrastructureException("Failed to begin transaction", e);
            }

            try {
                T result = action.get();
                conn.commit();
                return result;
            } catch (Throwable t) {
                try {
                    conn.rollback();
                } catch (SQLException rbEx) {
                    t.addSuppressed(rbEx);
                }
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new TrystInfrastructureException("Transaction failed and was rolled back", t);
            } finally {
                try {
                    conn.setAutoCommit(initialAutoCommit);
                } catch (SQLException e) {
                    TrystLog.warn(SqliteDatabaseManager.class, "Failed to restore connection autoCommit: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (dbLock) {
            if (connection != null) {
                try {
                    if (!connection.isClosed()) {
                        connection.close();
                        TrystLog.info(SqliteDatabaseManager.class, "SQLite connection closed cleanly.");
                    }
                } catch (SQLException e) {
                    TrystLog.error(SqliteDatabaseManager.class, "Error closing SQLite connection", e);
                } finally {
                    connection = null;
                }
            }
        }
    }
}
