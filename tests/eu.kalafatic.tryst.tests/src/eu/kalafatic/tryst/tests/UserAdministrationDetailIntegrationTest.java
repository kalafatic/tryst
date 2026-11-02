package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.*;
import eu.kalafatic.tryst.domain.repository.*;
import eu.kalafatic.tryst.domain.review.CodeReview;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.*;
import eu.kalafatic.tryst.ui.view.UserDetailView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying complete user resource relationship mapping:
 * - User to owned projects (findByOwnerId)
 * - User to auth sessions (findByUserId)
 * - User projects to application sessions, tasks, code reviews, and audit events
 * - Cross-user data isolation and administrative boundary enforcement
 */
public class UserAdministrationDetailIntegrationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SqliteUserRepository userRepository;
    private SqliteUserSessionRepository userSessionRepository;
    private SqliteProjectRepository projectRepository;
    private SqliteSessionRepository sessionRepository;
    private SqliteTaskRepository taskRepository;
    private SqliteCodeReviewRepository codeReviewRepository;
    private SqliteAuditEventRepository auditEventRepository;
    private SqliteNotificationRepository notificationRepository;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("tryst_user_admin_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();

        SchemaMigrationService migrationService = new SchemaMigrationService(databaseManager);
        migrationService.migrate();

        userRepository = new SqliteUserRepository(databaseManager);
        userSessionRepository = new SqliteUserSessionRepository(databaseManager);
        projectRepository = new SqliteProjectRepository(databaseManager);
        sessionRepository = new SqliteSessionRepository(databaseManager);
        taskRepository = new SqliteTaskRepository(databaseManager);
        codeReviewRepository = new SqliteCodeReviewRepository(databaseManager);
        auditEventRepository = new SqliteAuditEventRepository(databaseManager);
        notificationRepository = new SqliteNotificationRepository(databaseManager);

        authenticationService = new AuthenticationService(userRepository, userSessionRepository, new PermissionManager(auditEventRepository));
    }

    @AfterEach
    void tearDown() {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testComprehensiveUserResourceLinking() {
        // 1. Provision user
        String salt = eu.kalafatic.tryst.domain.security.PasswordHasher.generateSalt();
        String hash = eu.kalafatic.tryst.domain.security.PasswordHasher.hashPassword("AliceSecret#123".toCharArray(), salt);
        User alice = new User("alice", hash, salt, UserRole.DEVELOPER);
        userRepository.save(alice);
        assertNotNull(alice);

        // 2. Auth sessions for Alice
        AuthenticationService.AuthResult login1 = authenticationService.login("alice", "AliceSecret#123", "192.168.1.10", "Firefox/124.0");
        assertTrue(login1.success(), "Alice login 1 should succeed: " + login1.errorMessage());
        AuthenticationService.AuthResult login2 = authenticationService.login("alice", "AliceSecret#123", "10.0.0.5", "Chrome/122.0");
        assertTrue(login2.success(), "Alice login 2 should succeed: " + login2.errorMessage());

        List<UserSession> aliceAuthSessions = userSessionRepository.findByUserId(alice.getId());
        assertEquals(2, aliceAuthSessions.size(), "Alice should have 2 active authentication sessions");

        // 3. Alice creates Projects
        Project p1 = new Project("p-1", alice.getId(), "Alice Service API", tempDir.resolve("alice_api"), true);
        Project p2 = new Project("p-2", alice.getId(), "Alice Frontend", tempDir.resolve("alice_ui"), false);
        projectRepository.save(p1);
        projectRepository.save(p2);

        List<Project> aliceProjects = projectRepository.findByOwnerId(alice.getId());
        assertEquals(2, aliceProjects.size(), "Alice should own exactly 2 projects");

        // 4. Autonomous application execution sessions within Alice's projects
        Session s1 = new Session("s-1", p1.getId(), "Autonomous Bugfix Sprint", "ACTIVE", Instant.now(), Instant.now());
        sessionRepository.save(s1);

        List<Session> p1Sessions = sessionRepository.findByProjectId(p1.getId());
        assertEquals(1, p1Sessions.size(), "Project 1 should have 1 execution session");

        // 5. Tasks within Alice's projects
        AgentTask t1 = new AgentTask("t-1", p1.getId(), s1.getId(), "Fix Security Bug", "Validate inputs",
                TaskStatus.WAITING_FOR_REVIEW, TaskPriority.HIGH, "agent-1", null, Instant.now(), null);
        taskRepository.save(t1);

        List<AgentTask> p1Tasks = taskRepository.findByProjectId(p1.getId());
        assertEquals(1, p1Tasks.size(), "Project 1 should have 1 task");

        // 6. Code Review for the task
        CodeReview rev = new CodeReview(p1.getId(), t1.getId(), "feature/auth-fix",
                "diff --git a/Security.java\n+fix", "SUCCESS", "1 test passed");
        codeReviewRepository.save(rev);

        List<CodeReview> p1Reviews = codeReviewRepository.findByProjectId(p1.getId());
        assertEquals(1, p1Reviews.size(), "Project 1 should have 1 code review");

        // 7. Audit events generated by Alice
        AuditEvent a1 = AuditEvent.create(alice.getId(), "LOGIN", "USER", alice.getId(), null, null, "SUCCESS", "{\"ip\":\"192.168.1.10\"}");
        AuditEvent a2 = AuditEvent.create(alice.getId(), "CREATE_TASK", "TASK", t1.getId(), p1.getId(), t1.getId(), "SUCCESS", "{}");
        auditEventRepository.save(a1);
        auditEventRepository.save(a2);

        List<AuditEvent> aliceAudits = auditEventRepository.query(alice.getId(), null, null, null, null, 50, 0);
        assertEquals(2, aliceAudits.size(), "Alice should have 2 recorded audit events");

        // 8. Notifications for Alice
        Notification notif = new Notification("n-1", alice.getId(), "TASK_COMPLETE", "Task Review Ready", "Diff generated", "TASK", t1.getId(), false, Instant.now());
        notificationRepository.save(notif);

        List<Notification> aliceNotifs = notificationRepository.findByUserId(alice.getId(), false, 10);
        assertEquals(1, aliceNotifs.size(), "Alice should have 1 notification");

        // Verify ID of UserDetailView matches the Eclipse plugin ID convention
        assertEquals("eu.kalafatic.tryst.ui.view.userDetail", UserDetailView.ID);
    }

    @Test
    void testCrossUserDataIsolation() {
        String salt1 = eu.kalafatic.tryst.domain.security.PasswordHasher.generateSalt();
        String hash1 = eu.kalafatic.tryst.domain.security.PasswordHasher.hashPassword("BobSecret#123".toCharArray(), salt1);
        User bob = new User("bob", hash1, salt1, UserRole.DEVELOPER);
        userRepository.save(bob);

        String salt2 = eu.kalafatic.tryst.domain.security.PasswordHasher.generateSalt();
        String hash2 = eu.kalafatic.tryst.domain.security.PasswordHasher.hashPassword("CharlieSecret#123".toCharArray(), salt2);
        User charlie = new User("charlie", hash2, salt2, UserRole.DEVELOPER);
        userRepository.save(charlie);

        Project bobProject = new Project("p-bob", bob.getId(), "Bob Backend", tempDir.resolve("bob"), true);
        Project charlieProject = new Project("p-charlie", charlie.getId(), "Charlie Analytics", tempDir.resolve("charlie"), true);

        projectRepository.save(bobProject);
        projectRepository.save(charlieProject);

        List<Project> bobList = projectRepository.findByOwnerId(bob.getId());
        List<Project> charlieList = projectRepository.findByOwnerId(charlie.getId());

        assertEquals(1, bobList.size());
        assertEquals("p-bob", bobList.get(0).getId());

        assertEquals(1, charlieList.size());
        assertEquals("p-charlie", charlieList.get(0).getId());

        // Non-existent user returns empty list cleanly
        List<Project> emptyList = projectRepository.findByOwnerId("non-existent-user");
        assertTrue(emptyList.isEmpty(), "Query for unknown user must return empty list without error");
    }
}
