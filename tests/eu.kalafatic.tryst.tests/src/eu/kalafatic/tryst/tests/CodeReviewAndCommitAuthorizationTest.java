package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.AgentTask;
import eu.kalafatic.tryst.domain.model.ExecutionStatus;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.repository.CodeReviewRepository;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewFileEdit;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteProjectRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteTaskRepository;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeReviewAndCommitAuthorizationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("review_test.db");
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
    void testCodeReviewLifecycleAndFingerprintApproval() {
        SqliteProjectRepository projectRepo = new SqliteProjectRepository(databaseManager);
        SqliteTaskRepository taskRepo = new SqliteTaskRepository(databaseManager);
        CodeReviewRepository reviewRepo = new SqliteCodeReviewRepository(databaseManager);

        Project project = new Project("proj-1", "alice", "Test Project", tempDir, true);
        projectRepo.save(project);

        AgentTask task = new AgentTask("task-1", "proj-1", null, "Title", "Desc", TaskStatus.RUNNING, null, null, null, null, java.time.Instant.now(), null);
        taskRepo.save(task);

        String initialDiff = "diff --git a/App.java b/App.java\n+int x = 10;\n";
        CodeReview review = new CodeReview("proj-1", "task-1", "main", initialDiff, "SUCCESS", "All tests passed");
        reviewRepo.save(review);

        assertEquals(CodeReview.ReviewStatus.PENDING, review.getStatus());
        assertFalse(review.isApproved());
        assertNotNull(review.getContentFingerprint());

        // 1. Approve review
        review.approve("alice");
        assertTrue(review.isApproved());
        assertEquals("alice", review.getApprovedBy());
        assertEquals(review.getContentFingerprint(), review.getApprovedFingerprint());
        reviewRepo.save(review);

        // 2. Modifying effective diff invalidates approval
        String modifiedDiff = initialDiff + "+int y = 20;\n";
        review.updateEffectiveDiff(modifiedDiff);
        assertFalse(review.isApproved(), "Approval must be invalidated when effective content changes");
        assertEquals(CodeReview.ReviewStatus.PENDING, review.getStatus());
        assertNull(review.getApprovedBy());

        // 3. User edits recorded
        ReviewFileEdit edit = new ReviewFileEdit(review.getId(), "App.java", "int x = 10;", "int x = 10; int y = 20;");
        reviewRepo.saveFileEdit(edit);

        List<ReviewFileEdit> edits = reviewRepo.findFileEditsByReviewId(review.getId());
        assertEquals(1, edits.size());
        assertEquals("App.java", edits.get(0).getFilePath());
    }

    @Test
    void testAuthorizedGitCommitGating() throws IOException {
        GitService git = new GitService();
        Path repoDir = tempDir.resolve("git_repo");
        Files.createDirectories(repoDir);

        // Initialize git repository
        git.runGit(repoDir, List.of("init"));
        git.runGit(repoDir, List.of("config", "user.email", "tryst@example.com"));
        git.runGit(repoDir, List.of("config", "user.name", "Tryst Test"));

        // Initial base commit
        Path mainFile = repoDir.resolve("Main.java");
        Files.writeString(mainFile, "public class Main {}\n");
        git.stageAll(repoDir);
        git.commit(repoDir, "Initial commit");

        // Tool introduces changes in workspace
        Files.writeString(mainFile, "public class Main {\n    // Agent modification\n}\n");
        GitService.GitCommandResult diffRes = git.getDiff(repoDir);
        String workingDiff = diffRes.output();

        CodeReview review = new CodeReview("proj-git", "task-git", "main", workingDiff, "SUCCESS", "Build OK");

        // 1. Attempting commit without approval must fail
        GitService.AuthorizedCommitResult unapprovedCommit = git.commitAuthorizedReview(repoDir, review, "alice", "Test commit");
        assertFalse(unapprovedCommit.success());
        assertTrue(unapprovedCommit.error().contains("not approved"));

        // 2. Approve review
        review.approve("alice");
        assertTrue(review.isApproved());

        // 3. Modify working tree content after approval (time-of-check / time-of-use scenario)
        Files.writeString(mainFile, "public class Main {\n    // Agent modification\n    // Unauthorized extra line\n}\n");
        GitService.AuthorizedCommitResult tamperedCommit = git.commitAuthorizedReview(repoDir, review, "alice", "Test commit");
        assertFalse(tamperedCommit.success(), "Commit must be rejected if working tree changed after approval");
        assertTrue(tamperedCommit.error().contains("does not match approved review fingerprint"));

        // 4. Reconcile working tree to approved diff and commit
        Files.writeString(mainFile, "public class Main {\n    // Agent modification\n}\n");
        GitService.AuthorizedCommitResult authorizedCommit = git.commitAuthorizedReview(repoDir, review, "alice", "Authorized commit");
        assertTrue(authorizedCommit.success());
        assertNotNull(authorizedCommit.commitHash());
        assertEquals(CodeReview.ReviewStatus.COMMITTED, review.getStatus());
        assertEquals(authorizedCommit.commitHash(), review.getCommitHash());
    }

    @Test
    void testTaskStateTransitionsAndRecoveryRequired() {
        AgentTask task = new AgentTask("Feature A", "Build feature");
        assertEquals(TaskStatus.CREATED, task.getTaskStatus());

        task.queue();
        assertEquals(TaskStatus.QUEUED, task.getTaskStatus());

        task.claim("agent-1");
        assertEquals(TaskStatus.CLAIMED, task.getTaskStatus());
        assertEquals("agent-1", task.getAgentId());

        task.start();
        assertEquals(TaskStatus.RUNNING, task.getTaskStatus());

        // Simulate crash / restart -> markRecoveryRequired
        task.markRecoveryRequired();
        assertEquals(TaskStatus.RECOVERY_REQUIRED, task.getTaskStatus());
        assertEquals(ExecutionStatus.RECOVERY_REQUIRED, task.getStatus());
    }
}
