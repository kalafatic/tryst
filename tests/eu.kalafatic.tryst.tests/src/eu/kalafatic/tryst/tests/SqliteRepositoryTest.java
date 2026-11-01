package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SqliteRepositoryTest {

    private SqliteDatabaseManager dbManager;
    private SqliteProjectRepository projectRepo;
    private SqliteWorkspaceRepository workspaceRepo;
    private SqliteSessionRepository sessionRepo;
    private SqliteTaskRepository taskRepo;
    private SqliteConversationMessageRepository messageRepo;
    private SqliteExecutionResultRepository resultRepo;
    private SqliteAgentRepository agentRepo;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("repo_test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();

        projectRepo = new SqliteProjectRepository(dbManager);
        taskRepo = new SqliteTaskRepository(dbManager);
        workspaceRepo = new SqliteWorkspaceRepository(dbManager, projectRepo, taskRepo);
        sessionRepo = new SqliteSessionRepository(dbManager);
        messageRepo = new SqliteConversationMessageRepository(dbManager);
        resultRepo = new SqliteExecutionResultRepository(dbManager);
        agentRepo = new SqliteAgentRepository(dbManager);
    }

    @AfterEach
    public void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    public void testProjectRepositoryCrud() {
        Project project = new Project("TestProj", Path.of("/test/proj"));
        projectRepo.save(project);

        Optional<Project> found = projectRepo.findById(project.getId());
        assertTrue(found.isPresent());
        assertEquals("TestProj", found.get().getName());
        assertEquals(Path.of("/test/proj"), found.get().getLocation());
        assertFalse(found.get().isActive());

        // Update active flag
        project.setActive(true);
        projectRepo.save(project);

        Optional<Project> active = projectRepo.findActive();
        assertTrue(active.isPresent());
        assertEquals(project.getId(), active.get().getId());

        List<Project> all = projectRepo.findAll();
        assertEquals(1, all.size());

        assertTrue(projectRepo.delete(project.getId()));
        assertTrue(projectRepo.findById(project.getId()).isEmpty());
    }

    @Test
    public void testSessionAndConversationMessageRepository() {
        Project project = new Project("Proj", Path.of("/tmp/p"));
        projectRepo.save(project);

        Session session = new Session(project.getId(), "Session 1");
        sessionRepo.save(session);

        Optional<Session> foundSession = sessionRepo.findById(session.getId());
        assertTrue(foundSession.isPresent());
        assertEquals("Session 1", foundSession.get().getName());
        assertEquals("ACTIVE", foundSession.get().getStatus());

        List<Session> recoverable = sessionRepo.findRecoverableSessions();
        assertEquals(1, recoverable.size());

        ConversationMessage msg1 = new ConversationMessage(session.getId(), "USER", "Build the project");
        ConversationMessage msg2 = new ConversationMessage(session.getId(), "ASSISTANT", "Building now");
        messageRepo.save(msg1);
        messageRepo.save(msg2);

        List<ConversationMessage> messages = messageRepo.findBySessionId(session.getId());
        assertEquals(2, messages.size());
        assertEquals("USER", messages.get(0).getRole());
        assertEquals("ASSISTANT", messages.get(1).getRole());

        List<ConversationMessage> paged = messageRepo.findBySessionIdPaged(session.getId(), 1, 0);
        assertEquals(1, paged.size());
        assertEquals("USER", paged.get(0).getRole());

        // Cascade delete on session
        sessionRepo.delete(session.getId());
        assertTrue(sessionRepo.findById(session.getId()).isEmpty());
        assertTrue(messageRepo.findBySessionId(session.getId()).isEmpty());
    }

    @Test
    public void testTaskAndExecutionResultRepository() {
        Project project = new Project("Proj2", Path.of("/tmp/p2"));
        projectRepo.save(project);

        AgentTask task = new AgentTask("Task 1", "Implement persistence");
        taskRepo.save(task);

        Optional<AgentTask> foundTask = taskRepo.findById(task.getId());
        assertTrue(foundTask.isPresent());
        assertEquals("Task 1", foundTask.get().getTitle());
        assertEquals(ExecutionStatus.IDLE, foundTask.get().getStatus());

        // Start task and update
        task.start();
        taskRepo.save(task);
        assertEquals(ExecutionStatus.RUNNING, taskRepo.findById(task.getId()).get().getStatus());

        task.complete();
        taskRepo.save(task);
        assertEquals(ExecutionStatus.COMPLETED, taskRepo.findById(task.getId()).get().getStatus());

        // Save execution result
        ExecutionResult result = new ExecutionResult(task.getId(), 0, "Build OK", "", 1250);
        resultRepo.save(result);

        Optional<ExecutionResult> foundResult = resultRepo.findById(result.getId());
        assertTrue(foundResult.isPresent());
        assertEquals(0, foundResult.get().getExitCode());
        assertEquals("Build OK", foundResult.get().getOutput());
        assertTrue(foundResult.get().isSuccess());

        Optional<ExecutionResult> latest = resultRepo.findLatestByTaskId(task.getId());
        assertTrue(latest.isPresent());
        assertEquals(result.getId(), latest.get().getId());
    }

    @Test
    public void testAgentRepository() {
        AgentConfiguration agent = new AgentConfiguration("SoftwareEngineer", "qwen2.5-coder:14b", "You are an engineer", "{\"temp\":0.2}");
        agentRepo.save(agent);

        Optional<AgentConfiguration> found = agentRepo.findById(agent.getId());
        assertTrue(found.isPresent());
        assertEquals("SoftwareEngineer", found.get().getName());
        assertEquals("qwen2.5-coder:14b", found.get().getModelName());

        Optional<AgentConfiguration> byName = agentRepo.findByName("SoftwareEngineer");
        assertTrue(byName.isPresent());
        assertEquals(agent.getId(), byName.get().getId());
    }
}
