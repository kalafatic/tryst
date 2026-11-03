package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.review.ReviewComment;
import eu.kalafatic.tryst.domain.review.ReviewRevision;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteCodeReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ReviewCommentPersistenceTest {

    private Path tempDir;
    private SqliteDatabaseManager dbManager;
    private SqliteCodeReviewRepository reviewRepository;

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("tryst-review-persistence-test-");
        Path dbPath = tempDir.resolve("tryst-test.db");
        dbManager = new SqliteDatabaseManager(dbPath);
        dbManager.initialize();
        new SchemaMigrationService(dbManager).migrate();
        reviewRepository = new SqliteCodeReviewRepository(dbManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (dbManager != null) {
            dbManager.close();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testReviewRevisionsAndCommentsPersistence() {
        String projectId = "proj-" + UUID.randomUUID();
        String taskId = "task-" + UUID.randomUUID();
        String reviewId = "rev-" + UUID.randomUUID();

        // Ensure parent project and task exist to satisfy foreign keys
        dbManager.runInTransaction(() -> {
            try (var stmt = dbManager.getConnection().createStatement()) {
                stmt.execute(String.format("INSERT INTO projects (id, name, location, created_at, updated_at) VALUES ('%s', 'Test', '/tmp', '%s', '%s')",
                        projectId, Instant.now(), Instant.now()));
                stmt.execute(String.format("INSERT INTO tasks (id, project_id, title, status, created_at) VALUES ('%s', '%s', 'Test Task', 'WAITING_FOR_REVIEW', '%s')",
                        taskId, projectId, Instant.now()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CodeReview review = new CodeReview(
                reviewId, projectId, taskId, "main", CodeReview.ReviewStatus.PENDING,
                "diff1", "diff1", "fp1", null, null, null, null, null,
                "SUCCESS", "All passed", "Initial AI implementation", 1,
                Instant.now(), Instant.now()
        );
        reviewRepository.save(review);

        // Save Revision 1
        ReviewRevision rev1 = new ReviewRevision(
                UUID.randomUUID().toString(), reviewId, 1, "diff1", "Initial AI implementation",
                "SUCCESS", "All passed", Instant.now()
        );
        reviewRepository.saveRevision(rev1);

        // Save Comments referencing stable review context
        ReviewComment c1 = new ReviewComment(
                UUID.randomUUID().toString(), reviewId, taskId, 1, "src/Main.java",
                ReviewComment.DiffSide.RIGHT, 10, 15, "int x = 42;", ReviewComment.CommentType.LINE,
                "Please extract this constant.", "reviewer1", false, true, Instant.now(), Instant.now()
        );
        ReviewComment c2 = new ReviewComment(
                UUID.randomUUID().toString(), reviewId, taskId, 1, "src/Main.java",
                ReviewComment.DiffSide.RIGHT, 1, 1, "", ReviewComment.CommentType.FILE,
                "Add file-level javadoc.", "reviewer1", false, false, Instant.now(), Instant.now()
        );
        reviewRepository.saveComment(c1);
        reviewRepository.saveComment(c2);

        // Verify Revisions
        List<ReviewRevision> revisions = reviewRepository.findRevisionsByReviewId(reviewId);
        assertEquals(1, revisions.size());
        assertEquals(1, revisions.get(0).getRevisionNumber());
        assertEquals("Initial AI implementation", revisions.get(0).getExplanation());

        // Verify Comments
        List<ReviewComment> comments = reviewRepository.findCommentsByReviewId(reviewId);
        assertEquals(2, comments.size());

        Optional<ReviewComment> optC1 = reviewRepository.findCommentById(c1.getId());
        assertTrue(optC1.isPresent());
        assertEquals("src/Main.java", optC1.get().getFilePath());
        assertEquals(10, optC1.get().getStartLine());
        assertEquals(15, optC1.get().getEndLine());
        assertEquals("int x = 42;", optC1.get().getCodeContext());
        assertTrue(optC1.get().isIncludeInRework());
        assertFalse(optC1.get().isResolved());

        // Test updating comment resolution and rework flag
        c1.setResolved(true);
        c1.setIncludeInRework(false);
        reviewRepository.saveComment(c1);

        ReviewComment updatedC1 = reviewRepository.findCommentById(c1.getId()).orElseThrow();
        assertTrue(updatedC1.isResolved());
        assertFalse(updatedC1.isIncludeInRework());

        // Test deleting comment
        boolean deleted = reviewRepository.deleteComment(c2.getId());
        assertTrue(deleted);
        assertEquals(1, reviewRepository.findCommentsByReviewId(reviewId).size());
    }
}
