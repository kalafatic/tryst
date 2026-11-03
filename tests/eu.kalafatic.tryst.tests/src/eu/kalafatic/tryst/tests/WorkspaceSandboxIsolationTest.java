package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
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

class WorkspaceSandboxIsolationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private WorkspaceService workspaceService;
    private PermissionManager permissionManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("isolation_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        SqliteProjectRepository projRepo = new SqliteProjectRepository(databaseManager);
        SqliteTaskRepository taskRepo = new SqliteTaskRepository(databaseManager);
        SqliteWorkspaceRepository wsRepo = new SqliteWorkspaceRepository(databaseManager, projRepo, taskRepo);
        permissionManager = new PermissionManager();

        workspaceService = new WorkspaceService(tempDir.resolve("base_ws"), wsRepo, projRepo, taskRepo, permissionManager);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testEveryTaskReceivesIsolatedWorkspace() {
        Workspace ws1 = workspaceService.allocateTaskWorkspace("user-1", "task-1", "proj-a", "repo.git", "branch-1");
        Workspace ws2 = workspaceService.allocateTaskWorkspace("user-2", "task-2", "proj-b", "repo.git", "branch-2");

        assertNotNull(ws1);
        assertNotNull(ws2);
        assertNotEquals(ws1.getId(), ws2.getId());
        assertNotEquals(ws1.getRootPath(), ws2.getRootPath());
        assertEquals("user-1", ws1.getOwnerId());
        assertEquals("user-2", ws2.getOwnerId());
        assertEquals("task-1", ws1.getTaskId());
        assertEquals("task-2", ws2.getTaskId());

        // Both initialize cleanly in separate directories
        Workspace initWs1 = workspaceService.initializeWorkspace(ws1.getId());
        Workspace initWs2 = workspaceService.initializeWorkspace(ws2.getId());

        assertEquals(WorkspaceStatus.READY, initWs1.getStatus());
        assertEquals(WorkspaceStatus.READY, initWs2.getStatus());
        assertTrue(Files.exists(initWs1.getRootPath()));
        assertTrue(Files.exists(initWs2.getRootPath()));
    }

    @Test
    void testCleanupRefusesToDeleteActiveOrRecoverableWork() throws IOException {
        Workspace ws = workspaceService.allocateTaskWorkspace("user-1", "task-active", "p1", "repo.git", "main");
        workspaceService.initializeWorkspace(ws.getId());
        ws.activate();

        // Write a test file in the workspace
        Path testFile = ws.getRootPath().resolve("important_work.java");
        Files.writeString(testFile, "public class Important {}");

        // Attempt cleanup while ACTIVE -> must fail and keep files intact!
        assertThrows(TrystDomainException.class, () -> workspaceService.cleanupWorkspace(ws.getId(), true));
        assertTrue(Files.exists(testFile), "File must not be deleted when active");

        // Transition to WAITING_FOR_REVIEW -> must also refuse cleanup!
        ws.markWaitingForReview();
        assertThrows(TrystDomainException.class, () -> workspaceService.cleanupWorkspace(ws.getId(), true));
        assertTrue(Files.exists(testFile), "File must not be deleted when WAITING_FOR_REVIEW");

        // Transition to REWORKING -> must refuse cleanup!
        ws.rework();
        assertThrows(TrystDomainException.class, () -> workspaceService.cleanupWorkspace(ws.getId(), true));
        assertTrue(Files.exists(testFile));

        // Complete the task workspace -> cleanup is now permitted
        ws.activate();
        ws.complete();
        boolean cleaned = workspaceService.cleanupWorkspace(ws.getId(), true);
        assertTrue(cleaned);
        assertFalse(Files.exists(testFile), "File safely removed after task completed");
    }

    @Test
    void testSymlinkAndTraversalProtection() throws IOException {
        Workspace ws = workspaceService.allocateTaskWorkspace("user-x", "task-x", "p1", null, "main");
        workspaceService.initializeWorkspace(ws.getId());

        Path safeFile = ws.getRootPath().resolve("code.java");
        Files.writeString(safeFile, "// code");
        assertDoesNotThrow(() -> permissionManager.validatePathWithinWorkspace(safeFile, ws));

        // Path Traversal
        Path escapeTraversal = ws.getRootPath().resolve("../../../etc/shadow");
        assertThrows(TrystDomainException.class, () -> permissionManager.validatePathWithinWorkspace(escapeTraversal, ws));

        // Symlink pointing outside
        Path outsideTarget = tempDir.resolve("external_secrets");
        Files.createDirectories(outsideTarget);
        Path link = ws.getRootPath().resolve("link_to_external");

        try {
            Files.createSymbolicLink(link, outsideTarget);
            Path escapedViaLink = link.resolve("passwords.txt");
            assertThrows(TrystDomainException.class, () -> permissionManager.validatePathWithinWorkspace(escapedViaLink, ws));
        } catch (UnsupportedOperationException ignored) {}
    }

    @Test
    void testConcurrentOperationsCoordination() throws InterruptedException {
        Workspace ws = workspaceService.allocateTaskWorkspace("user-c", "task-c", "p1", null, "main");
        workspaceService.initializeWorkspace(ws.getId());

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    ReentrantLock lock = permissionManager.getWorkspaceLock(ws.getRootPath());
                    lock.lock();
                    try {
                        int c = counter.get();
                        Thread.sleep(10);
                        counter.set(c + 1);
                    } finally {
                        lock.unlock();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threads, counter.get());
    }
}
