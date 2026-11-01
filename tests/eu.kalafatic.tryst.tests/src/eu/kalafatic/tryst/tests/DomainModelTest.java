package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Agent;
import eu.kalafatic.tryst.domain.model.AgentAvailability;
import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.AgentType;
import eu.kalafatic.tryst.domain.model.ConsoleMessage;
import eu.kalafatic.tryst.domain.model.ConversationMessage;
import eu.kalafatic.tryst.domain.model.ExecutionResult;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.MessageRole;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.ProjectStatus;
import eu.kalafatic.tryst.domain.model.Session;
import eu.kalafatic.tryst.domain.model.SessionStatus;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DomainModelTest {

    // =========================================================================
    // 1. Project Tests
    // =========================================================================

    @Test
    void testProjectCreationAndValidation() {
        Path loc = Path.of("/tmp/test-project");
        Project project = new Project("Tryst Core", "Main project description", loc);

        assertNotNull(project.getId());
        assertEquals("Tryst Core", project.getName());
        assertEquals("Main project description", project.getDescription());
        assertEquals(loc, project.getLocation());
        assertEquals(ProjectStatus.ACTIVE, project.getStatus());
        assertFalse(project.isActive());
        assertNotNull(project.getCreatedAt());
        assertNotNull(project.getUpdatedAt());

        // Invariant: name cannot be null or blank
        assertThrows(TrystDomainException.class, () -> new Project(null, loc));
        assertThrows(TrystDomainException.class, () -> new Project("   ", loc));
        assertThrows(NullPointerException.class, () -> new Project("Valid", null));
    }

    @Test
    void testProjectLifecycleAndTransitions() {
        Project p = new Project("Tryst", Path.of("/tmp/p"));
        assertEquals(ProjectStatus.ACTIVE, p.getStatus());

        p.setActive(true);
        assertTrue(p.isActive());

        p.archive();
        assertEquals(ProjectStatus.ARCHIVED, p.getStatus());
        assertFalse(p.isActive());

        // Archived project cannot be set as active
        assertThrows(TrystDomainException.class, () -> p.setActive(true));

        p.activate();
        assertEquals(ProjectStatus.ACTIVE, p.getStatus());
        p.setActive(true);
        assertTrue(p.isActive());

        p.close();
        assertEquals(ProjectStatus.CLOSED, p.getStatus());
        assertFalse(p.isActive());

        // Terminal state: closed project cannot be reactivated or archived
        assertThrows(TrystDomainException.class, p::activate);
        assertThrows(TrystDomainException.class, p::archive);
    }

    @Test
    void testProjectEqualityAndHashCode() {
        String id = UUID.randomUUID().toString();
        Project p1 = new Project(id, "A", Path.of("/tmp/a"), true);
        Project p2 = new Project(id, "B", Path.of("/tmp/b"), false);
        Project p3 = new Project(UUID.randomUUID().toString(), "A", Path.of("/tmp/a"), true);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, p3);
    }

    // =========================================================================
    // 2. Workspace Tests
    // =========================================================================

    @Test
    void testWorkspaceLifecycleAndContainment() {
        Path root = Path.of("/tmp/tryst-ws");
        Workspace ws = new Workspace(root);
        assertEquals(root, ws.getRootPath());
        assertEquals(WorkspaceStatus.ACTIVE, ws.getStatus());
        assertTrue(ws.getProjects().isEmpty());
        assertTrue(ws.getTasks().isEmpty());

        Project p1 = new Project("Proj1", Path.of("/tmp/proj1"));
        Project p2 = new Project("Proj2", Path.of("/tmp/proj2"));
        ws.addProject(p1);
        ws.addProject(p2);
        assertEquals(2, ws.getProjects().size());

        ws.setActiveProject(p1.getId());
        assertTrue(ws.getActiveProject().isPresent());
        assertEquals(p1.getId(), ws.getActiveProject().get().getId());
        assertTrue(p1.isActive());
        assertFalse(p2.isActive());

        ws.removeProject(p1.getId());
        assertEquals(1, ws.getProjects().size());
        assertFalse(ws.getActiveProject().isPresent());

        // Workspace status transitions
        ws.lock();
        assertEquals(WorkspaceStatus.LOCKED, ws.getStatus());

        // Cannot add/remove projects or tasks when locked
        Project p3 = new Project("Proj3", Path.of("/tmp/proj3"));
        assertThrows(TrystDomainException.class, () -> ws.addProject(p3));
        assertThrows(TrystDomainException.class, () -> ws.addTask(new AgentTask("Task 1", "Desc")));

        ws.activate();
        assertEquals(WorkspaceStatus.ACTIVE, ws.getStatus());
        ws.addProject(p3);
        assertEquals(2, ws.getProjects().size());

        ws.close();
        assertEquals(WorkspaceStatus.CLOSED, ws.getStatus());
        assertThrows(TrystDomainException.class, ws::activate);
        assertThrows(TrystDomainException.class, ws::lock);
    }

    // =========================================================================
    // 3. Session Tests
    // =========================================================================

    @Test
    void testSessionCreationAndTransitions() {
        String projId = UUID.randomUUID().toString();
        String wsId = UUID.randomUUID().toString();
        Session session = new Session(projId, wsId, "Interactive Session 1");

        assertNotNull(session.getId());
        assertEquals(projId, session.getProjectId());
        assertEquals(wsId, session.getWorkspaceId());
        assertEquals("Interactive Session 1", session.getName());
        assertEquals(SessionStatus.ACTIVE, session.getSessionStatus());
        assertEquals("ACTIVE", session.getStatus());

        // Valid transitions: ACTIVE -> PAUSED -> ACTIVE -> COMPLETED -> CLOSED -> TERMINATED
        session.pause();
        assertEquals(SessionStatus.PAUSED, session.getSessionStatus());

        session.resume();
        assertEquals(SessionStatus.ACTIVE, session.getSessionStatus());

        session.complete();
        assertEquals(SessionStatus.COMPLETED, session.getSessionStatus());

        session.close();
        assertEquals(SessionStatus.CLOSED, session.getSessionStatus());

        session.terminate();
        assertEquals(SessionStatus.TERMINATED, session.getSessionStatus());

        // Invalid transitions from terminal state
        assertThrows(TrystDomainException.class, session::resume);
        assertThrows(TrystDomainException.class, session::pause);
        assertThrows(TrystDomainException.class, session::complete);
    }

    @Test
    void testSessionValidation() {
        assertThrows(TrystDomainException.class, () -> new Session(null, "Session 1"));
        assertThrows(TrystDomainException.class, () -> new Session("  ", "Session 1"));
        assertThrows(TrystDomainException.class, () -> new Session("p1", "   "));
        assertThrows(TrystDomainException.class, () -> new Session("p1", null));
    }

    // =========================================================================
    // 4. AgentTask Lifecycle Tests
    // =========================================================================

    @Test
    void testAgentTaskFullLifecycleTransitions() {
        AgentTask task = new AgentTask("Feature A", "Implement feature");
        assertEquals("Feature A", task.getTitle());
        assertEquals("Implement feature", task.getDescription());
        assertEquals(TaskStatus.CREATED, task.getTaskStatus());
        assertEquals(ExecutionStatus.IDLE, task.getStatus());
        assertEquals(TaskPriority.NORMAL, task.getPriority());
        assertNull(task.getCompletedAt());
        assertNull(task.getAgentId());
        assertNull(task.getExecutionResultId());

        task.setPriority(TaskPriority.HIGH);
        assertEquals(TaskPriority.HIGH, task.getPriority());

        task.assignAgent("agent-007");
        assertEquals("agent-007", task.getAgentId());

        // CREATED -> PLANNED
        task.plan();
        assertEquals(TaskStatus.PLANNED, task.getTaskStatus());

        // PLANNED -> READY
        task.markReady();
        assertEquals(TaskStatus.READY, task.getTaskStatus());

        // READY -> RUNNING
        task.start();
        assertEquals(TaskStatus.RUNNING, task.getTaskStatus());
        assertEquals(ExecutionStatus.RUNNING, task.getStatus());

        // RUNNING -> WAITING_FOR_APPROVAL
        task.requestApproval();
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, task.getTaskStatus());

        // WAITING_FOR_APPROVAL -> RUNNING
        task.approve();
        assertEquals(TaskStatus.RUNNING, task.getTaskStatus());

        // RUNNING -> COMPLETED
        task.setExecutionResultId("result-123");
        task.complete();
        assertEquals(TaskStatus.COMPLETED, task.getTaskStatus());
        assertEquals(ExecutionStatus.COMPLETED, task.getStatus());
        assertNotNull(task.getCompletedAt());
        assertEquals("result-123", task.getExecutionResultId());
        assertTrue(task.getTaskStatus().isTerminal());

        // In terminal state: cannot modify priority, re-assign, or transition
        assertThrows(TrystDomainException.class, () -> task.setPriority(TaskPriority.URGENT));
        assertThrows(TrystDomainException.class, () -> task.assignAgent("other-agent"));
        assertThrows(TrystDomainException.class, task::start);
        assertThrows(TrystDomainException.class, task::cancel);
    }

    @Test
    void testAgentTaskFailureAndCancellation() {
        AgentTask task1 = new AgentTask("Task F", "Failure flow");
        task1.start();
        task1.fail();
        assertEquals(TaskStatus.FAILED, task1.getTaskStatus());
        assertEquals(ExecutionStatus.FAILED, task1.getStatus());
        assertNotNull(task1.getCompletedAt());
        assertTrue(task1.getTaskStatus().isTerminal());
        assertThrows(TrystDomainException.class, task1::complete);
        assertThrows(TrystDomainException.class, task1::cancel);

        AgentTask task2 = new AgentTask("Task C", "Cancellation flow");
        task2.cancel();
        assertEquals(TaskStatus.CANCELLED, task2.getTaskStatus());
        assertEquals(ExecutionStatus.CANCELLED, task2.getStatus());
        assertNotNull(task2.getCompletedAt());
        assertTrue(task2.getTaskStatus().isTerminal());
        assertThrows(TrystDomainException.class, task2::start);
    }

    @Test
    void testAgentTaskValidation() {
        assertThrows(TrystDomainException.class, () -> new AgentTask(null, "desc"));
        assertThrows(TrystDomainException.class, () -> new AgentTask("   ", "desc"));
    }

    // =========================================================================
    // 5. Agent Model Tests
    // =========================================================================

    @Test
    void testAgentIdentityAndCapabilities() {
        Agent agent = new Agent("CodeAssistant", "Autonomous coding agent", AgentType.CODER);
        assertNotNull(agent.getId());
        assertEquals("CodeAssistant", agent.getName());
        assertEquals("Autonomous coding agent", agent.getDescription());
        assertEquals(AgentType.CODER, agent.getAgentType());
        assertEquals(AgentAvailability.AVAILABLE, agent.getAvailability());
        assertTrue(agent.getCapabilities().isEmpty());

        agent.addCapability("java21");
        agent.addCapability("eclipse-rcp");
        assertTrue(agent.hasCapability("java21"));
        assertTrue(agent.hasCapability("eclipse-rcp"));
        assertFalse(agent.hasCapability("python"));

        agent.markBusy();
        assertEquals(AgentAvailability.BUSY, agent.getAvailability());

        agent.markAvailable();
        assertEquals(AgentAvailability.AVAILABLE, agent.getAvailability());

        agent.markOffline();
        assertEquals(AgentAvailability.OFFLINE, agent.getAvailability());

        // Offline agent cannot transition directly to busy
        assertThrows(TrystDomainException.class, agent::markBusy);

        // Validation
        assertThrows(TrystDomainException.class, () -> new Agent("", "desc", AgentType.GENERAL));
        assertThrows(TrystDomainException.class, () -> new Agent(null, "desc", AgentType.GENERAL));
    }

    // =========================================================================
    // 6. ConversationMessage Tests
    // =========================================================================

    @Test
    void testConversationMessageRolesAndMetadata() {
        String sessId = UUID.randomUUID().toString();
        ConversationMessage msg = new ConversationMessage(
                UUID.randomUUID().toString(),
                sessId,
                MessageRole.USER,
                "Can you generate the domain model?",
                Instant.now(),
                Map.of("model", "qwen2.5-coder")
        );

        assertEquals(sessId, msg.getSessionId());
        assertEquals(MessageRole.USER, msg.getMessageRole());
        assertEquals("USER", msg.getRole());
        assertEquals("Can you generate the domain model?", msg.getContent());
        assertEquals("qwen2.5-coder", msg.getMetadata().get("model"));

        // Validation
        assertThrows(TrystDomainException.class, () -> new ConversationMessage(null, MessageRole.USER, "text"));
        assertThrows(TrystDomainException.class, () -> new ConversationMessage(sessId, (MessageRole) null, "text"));
        assertThrows(TrystDomainException.class, () -> new ConversationMessage(sessId, MessageRole.USER, null));
        assertThrows(TrystDomainException.class, () -> new ConversationMessage(sessId, "INVALID_ROLE", "text"));
    }

    // =========================================================================
    // 7. ExecutionResult Tests
    // =========================================================================

    @Test
    void testExecutionResultCreation() {
        String taskId = UUID.randomUUID().toString();
        ExecutionResult result = new ExecutionResult(
                UUID.randomUUID().toString(),
                taskId,
                0,
                "Compilation succeeded",
                "BUILD SUCCESS",
                "",
                "Duration: 1.2s",
                1200,
                Instant.now()
        );

        assertEquals(taskId, result.getTaskId());
        assertEquals(0, result.getExitCode());
        assertTrue(result.isSuccess());
        assertEquals("Compilation succeeded", result.getSummary());
        assertEquals("BUILD SUCCESS", result.getOutput());
        assertEquals("", result.getErrorOutput());
        assertEquals("Duration: 1.2s", result.getDiagnosticInfo());
        assertEquals(1200, result.getDurationMs());

        // Failure execution result
        ExecutionResult failResult = new ExecutionResult(
                UUID.randomUUID().toString(),
                taskId,
                1,
                "Build failure",
                "",
                "ERROR: test failed",
                "Exit code 1",
                450,
                Instant.now()
        );
        assertFalse(failResult.isSuccess());
        assertEquals(1, failResult.getExitCode());

        // Validation
        assertThrows(TrystDomainException.class, () -> new ExecutionResult(null, 0, "", "", 0));
        assertThrows(TrystDomainException.class, () -> new ExecutionResult("   ", 0, "", "", 0));
    }

    // =========================================================================
    // 8. ConsoleMessage Tests
    // =========================================================================

    @Test
    void testConsoleMessageCreation() {
        ConsoleMessage info = ConsoleMessage.info("Test", "Info message");
        assertEquals("INFO", info.level());
        assertEquals("Test", info.source());
        assertEquals("Info message", info.message());
        assertNotNull(info.timestamp());

        ConsoleMessage warn = ConsoleMessage.warn("Test", "Warn message");
        assertEquals("WARN", warn.level());

        ConsoleMessage error = ConsoleMessage.error("Test", "Error message");
        assertEquals("ERROR", error.level());
    }
}
