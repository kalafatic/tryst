package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.domain.service.WorkspaceRecoveryService;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePersistenceAndRecoveryTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteWorkspaceRepository workspaceRepository;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("ws_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testWorkspacePersistenceAndQueries() throws IOException {
        Path root = tempDir.resolve("ws_persistent");
        Files.createDirectories(root);

        Workspace ws = new Workspace(
                "ws-persist-1",
                root,
                "alice",
                "task-999",
                "https://git.example.com/repo.git",
                "feature/payments",
                WorkspaceStatus.READY,
                Instant.now(),
                Instant.now(),
                Map.of("cluster", "local-1", "env", "test")
        );

        workspaceRepository.save(ws);

        // Verify findById
        Optional<Workspace> opt = workspaceRepository.findById("ws-persist-1");
        assertTrue(opt.isPresent());
        Workspace loaded = opt.get();
        assertEquals("ws-persist-1", loaded.getId());
        assertEquals("alice", loaded.getOwnerId());
        assertEquals("task-999", loaded.getTaskId());
        assertEquals("feature/payments", loaded.getBranch());
        assertEquals("https://git.example.com/repo.git", loaded.getRepositoryUrl());
        assertEquals(WorkspaceStatus.READY, loaded.getStatus());
        assertEquals("local-1", loaded.getMetadata().get("cluster"));
        assertEquals("test", loaded.getMetadata().get("env"));

        // Verify findByTaskId
        Optional<Workspace> byTask = workspaceRepository.findByTaskId("task-999");
        assertTrue(byTask.isPresent());
        assertEquals("ws-persist-1", byTask.get().getId());

        // Verify findByOwnerId
        List<Workspace> byOwner = workspaceRepository.findByOwnerId("alice");
        assertEquals(1, byOwner.size());
        assertEquals("ws-persist-1", byOwner.get(0).getId());

        // Verify findByStatus
        List<Workspace> readyList = workspaceRepository.findByStatus(WorkspaceStatus.READY);
        assertFalse(readyList.isEmpty());
        assertTrue(readyList.stream().anyMatch(w -> w.getId().equals("ws-persist-1")));
    }

    @Test
    void testCrashRestartRecoveryPreservesWork() throws IOException {
        Path r1 = tempDir.resolve("ws_active");
        Path r2 = tempDir.resolve("ws_review");
        Path r3 = tempDir.resolve("ws_init");
        Files.createDirectories(r1);
        Files.createDirectories(r2);
        Files.createDirectories(r3);

        Workspace activeWs = new Workspace("ws-active", r1, "alice", "t-1", null, "main",
                WorkspaceStatus.ACTIVE, Instant.now(), Instant.now(), Map.of());
        Workspace reviewWs = new Workspace("ws-review", r2, "alice", "t-2", null, "main",
                WorkspaceStatus.WAITING_FOR_REVIEW, Instant.now(), Instant.now(), Map.of());
        Workspace initWs = new Workspace("ws-init", r3, "bob", "t-3", null, "main",
                WorkspaceStatus.INITIALIZING, Instant.now(), Instant.now(), Map.of());

        workspaceRepository.save(activeWs);
        workspaceRepository.save(reviewWs);
        workspaceRepository.save(initWs);

        // Run crash restart recovery service
        WorkspaceRecoveryService recoveryService = new WorkspaceRecoveryService(workspaceRepository);
        int recovered = recoveryService.recoverWorkspaces();
        assertEquals(2, recovered, "Active and initializing workspaces must be recovered");

        // Verify active transitioned to FAILED
        Workspace loadedActive = workspaceRepository.findById("ws-active").orElseThrow();
        assertEquals(WorkspaceStatus.FAILED, loadedActive.getStatus());
        assertNotNull(loadedActive.getMetadata().get("recovery_reason"));

        // Verify initializing transitioned to FAILED
        Workspace loadedInit = workspaceRepository.findById("ws-init").orElseThrow();
        assertEquals(WorkspaceStatus.FAILED, loadedInit.getStatus());

        // Verify WAITING_FOR_REVIEW is preserved!
        Workspace loadedReview = workspaceRepository.findById("ws-review").orElseThrow();
        assertEquals(WorkspaceStatus.WAITING_FOR_REVIEW, loadedReview.getStatus(),
                "WAITING_FOR_REVIEW must be preserved across application restarts");
    }
}
