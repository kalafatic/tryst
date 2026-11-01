package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.service.WorkspaceService;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ApplicationRestartRecoveryTest {

    @Test
    public void testStateRestorationAfterSimulatedRestart(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("restart_test.db");
        Path wsPath = tempDir.resolve("my-workspace");

        String projectId;
        String taskId;

        // Run session 1
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            new SchemaMigrationService(dbManager).migrate();

            SqliteProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
            SqliteTaskRepository taskRepo = new SqliteTaskRepository(dbManager);
            SqliteWorkspaceRepository workspaceRepo = new SqliteWorkspaceRepository(dbManager, projectRepo, taskRepo);

            WorkspaceService service = new WorkspaceService(wsPath, workspaceRepo, projectRepo, taskRepo);
            Project p = service.addProject("PersistedProject", wsPath.resolve("PersistedProject"));
            service.selectProject(p.getId());
            AgentTask t = service.createTask("First Task", "Persist tasks");

            projectId = p.getId();
            taskId = t.getId();
            assertNotNull(projectId);
            assertNotNull(taskId);
        }

        // Run session 2 (Simulated application restart)
        try (SqliteDatabaseManager dbManager = new SqliteDatabaseManager(dbPath)) {
            dbManager.initialize();
            new SchemaMigrationService(dbManager).migrate();

            SqliteProjectRepository projectRepo = new SqliteProjectRepository(dbManager);
            SqliteTaskRepository taskRepo = new SqliteTaskRepository(dbManager);
            SqliteWorkspaceRepository workspaceRepo = new SqliteWorkspaceRepository(dbManager, projectRepo, taskRepo);

            WorkspaceService restoredService = new WorkspaceService(wsPath, workspaceRepo, projectRepo, taskRepo);
            Workspace restoredWs = restoredService.getCurrentWorkspace();

            assertNotNull(restoredWs);
            assertEquals(1, restoredWs.getProjects().size());
            assertEquals("PersistedProject", restoredWs.getProjects().get(0).getName());
            assertTrue(restoredWs.getActiveProject().isPresent());
            assertEquals(projectId, restoredWs.getActiveProject().get().getId());

            assertEquals(1, restoredWs.getTasks().size());
            assertEquals("First Task", restoredWs.getTasks().get(0).getTitle());
            assertEquals(ExecutionStatus.IDLE, restoredWs.getTasks().get(0).getStatus());
        }
    }
}
