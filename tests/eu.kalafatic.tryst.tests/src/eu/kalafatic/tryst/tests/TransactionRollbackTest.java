package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionRollbackTest {

    private SqliteDatabaseManager dbManager;
    private SqliteProjectRepository projectRepo;
    private SqliteWorkspaceRepository workspaceRepo;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("tx_test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();

        projectRepo = new SqliteProjectRepository(dbManager);
        SqliteTaskRepository taskRepo = new SqliteTaskRepository(dbManager);
        workspaceRepo = new SqliteWorkspaceRepository(dbManager, projectRepo, taskRepo);
    }

    @AfterEach
    public void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    public void testTransactionCommitPersistsBothEntities() {
        Project project = new Project("P1", Path.of("/tmp/p1"));
        Workspace workspace = new Workspace(Path.of("/tmp/ws"));

        dbManager.runInTransaction(() -> {
            projectRepo.save(project);
            workspace.addProject(project);
            workspaceRepo.save(workspace);
        });

        assertTrue(projectRepo.findById(project.getId()).isPresent());
        assertTrue(workspaceRepo.findById(workspace.getId()).isPresent());
    }

    @Test
    public void testTransactionRollbackOnExceptionLeavesNoPartialState() {
        Project project = new Project("P2", Path.of("/tmp/p2"));

        assertThrows(RuntimeException.class, () -> {
            dbManager.runInTransaction(() -> {
                projectRepo.save(project);
                // simulate failure
                throw new RuntimeException("Simulated unexpected failure");
            });
        });

        // The project must have been rolled back
        assertTrue(projectRepo.findById(project.getId()).isEmpty());
    }
}
