package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.review.CodeReviewService;
import eu.kalafatic.tryst.infrastructure.vcs.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitAndCodeReviewTest {

    @TempDir
    Path tempRepoDir;

    @Test
    void testGitServiceOperations() throws Exception {
        GitService git = new GitService();

        // 1. Initialize git repo
        GitService.GitCommandResult initRes = git.runGit(tempRepoDir, java.util.List.of("init"));
        assertTrue(initRes.isSuccess());
        assertTrue(git.isGitRepository(tempRepoDir));

        // Configure dummy user for committing
        git.runGit(tempRepoDir, java.util.List.of("config", "user.email", "tryst@example.com"));
        git.runGit(tempRepoDir, java.util.List.of("config", "user.name", "Tryst Test"));

        // 2. Create and stage a file
        Files.writeString(tempRepoDir.resolve("README.md"), "# Test Project");
        GitService.GitCommandResult stageRes = git.stageAll(tempRepoDir);
        assertTrue(stageRes.isSuccess());

        // 3. Commit
        GitService.GitCommandResult commitRes = git.commit(tempRepoDir, "Initial commit");
        assertTrue(commitRes.isSuccess());

        // 4. Modify file and check diff
        Files.writeString(tempRepoDir.resolve("README.md"), "# Test Project\nNew Line Added");
        GitService.GitCommandResult diffRes = git.getDiff(tempRepoDir);
        assertTrue(diffRes.isSuccess());
        assertTrue(diffRes.output().contains("+New Line Added"));
    }

    @Test
    void testCodeReviewServiceDetectsDefectsAndSecurityIssues() {
        CodeReviewService reviewService = new CodeReviewService();

        String simulatedDiff = """
            --- a/App.java
            +++ b/App.java
            @@ -1,3 +1,4 @@
            +String password = "secretPassword123";
            +System.exit(1);
            """;

        CodeReviewService.ReviewReport report = reviewService.reviewChanges(simulatedDiff, "BUILD SUCCESS", true);

        assertFalse(report.approved());
        assertTrue(report.defectCount() >= 2);
        assertTrue(report.findings().stream().anyMatch(f -> f.category().equals("Security Risk")));
        assertTrue(report.findings().stream().anyMatch(f -> f.category().equals("Execution Safety")));
    }
}
