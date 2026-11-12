package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.event.EventBus;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskInstruction;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.service.NotificationService;
import eu.kalafatic.tryst.domain.service.TaskLifecycleManager;
import eu.kalafatic.tryst.domain.service.TaskOrchestrator;
import eu.kalafatic.tryst.domain.service.TaskQueue;
import eu.kalafatic.tryst.domain.studio.DefaultStudioService;
import eu.kalafatic.tryst.domain.studio.StudioAlternative;
import eu.kalafatic.tryst.domain.studio.StudioPlanStep;
import eu.kalafatic.tryst.domain.studio.StudioService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TrystStudioIntegrationTest {

    private SqliteDatabaseManager databaseManager;
    private SqliteTaskRepository taskRepository;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskInstructionRepository taskInstructionRepository;
    private SqliteCodeReviewRepository codeReviewRepository;
    private SqliteNotificationRepository notificationRepository;
    private SqliteNotificationPreferenceRepository notificationPreferenceRepository;
    private SqliteUserRepository userRepository;
    private NotificationService notificationService;
    private TaskOrchestrator orchestrator;
    private StudioService studioService;

    @BeforeEach
    public void setup(@TempDir Path tempDir) {
        System.setProperty("tryst.dev.mode", "false");
        Path dbPath = tempDir.resolve("studio_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        taskRepository = new SqliteTaskRepository(databaseManager);
        projectRepository = new SqliteProjectRepository(databaseManager);
        taskInstructionRepository = new SqliteTaskInstructionRepository(databaseManager);
        codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);
        notificationRepository = new SqliteNotificationRepository(databaseManager);
        notificationPreferenceRepository = new SqliteNotificationPreferenceRepository(databaseManager);
        userRepository = new SqliteUserRepository(databaseManager);

        EventBus eventBus = new EventBus();
        notificationService = new NotificationService(
                notificationRepository,
                notificationPreferenceRepository,
                eventBus,
                userRepository
        );

        TaskLifecycleManager lifecycleManager = new TaskLifecycleManager(taskRepository, eventBus);
        TaskQueue taskQueue = new TaskQueue(taskRepository, lifecycleManager);

        orchestrator = new TaskOrchestrator(
                taskQueue,
                taskRepository,
                lifecycleManager,
                null,
                projectRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                codeReviewRepository,
                null,
                2
        );
        orchestrator.setNotificationService(notificationService);
        orchestrator.setTaskInstructionRepository(taskInstructionRepository);

        studioService = new DefaultStudioService(
                taskRepository,
                null,
                projectRepository,
                taskInstructionRepository,
                codeReviewRepository,
                orchestrator,
                notificationService,
                eventBus,
                () -> databaseManager.getConnection()
        );
        orchestrator.setStudioService(studioService);
    }

    @AfterEach
    public void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        System.clearProperty("tryst.dev.mode");
    }

    @Test
    public void testOpenExistingTaskInStudioDoesNotCreateDuplicate() {
        Task task = orchestrator.submitTask(
                null, null, "Studio Test Task", "Verify studio loads task state",
                TaskPriority.HIGH, "test-user", null
        );

        assertNotNull(task.getId());
        int taskCountBefore = taskRepository.findAllTasks().size();

        // Load snapshot in Studio
        StudioService.StudioTaskSnapshot snapshot = studioService.getTaskSnapshot(task.getId());
        assertNotNull(snapshot);
        assertNotNull(snapshot.task());
        assertEquals(task.getId(), snapshot.task().getId());
        assertEquals("Studio Test Task", snapshot.task().getTitle());

        // Ensure opening in Studio did NOT create any duplicate tasks
        int taskCountAfter = taskRepository.findAllTasks().size();
        assertEquals(taskCountBefore, taskCountAfter);
    }

    @Test
    public void testLiveInstructionsAndCommentPersistence() {
        Task task = orchestrator.submitTask(
                null, null, "Interactive Instruction Task", "Testing user instruction queuing",
                TaskPriority.NORMAL, "test-user", null
        );

        // Submit live interactive user instruction
        TaskInstruction inst = studioService.submitUserInstruction(task.getId(), "test-user", "Please preserve public API methods");
        assertNotNull(inst.getId());

        List<TaskInstruction> instructions = taskInstructionRepository.findByTaskId(task.getId());
        assertFalse(instructions.isEmpty());
        assertTrue(instructions.stream().anyMatch(i -> i.getContent().contains("preserve public API methods")));

        // Add code comment
        ReviewComment comment = new ReviewComment(
                UUID.randomUUID().toString(),
                "review-test-1",
                task.getId(),
                1,
                "src/Main.java",
                ReviewComment.DiffSide.RIGHT,
                42,
                42,
                "int x = 10;",
                ReviewComment.CommentType.LINE,
                "Check for potential null pointer here",
                "test-user",
                false,
                true,
                Instant.now(),
                Instant.now()
        );
        studioService.addComment(comment);

        List<ReviewComment> comments = studioService.getComments(task.getId());
        assertEquals(1, comments.size());
        assertEquals("src/Main.java", comments.get(0).getFilePath());
        assertEquals(42, comments.get(0).getStartLine());
        assertEquals("Check for potential null pointer here", comments.get(0).getContent());
    }

    @Test
    public void testAlternativesProposalAndSelectionDecision() {
        Task task = orchestrator.submitTask(
                null, null, "Architecture Refactoring", "Selecting optimal design",
                TaskPriority.NORMAL, "test-user", null
        );

        StudioAlternative altA = StudioAlternative.create(
                task.getId(),
                "Option A: Minimal Refactoring",
                "Keep existing interface and adapt internals",
                "Zero breaking changes",
                "Slight overhead",
                List.of("Service.java"),
                "None",
                "Backward compatible"
        );
        StudioAlternative altB = StudioAlternative.create(
                task.getId(),
                "Option B: Full Redesign",
                "Rewrite module",
                "Cleaner abstraction",
                "Breaking API change",
                List.of("Service.java", "Model.java"),
                "Medium risk",
                "Breaks existing callers"
        );

        studioService.proposeAlternative(altA);
        studioService.proposeAlternative(altB);

        List<StudioAlternative> alternatives = studioService.getAlternatives(task.getId());
        assertEquals(2, alternatives.size());

        // User makes decision: Select Option A
        boolean selected = studioService.selectAlternative(altA.id(), task.getId(), "test-user");
        assertTrue(selected);

        List<StudioAlternative> updatedAlts = studioService.getAlternatives(task.getId());
        StudioAlternative selectedAlt = updatedAlts.stream().filter(a -> a.id().equals(altA.id())).findFirst().orElseThrow();
        assertEquals(StudioAlternative.AlternativeStatus.SELECTED, selectedAlt.status());

        StudioAlternative rejectedAlt = updatedAlts.stream().filter(a -> a.id().equals(altB.id())).findFirst().orElseThrow();
        assertEquals(StudioAlternative.AlternativeStatus.REJECTED, rejectedAlt.status());

        // Verify decision was converted to an instruction in task context
        List<TaskInstruction> insts = taskInstructionRepository.findByTaskId(task.getId());
        assertTrue(insts.stream().anyMatch(i -> i.getContent().contains(altA.id())));
    }

    @Test
    public void testDevelopmentPlanUpdates() {
        Task task = orchestrator.submitTask(
                null, null, "Plan Tracking Task", "Verifying plan steps",
                TaskPriority.LOW, "test-user", null
        );

        List<StudioPlanStep> steps = List.of(
                StudioPlanStep.create(task.getId(), 1, "Analyze Project Structure", StudioPlanStep.StepStatus.COMPLETED),
                StudioPlanStep.create(task.getId(), 2, "Implement Modifications", StudioPlanStep.StepStatus.IN_PROGRESS),
                StudioPlanStep.create(task.getId(), 3, "Run Verification Tests", StudioPlanStep.StepStatus.PENDING)
        );

        studioService.updatePlan(task.getId(), steps);

        List<StudioPlanStep> retrieved = studioService.getPlanSteps(task.getId());
        assertEquals(3, retrieved.size());
        assertEquals("Analyze Project Structure", retrieved.get(0).description());
        assertEquals(StudioPlanStep.StepStatus.COMPLETED, retrieved.get(0).status());
        assertEquals(StudioPlanStep.StepStatus.IN_PROGRESS, retrieved.get(1).status());
        assertEquals(StudioPlanStep.StepStatus.PENDING, retrieved.get(2).status());
    }
}
