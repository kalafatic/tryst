package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.domain.model.Permission;
import eu.kalafatic.tryst.domain.model.Project;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;
import eu.kalafatic.tryst.domain.security.AuthenticationService;
import eu.kalafatic.tryst.domain.security.PasswordHasher;
import eu.kalafatic.tryst.domain.security.PermissionManager;
import eu.kalafatic.tryst.domain.security.SecurityContext;
import eu.kalafatic.tryst.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.tryst.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserRepository;
import eu.kalafatic.tryst.infrastructure.persistence.repository.SqliteUserSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationAndAuthorizationTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private UserRepository userRepository;
    private UserSessionRepository sessionRepository;
    private PermissionManager permissionManager;
    private AuthenticationService authService;

    private String savedDevMode;

    @BeforeEach
    void setUp() {
        savedDevMode = System.getProperty("tryst.dev.mode");
        System.setProperty("tryst.dev.mode", "false");

        Path dbPath = tempDir.resolve("auth_test.db");
        databaseManager = new SqliteDatabaseManager(dbPath);
        databaseManager.initialize();
        new SchemaMigrationService(databaseManager).migrate();

        userRepository = new SqliteUserRepository(databaseManager);
        sessionRepository = new SqliteUserSessionRepository(databaseManager);
        permissionManager = new PermissionManager();
        authService = new AuthenticationService(userRepository, sessionRepository, permissionManager);
    }

    @AfterEach
    void tearDown() {
        if (savedDevMode != null) {
            System.setProperty("tryst.dev.mode", savedDevMode);
        } else {
            System.clearProperty("tryst.dev.mode");
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void testPasswordHashingParametersAndVerification() {
        String salt = PasswordHasher.generateSalt();
        assertNotNull(salt);
        assertEquals(44, salt.length(), "32-byte salt Base64 encoded is 44 characters");

        char[] password = "StrongPassword#2026".toCharArray();
        String hash = PasswordHasher.hashPassword(password, salt);
        assertNotNull(hash);

        assertTrue(PasswordHasher.verifyPassword(password, hash, salt));
        assertFalse(PasswordHasher.verifyPassword("WrongPassword".toCharArray(), hash, salt));
    }

    @Test
    void testAdminBootstrapAndAuthenticationLifecycle() {
        User admin = authService.bootstrapAdmin("admin", "AdminSecret#123");
        assertNotNull(admin);
        assertEquals("admin", admin.getUsername());
        assertEquals(UserRole.ADMIN, admin.getRole());

        // 1. Invalid login attempt
        AuthenticationService.AuthResult badLogin = authService.login("admin", "WrongPass", "127.0.0.1", "Agent/1.0");
        assertFalse(badLogin.success());
        assertNull(badLogin.token());

        // 2. Successful login
        AuthenticationService.AuthResult goodLogin = authService.login("admin", "AdminSecret#123", "127.0.0.1", "Agent/1.0");
        assertTrue(goodLogin.success());
        assertNotNull(goodLogin.token());
        assertEquals(64, goodLogin.token().length(), "256-bit token hex representation is 64 chars");

        // 3. Authenticate with valid token
        Optional<SecurityContext> ctxOpt = authService.authenticateToken(goodLogin.token());
        assertTrue(ctxOpt.isPresent());
        SecurityContext ctx = ctxOpt.get();
        assertEquals("admin", ctx.getUsername());
        assertTrue(ctx.isAdmin());

        // 4. Logout invalidates session
        authService.logout(goodLogin.token());
        Optional<SecurityContext> afterLogout = authService.authenticateToken(goodLogin.token());
        assertTrue(afterLogout.isEmpty(), "Session token must be invalid after logout");
    }

    @Test
    void testBruteForceLockout() {
        authService.bootstrapAdmin("alice", "AlicePass#1");

        for (int i = 0; i < 5; i++) {
            AuthenticationService.AuthResult fail = authService.login("alice", "bad", "127.0.0.1", "test");
            assertFalse(fail.success());
        }

        User alice = userRepository.findByUsername("alice").orElseThrow();
        assertTrue(alice.isLocked(), "User should be locked after 5 failed attempts");

        // Even with correct password, login must now be denied
        AuthenticationService.AuthResult lockedLogin = authService.login("alice", "AlicePass#1", "127.0.0.1", "test");
        assertFalse(lockedLogin.success());
        assertTrue(lockedLogin.errorMessage().contains("locked"));
    }

    @Test
    void testRbacPermissionEnforcement() {
        User admin = new User("admin_user", "hash", "salt", UserRole.ADMIN);
        User dev = new User("dev_user", "hash", "salt", UserRole.DEVELOPER);
        User reviewer = new User("rev_user", "hash", "salt", UserRole.REVIEWER);

        assertTrue(admin.hasPermission(Permission.MANAGE_USERS));
        assertFalse(dev.hasPermission(Permission.MANAGE_USERS));
        assertFalse(reviewer.hasPermission(Permission.MANAGE_USERS));

        assertTrue(dev.hasPermission(Permission.COMMIT_APPROVED_CHANGES));
        assertFalse(reviewer.hasPermission(Permission.COMMIT_APPROVED_CHANGES));

        // Test project access authorization with PermissionManager
        Project project = new Project("proj-1", "alice", "Alice Project", tempDir, true);
        SecurityContext aliceCtx = new SecurityContext(new User("alice", "h", "s", UserRole.USER));
        SecurityContext bobCtx = new SecurityContext(new User("bob", "h", "s", UserRole.USER));
        SecurityContext adminCtx = new SecurityContext(admin);

        assertDoesNotThrow(() -> permissionManager.validateUserProjectAccess("alice", project, aliceCtx));
        assertDoesNotThrow(() -> permissionManager.validateUserProjectAccess("admin_user", project, adminCtx));
        assertThrows(TrystDomainException.class, () -> permissionManager.validateUserProjectAccess("bob", project, bobCtx));
    }
}
