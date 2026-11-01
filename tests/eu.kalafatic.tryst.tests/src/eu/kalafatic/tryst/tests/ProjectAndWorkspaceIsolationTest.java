package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAndWorkspaceIsolationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("isolation.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService(databaseManager).migrate();
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testUserProjectAuthorizationEnforcement() throws IOException {
        PermissionManager permissionManager = new PermissionManager();
        Path p1Path = tempDir.resolve("user1_proj");
        Files.createDirectories(p1Path);

        Project user1Proj = new Project("p1", "alice", "Alice Project", p1Path, true);

        // 1. Authorized user access granted
        assertDoesNotThrow(() -> permissionManager.validateUserProjectAccess("alice", user1Proj));
        // Admin access granted
        assertDoesNotThrow(() -> permissionManager.validateUserProjectAccess("admin", user1Proj));

        // 2. Unauthorized user rejected
        assertThrows(TrystDomainException.class, () -> permissionManager.validateUserProjectAccess("bob", user1Proj));
        assertThrows(TrystDomainException.class, () -> permissionManager.validateUserProjectAccess(null, user1Proj));
    }

    @Test
    void testWorkspaceRootContainmentAndTraversalProtection() throws IOException {
        PermissionManager permissionManager = new PermissionManager();
        Path wsRoot = tempDir.resolve("workspace_root");
        Files.createDirectories(wsRoot);
        Workspace ws = new Workspace(wsRoot);

        // Safe path inside
        Path validFile = wsRoot.resolve("valid.txt");
        assertDoesNotThrow(() -> permissionManager.validatePathWithinWorkspace(validFile, ws));

        // Traversal path outside
        Path escapeFile = wsRoot.resolve("../outside.txt");
        assertThrows(TrystDomainException.class, () -> permissionManager.validatePathWithinWorkspace(escapeFile, ws));

        // Symlink pointing outside workspace
        Path externalDir = tempDir.resolve("external_target");
        Files.createDirectories(externalDir);
        Path symlinkInsideWs = wsRoot.resolve("symlink_escape");

        try {
            Files.createSymbolicLink(symlinkInsideWs, externalDir);
            Path fileViaSymlink = symlinkInsideWs.resolve("secret.txt");
            assertThrows(TrystDomainException.class, () -> permissionManager.validatePathWithinWorkspace(fileViaSymlink, ws));
        } catch (UnsupportedOperationException ignored) {
            // OS / filesystem does not support symlinks in test container
        }
    }

    @Test
    void testConcurrentWorkspaceLocks() throws InterruptedException {
        PermissionManager permissionManager = new PermissionManager();
        Path wsPath = tempDir.resolve("concurrent_ws");
        ReentrantLock lock1 = permissionManager.getWorkspaceLock(wsPath);
        ReentrantLock lock2 = permissionManager.getWorkspaceLock(wsPath);

        assertSame(lock1, lock2, "Locks for the same workspace path must be identical");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ReentrantLock l = permissionManager.getWorkspaceLock(wsPath);
                    l.lock();
                    try {
                        int current = counter.get();
                        Thread.sleep(10);
                        counter.set(current + 1);
                    } finally {
                        l.unlock();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount, counter.get());
    }

    @Test
    void testScopedWorkspaceResolutionPerProject() throws IOException {
        SqliteProjectRepository projRepo = new SqliteProjectRepository(databaseManager);
        SqliteTaskRepository taskRepo = new SqliteTaskRepository(databaseManager);
        SqliteWorkspaceRepository wsRepo = new SqliteWorkspaceRepository(databaseManager, projRepo, taskRepo);

        Path root1 = tempDir.resolve("proj1_root");
        Path root2 = tempDir.resolve("proj2_root");
        Files.createDirectories(root1);
        Files.createDirectories(root2);

        WorkspaceService wsService = new WorkspaceService(tempDir.resolve("default_ws"), wsRepo, projRepo, taskRepo);
        Project p1 = wsService.addProject("user1", "Project1", "Desc1", root1);
        Project p2 = wsService.addProject("user2", "Project2", "Desc2", root2);

        Workspace ws1 = wsService.getWorkspaceForProject(p1.getId());
        Workspace ws2 = wsService.getWorkspaceForProject(p2.getId());

        assertNotNull(ws1);
        assertNotNull(ws2);
        assertNotEquals(ws1.getRootPath(), ws2.getRootPath());
        assertEquals(root1.toAbsolutePath().normalize(), ws1.getRootPath().toAbsolutePath().normalize());
        assertEquals(root2.toAbsolutePath().normalize(), ws2.getRootPath().toAbsolutePath().normalize());
    }
}
