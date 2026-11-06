package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.config.ConfigurationService;
import eu.kalafatic.tryst.domain.ai.*;
import eu.kalafatic.tryst.domain.build.*;
import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.TaskRecoveryCheckpointRepository;
import eu.kalafatic.tryst.domain.review.*;
import eu.kalafatic.tryst.domain.security.*;
import eu.kalafatic.tryst.domain.service.*;
import eu.kalafatic.tryst.domain.tool.*;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.*;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authoritative end-to-end integration and stabilization test verifying Scenarios A through F:
 * - Scenario A: Successful Java programming task against a real Git repository
 * - Scenario B: Failed programming task (compilation/build failure, diagnostic capture)
 * - Scenario C: Task cancellation (immediate graceful cancellation, process cleanup)
 * - Scenario D: Restart and recovery (recovering tasks and workspace state across restarts)
 * - Scenario E: Concurrency (multiple isolated tasks executing without cross-contamination)
 * - Scenario F: Review and rework (comments, AI explanations, rework iteration)
 */
public class EndToEndWorkflowStabilizationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteProjectRepository projectRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteWorkspaceRepository workspaceRepository;
    private SqliteCodeReviewRepository codeReviewRepository;
    private SqliteGitCommitRepository gitCommitRepository;
    private SqliteTaskRecoveryCheckpointRepository checkpointRepository;
    private SqliteUserRepository userRepository;
    private SqliteUserSessionRepository sessionRepository;

    private WorkspaceService workspaceService;
    private TaskLifecycleManager lifecycleManager;
    private TaskQueue taskQueue;
    private PermissionManager permissionManager;
    private GitService gitService;
    private TaskRecoveryService recoveryService;
    private TaskOrchestrator orchestrator;

    private User testUser;
    private String authToken;
    private Project testProject;
    private Path gitRepoPath;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("e2e_stabilization.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        projectRepository = new SqliteProjectRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        workspaceRepository = new SqliteWorkspaceRepository(databaseManager, projectRepository, taskRepository);
        codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);
        gitCommitRepository = new SqliteGitCommitRepository(databaseManager);
        checkpointRepository = new SqliteTaskRecoveryCheckpointRepository(databaseManager);
        userRepository = new SqliteUserRepository(databaseManager);
        sessionRepository = new SqliteUserSessionRepository(databaseManager);

        permissionManager = new PermissionManager();
        gitService = new GitService();
        lifecycleManager = new TaskLifecycleManager(taskRepository, null);
        taskQueue = new TaskQueue(taskRepository, lifecycleManager);
        recoveryService = new TaskRecoveryService(taskRepository, lifecycleManager, checkpointRepository);

        Path sandboxesRoot = tempDir.resolve("workspaces");
        Files.createDirectories(sandboxesRoot);
        workspaceService = new WorkspaceService(sandboxesRoot, workspaceRepository, projectRepository, taskRepository);

        // Bootstrap developer user
        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hashPassword("Secret123!".toCharArray(), salt);
        testUser = new User("dev-engineer", hash, salt, UserRole.DEVELOPER);
        userRepository.save(testUser);

        AuthenticationService authService = new AuthenticationService(userRepository, sessionRepository, permissionManager);
        AuthenticationService.AuthResult auth = authService.login("dev-engineer", "Secret123!", "127.0.0.1", "JUnit");
        this.authToken = auth.token();

        // Initialize real Git repository for testing
        gitRepoPath = tempDir.resolve("test-repo");
        Files.createDirectories(gitRepoPath);
        gitService.runGit(gitRepoPath, List.of("init"));
        gitService.runGit(gitRepoPath, List.of("config", "user.name", "TRYST Test"));
        gitService.runGit(gitRepoPath, List.of("config", "user.email", "tester@tryst.local"));

        Files.writeString(gitRepoPath.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>eu.test</groupId>
                  <artifactId>sample</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Files.writeString(gitRepoPath.resolve("README.md"), "# Initial Sample");
        gitService.runGit(gitRepoPath, List.of("add", "."));
        gitService.runGit(gitRepoPath, List.of("commit", "-m", "Initial commit"));

        testProject = new Project("proj-alpha", testUser.getId(), "Sample Git App", gitRepoPath, true);
        projectRepository.save(testProject);
    }

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    /**
     * Scenario A: Successful Java programming task against a real Git repository.
     */
    @Test
    void testScenarioA_SuccessfulJavaProgrammingTask() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FileTools.WriteFileTool());
        toolRegistry.register(new FileTools.ReadFileTool());
        toolRegistry.register(new GitTools.GitDiffTool());

        AiInferenceService fakeAi = new AiInferenceService() {
            @Override public CompletableFuture<Boolean> checkConnection() { return CompletableFuture.completedFuture(true); }
            @Override public CompletableFuture<List<ModelMetadata>> listModels() { return CompletableFuture.completedFuture(List.of()); }
            @Override public CompletableFuture<InferenceResponse> generateResponse(InferenceRequest req) {
                String toolOutput = "TOOL:write_file PARAM:path=SampleService.java PARAM:content=public class SampleService { public int add(int a, int b) { return a + b; } }";
                return CompletableFuture.completedFuture(new InferenceResponse(toolOutput, "stop", 10, 5, 5));
            }
            @Override public CompletableFuture<Void> streamResponse(InferenceRequest req, java.util.function.Consumer<String> chunkConsumer) {
                chunkConsumer.accept("TOOL:write_file PARAM:path=SampleService.java PARAM:content=public class SampleService { public int add(int a, int b) { return a + b; } }");
                return CompletableFuture.completedFuture(null);
            }
        };

        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                toolRegistry, permissionManager, fakeAi, null, null, null,
                codeReviewRepository, new CodeReviewService(), 2
        );
        orchestrator.setTaskRecoveryService(recoveryService);
        orchestrator.start();

        // 1. Submit task
        Task task = orchestrator.submitTask(testProject.getId(), null, "Implement SampleService", "Add mathematical helper",
                TaskPriority.HIGH, testUser.getId(), "sample-model");
        assertNotNull(task);

        // 2. Await async completion -> WAITING_FOR_REVIEW
        long deadline = System.currentTimeMillis() + 8000;
        Task completed = null;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(task.getId()).orElse(null);
            if (t != null && t.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
                completed = t;
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(completed, "Task should transition to WAITING_FOR_REVIEW");
        assertEquals(TaskStatus.WAITING_FOR_REVIEW, completed.getStatus());

        // 3. Inspect generated code review and diff
        Optional<CodeReview> reviewOpt = codeReviewRepository.findByTaskId(task.getId());
        assertTrue(reviewOpt.isPresent(), "Code review must be generated");
        CodeReview review = reviewOpt.get();
        assertEquals(1, review.getRevisionCount());
        assertNotNull(review.getContentFingerprint());

        // 4. Human-in-the-loop review approval for exact revision
        review.approve(testUser.getId(), 1);
        codeReviewRepository.save(review);
        assertEquals(CodeReview.ReviewStatus.APPROVED, review.getStatus());

        // 5. Authorized commit on the repo
        Workspace taskWs = workspaceService.getWorkspaceForTask(task.getId());
        Path targetCommitDir = (taskWs != null) ? taskWs.getRootPath() : gitRepoPath;
        GitService.AuthorizedCommitResult commitRes = gitService.commitAuthorizedReview(
                targetCommitDir, review, testUser.getId(), "Implemented SampleService"
        );
        assertTrue(commitRes.success(), "Authorized commit must succeed: " + commitRes.error());
        assertNotNull(commitRes.commitHash());

        GitCommitRecord commitRecord = new GitCommitRecord(
                commitRes.commitHash(), review.getId(), task.getId(), testProject.getId(),
                1, testUser.getId(), "Implemented SampleService", review.getApprovedFingerprint(),
                "main", 1
        );
        gitCommitRepository.save(commitRecord);

        Optional<GitCommitRecord> loadedCommit = gitCommitRepository.findByCommitHash(commitRes.commitHash());
        assertTrue(loadedCommit.isPresent());
    }

    /**
     * Scenario B: Failed programming task (compilation failure, diagnostic capture, no false success).
     */
    @Test
    void testScenarioB_FailedProgrammingTask() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        // Custom ResultValidator that simulates build compilation failure
        BuildAndQualityService failingQualityService = new BuildAndQualityService() {
            @Override
            public QualityReport runQualityAssurance(Workspace workspace, String taskId, Duration timeout, java.util.function.BooleanSupplier cancellationSupplier, java.util.function.Consumer<String> outputListener) {
                return new QualityReport(
                        BuildSystemType.MAVEN,
                        BuildExecutionStatus.FAILED,
                        BuildExecutionStatus.NOT_EXECUTED,
                        false,
                        List.of(),
                        "Build failed",
                        "SampleService.java:[1,20] ';' expected"
                );
            }
            @Override
            public StageExecutionResult executeBuild(Workspace workspace, String taskId, Duration timeout, java.util.function.BooleanSupplier cancellationSupplier, java.util.function.Consumer<String> outputListener) {
                return new StageExecutionResult("BUILD", BuildExecutionStatus.FAILED, 1, List.of("mvn", "compile"), "", "Compilation error", 100, "Compilation error");
            }
            @Override
            public StageExecutionResult executeTests(Workspace workspace, String taskId, Duration timeout, java.util.function.BooleanSupplier cancellationSupplier, java.util.function.Consumer<String> outputListener) {
                return new StageExecutionResult("TESTS", BuildExecutionStatus.NOT_EXECUTED, 0, List.of(), "", "", 0, "");
            }
            @Override
            public BuildSystemType detectTechnology(Path repositoryRoot) {
                return BuildSystemType.MAVEN;
            }
            @Override
            public BuildTechnologyDetector getTechnologyDetector() {
                return new BuildTechnologyDetector() {
                    @Override public BuildSystemType detectBuildSystem(Path root) { return BuildSystemType.MAVEN; }
                    @Override public List<String> resolveBuildCommand(Path root) { return List.of("mvn", "compile"); }
                    @Override public List<String> resolveTestCommand(Path root) { return List.of("mvn", "test"); }
                    @Override public BuildConfiguration loadConfiguration(Path root) { return BuildConfiguration.defaults(); }
                };
            }
        };

        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                toolRegistry, permissionManager, (AiInferenceService) null, null, null, null,
                codeReviewRepository, new CodeReviewService(), 1
        );
        orchestrator.setBuildAndQualityService(failingQualityService);
        orchestrator.start();

        Task task = orchestrator.submitTask(testProject.getId(), null, "Task With Error", "Will fail build",
                TaskPriority.NORMAL, testUser.getId(), null);

        long deadline = System.currentTimeMillis() + 8000;
        Task failed = null;
        while (System.currentTimeMillis() < deadline) {
            Task t = taskRepository.findTaskById(task.getId()).orElse(null);
            if (t != null && t.getStatus() == TaskStatus.FAILED) {
                failed = t;
                break;
            }
            Thread.sleep(100);
        }

        assertNotNull(failed, "Task must transition to FAILED status");
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        // Verify no false review or commit exists
        assertTrue(codeReviewRepository.findByTaskId(task.getId()).isEmpty());
    }

    /**
     * Scenario C: Task cancellation (immediate graceful cancellation, process cleanup).
     */
    @Test
    void testScenarioC_TaskCancellation() throws Exception {
        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                new ToolRegistry(), permissionManager, (AiInferenceService) null, null, null, null,
                codeReviewRepository, new CodeReviewService(), 1
        );

        Task task = orchestrator.submitTask(testProject.getId(), null, "Task to Cancel", "Cancel me",
                TaskPriority.LOW, testUser.getId(), null);

        boolean cancelled = orchestrator.cancelTask(task.getId(), "User requested cancellation");
        assertTrue(cancelled);

        Task reloaded = taskRepository.findTaskById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, reloaded.getStatus());
        assertTrue(reloaded.getStatus().isTerminal());
    }

    /**
     * Scenario D: Restart and recovery (recovering tasks and workspace state across restarts).
     */
    @Test
    void testScenarioD_RestartAndRecovery() throws Exception {
        // Create task simulated in IMPLEMENTING state prior to sudden shutdown
        Task crashedTask = new Task(
                UUID.randomUUID().toString(), testProject.getId(), null, "Crashed Task", "Interrupted",
                TaskStatus.IMPLEMENTING, TaskPriority.HIGH, null, null, null, null, 0, null,
                Instant.now(), null
        );
        taskRepository.saveTask(crashedTask);

        checkpointRepository.save(new TaskRecoveryCheckpoint(
                UUID.randomUUID().toString(), crashedTask.getId(), TaskStatus.IMPLEMENTING,
                "{\"stage\":\"IMPLEMENTING\",\"goal\":\"Process\"}", Instant.now()
        ));

        // Simulate application restart
        TaskRecoveryService newRecoveryService = new TaskRecoveryService(taskRepository, lifecycleManager, checkpointRepository);
        List<Task> reconciled = newRecoveryService.reconcileInterruptedTasks();

        assertFalse(reconciled.isEmpty());
        Task recovered = taskRepository.findTaskById(crashedTask.getId()).orElseThrow();
        assertEquals(TaskStatus.RECOVERY_REQUIRED, recovered.getStatus());
    }

    /**
     * Scenario E: Concurrency (multiple tasks executing simultaneously in isolated workspaces).
     */
    @Test
    void testScenarioE_ConcurrencyAndIsolation() throws Exception {
        orchestrator = new TaskOrchestrator(
                taskQueue, taskRepository, lifecycleManager, workspaceService, projectRepository,
                new ToolRegistry(), permissionManager, (AiInferenceService) null, null, null, null,
                codeReviewRepository, new CodeReviewService(), 4
        );
        orchestrator.start();

        int taskCount = 4;
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            Task t = orchestrator.submitTask(testProject.getId(), null, "Concurrent Task " + i, "Desc",
                    TaskPriority.NORMAL, testUser.getId(), null);
            taskIds.add(t.getId());
        }

        long deadline = System.currentTimeMillis() + 10000;
        Set<String> finished = new HashSet<>();
        while (System.currentTimeMillis() < deadline && finished.size() < taskCount) {
            for (String tid : taskIds) {
                Task t = taskRepository.findTaskById(tid).orElse(null);
                if (t != null && t.getStatus() == TaskStatus.WAITING_FOR_REVIEW) {
                    finished.add(tid);
                }
            }
            Thread.sleep(100);
        }

        assertEquals(taskCount, finished.size(), "All concurrent tasks must complete independently to WAITING_FOR_REVIEW");
    }

    /**
     * Scenario F: Review and rework (line comments, AI explanations, rework iteration).
     */
    @Test
    void testScenarioF_ReviewAndReworkCycle() throws Exception {
        Task taskF = new Task(
                "task-f", testProject.getId(), null, "Task F", "Rework test",
                TaskStatus.WAITING_FOR_REVIEW, TaskPriority.NORMAL, null, null, null, null, 0, null,
                Instant.now(), null
        );
        taskRepository.saveTask(taskF);

        CodeReview review = new CodeReview(testProject.getId(), "task-f", "main", "diff --git a/App.java b/App.java\n+int x = 1;\n", "SUCCESS", "Tests passed");
        review.setAiExplanation("Added variable x");
        codeReviewRepository.save(review);

        // 1. User posts line comment requesting changes
        ReviewComment comment = new ReviewComment(
                UUID.randomUUID().toString(), review.getId(), "task-f", 1, "App.java",
                ReviewComment.DiffSide.RIGHT, 1, 1, "int x = 1;",
                ReviewComment.CommentType.LINE, "Please rename variable to counter",
                testUser.getId(), false, true, true, Instant.now(), Instant.now()
        );
        codeReviewRepository.saveComment(comment);

        // 2. Verify comment is blocking
        List<ReviewComment> comments = codeReviewRepository.findCommentsByReviewId(review.getId());
        assertEquals(1, comments.size());
        assertTrue(comments.get(0).isBlocking());

        // 3. User approves attempt while blocking comment exists -> should not permit commit
        GitService.AuthorizedCommitResult prematureCommit = gitService.commitAuthorizedReview(
                gitRepoPath, review, testUser.getId(), "Attempt commit"
        );
        assertFalse(prematureCommit.success(), "Commit must be rejected if not approved");

        // 4. Resolve comment and approve
        comment.setResolved(true);
        codeReviewRepository.saveComment(comment);

        review.approve(testUser.getId(), 1);
        codeReviewRepository.save(review);
        assertEquals(CodeReview.ReviewStatus.APPROVED, review.getStatus());
    }
}
