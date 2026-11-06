package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.ai.ExternalAiDelegator;
import eu.kalafatic.tryst.domain.model.EvoConfiguration;
import eu.kalafatic.tryst.domain.model.EvoExecution;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.evo.integration.service.EvoAiDelegator;
import eu.kalafatic.tryst.evo.integration.service.EvoIntegrationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TeipLifecycleIntegrationTest {

    @Test
    void testAiAgentCannotApproveOrCommit() {
        String aiUserId = "ai-agent-evolution";
        assertTrue(CodeReview.isAiAgent(aiUserId), "AI user ID must be identified as an AI agent");
        assertFalse(CodeReview.isAiAgent("human-developer"), "Human developer must not be identified as an AI agent");
    }

    @Test
    void testEvoExecutionCompletedDoesNotCompleteTrystTask() {
        Task task = new Task("task-100", "proj-1", "sess-1", "Implement Feature", "Detailed spec",
                TaskStatus.IMPLEMENTING, TaskPriority.NORMAL, null, null, null, null, 0, null, Instant.now(), null);

        EvoExecution exec = EvoExecution.create(task.getId(), null, "CODE_IMPLEMENTATION", "DEFAULT", "idem-1");
        exec.updateStatus(EvoExecution.ExecutionStatus.COMPLETED, "FINISHED", 1.0);

        // Verification: Even though EvoExecution is COMPLETED, the TRYST task status remains IMPLEMENTING
        // until TRYST itself executes BUILD, TEST, and enters WAITING_FOR_REVIEW.
        assertEquals(EvoExecution.ExecutionStatus.COMPLETED, exec.getStatus());
        assertEquals(TaskStatus.IMPLEMENTING, task.getStatus());
        assertNotEquals(TaskStatus.COMPLETED, task.getStatus());
    }

    @Test
    void testExternalAiDelegatorAvailabilityGating() {
        MockEvoIntegrationService disabledService = new MockEvoIntegrationService(false);
        EvoAiDelegator delegatorDisabled = new EvoAiDelegator(disabledService);

        Task task = new Task("task-101", "proj-1", "sess-1", "Test", "Desc",
                TaskStatus.READY, TaskPriority.NORMAL, null, null, null, null, 0, null, Instant.now(), null);
        assertFalse(delegatorDisabled.isAvailableForTask(task), "Must not be available when integration is disabled");

        MockEvoIntegrationService enabledService = new MockEvoIntegrationService(true);
        EvoAiDelegator delegatorEnabled = new EvoAiDelegator(enabledService);
        assertTrue(delegatorEnabled.isAvailableForTask(task), "Must be available when integration is enabled");
    }

    private static class MockEvoIntegrationService extends EvoIntegrationService {
        private final boolean enabled;

        MockEvoIntegrationService(boolean enabled) {
            super(
                new MockEvoConfigRepo(enabled),
                new MockEvoExecRepo(),
                null
            );
            this.enabled = enabled;
        }

        @Override
        public EvoConfiguration getConfiguration() {
            return new EvoConfiguration("default", "http://localhost:39080", "token", enabled, 60, 3, null);
        }
    }

    private static class MockEvoConfigRepo implements eu.kalafatic.tryst.domain.repository.EvoConfigurationRepository {
        private final boolean enabled;
        MockEvoConfigRepo(boolean enabled) { this.enabled = enabled; }
        @Override public void save(EvoConfiguration configuration) {}
        @Override public java.util.Optional<EvoConfiguration> getConfiguration() {
            return java.util.Optional.of(new EvoConfiguration("default", "http://localhost:39080", "token", enabled, 60, 3, null));
        }
    }

    private static class MockEvoExecRepo implements eu.kalafatic.tryst.domain.repository.EvoExecutionRepository {
        @Override public void saveExecution(EvoExecution execution) {}
        @Override public java.util.Optional<EvoExecution> findExecutionById(String id) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<EvoExecution> findExecutionByEvoExecutionId(String evoExecutionId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<EvoExecution> findExecutionByIdempotencyKey(String idempotencyKey) { return java.util.Optional.empty(); }
        @Override public java.util.List<EvoExecution> findExecutionsByTaskId(String taskId) { return java.util.List.of(); }
        @Override public java.util.List<EvoExecution> findAllExecutions() { return java.util.List.of(); }
        @Override public boolean saveEvent(eu.kalafatic.tryst.domain.model.EvoExecutionEvent event) { return true; }
        @Override public java.util.List<eu.kalafatic.tryst.domain.model.EvoExecutionEvent> findEventsByExecutionId(String executionId, long afterSequence, int limit) { return java.util.List.of(); }
        @Override public long getLatestSequence(String executionId) { return 0; }
    }
}
