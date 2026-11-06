package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.core.error.TrystDomainException;
import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;
import eu.kalafatic.tryst.domain.model.UserSession;
import eu.kalafatic.tryst.domain.repository.UserRepository;
import eu.kalafatic.tryst.domain.repository.UserSessionRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative authentication and session management service.
 * Enforces credential verification, brute-force lockout, session generation,
 * session revocation, and safe initial administrator bootstrap.
 */
public class AuthenticationService {

    public static final long DEFAULT_SESSION_TTL_SECONDS = 3600 * 8; // 8 hours
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PermissionManager permissionManager;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile String oneTimeSetupToken;
    private volatile boolean setupCompleted = false;

    public record AuthResult(
            boolean success,
            String token,
            User user,
            String errorMessage
    ) {}

    public AuthenticationService(UserRepository userRepository,
                                 UserSessionRepository sessionRepository,
                                 PermissionManager permissionManager) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.permissionManager = permissionManager != null ? permissionManager : new PermissionManager();
    }

    /**
     * Idempotently bootstraps the initial system administrator if no users exist.
     * Generates a strong random password or configures the specified bootstrap password.
     */
    public synchronized User bootstrapAdmin(String username, String initialPassword) {
        Objects.requireNonNull(username, "username must not be null");
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent()) {
            return existing.get();
        }

        String password = (initialPassword != null && !initialPassword.isBlank())
                ? initialPassword
                : generateSecureToken().substring(0, 16);

        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hashPassword(password.toCharArray(), salt);

        User admin = new User(username, hash, salt, UserRole.ADMIN);
        userRepository.save(admin);
        this.setupCompleted = true;
        this.oneTimeSetupToken = null;
        permissionManager.recordAudit("system", "ADMIN_BOOTSTRAP", "user", admin.getId(), null, null, "SUCCESS",
                "Bootstrapped administrator user '" + username + "'");
        TrystLog.info(AuthenticationService.class, "Bootstrapped administrator '" + username + "'.");
        return admin;
    }

    public synchronized boolean hasAdministrator() {
        return userRepository.findAll().stream().anyMatch(u -> u.getRole() == UserRole.ADMIN);
    }

    public synchronized String generateOneTimeSetupToken() {
        if (hasAdministrator()) {
            throw new TrystDomainException("Administrator already exists; setup token generation not permitted");
        }
        if (this.oneTimeSetupToken == null) {
            this.oneTimeSetupToken = generateSecureToken();
            TrystLog.warn(AuthenticationService.class, "Generated One-Time Setup Token: " + this.oneTimeSetupToken);
        }
        return this.oneTimeSetupToken;
    }

    public synchronized String getOneTimeSetupToken() {
        return this.oneTimeSetupToken;
    }

    public synchronized boolean isSetupRequired() {
        return !hasAdministrator();
    }

    public synchronized User completeInitialSetup(String token, String adminUsername, String adminPassword) {
        if (hasAdministrator()) {
            throw new TrystDomainException("Setup is already completed; administrator exists");
        }
        if (this.oneTimeSetupToken == null || !java.security.MessageDigest.isEqual(
                this.oneTimeSetupToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            permissionManager.recordAudit("system", "SETUP_FAILED", "system", null, null, null, "DENIED", "Invalid one-time setup token");
            throw new TrystDomainException("Invalid setup token");
        }
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new TrystDomainException("Administrator username and password must not be blank");
        }

        String salt = PasswordHasher.generateSalt();
        String hash = PasswordHasher.hashPassword(adminPassword.toCharArray(), salt);
        User admin = new User(adminUsername.trim(), hash, salt, UserRole.ADMIN);
        userRepository.save(admin);

        this.oneTimeSetupToken = null;
        this.setupCompleted = true;
        permissionManager.recordAudit("system", "FIRST_RUN_SETUP", "user", admin.getId(), null, null, "SUCCESS",
                "Initial administrator provisioned via first-run wizard: " + adminUsername);
        TrystLog.info(AuthenticationService.class, "First-run setup complete. Administrator provisioned: " + adminUsername);
        return admin;
    }

    public AuthResult login(String username, String rawPassword, String clientIp, String userAgent) {
        if (username == null || username.isBlank() || rawPassword == null) {
            return new AuthResult(false, null, null, "Invalid credentials");
        }

        Optional<User> userOpt = userRepository.findByUsername(username.trim());
        if (userOpt.isEmpty()) {
            TrystLog.warn(AuthenticationService.class, "Authentication failed: Unknown username '" + username.trim() + "' from " + clientIp);
            permissionManager.recordAudit(username, "LOGIN", "session", null, null, null, "FAILED", "Unknown username");
            return new AuthResult(false, null, null, "Invalid username or password");
        }

        User user = userOpt.get();
        if (!user.isActive()) {
            permissionManager.recordAudit(username, "LOGIN", "session", null, null, null, "DENIED", "Account inactive");
            return new AuthResult(false, null, null, "Account is deactivated");
        }
        if (user.isLocked()) {
            permissionManager.recordAudit(username, "LOGIN", "session", null, null, null, "DENIED", "Account locked");
            return new AuthResult(false, null, null, "Account is locked due to security policy");
        }

        boolean passwordValid = PasswordHasher.verifyPassword(rawPassword.toCharArray(), user.getPasswordHash(), user.getSalt());
        if (!passwordValid) {
            user.incrementFailedAttempts();
            userRepository.save(user);
            TrystLog.warn(AuthenticationService.class, "Authentication failed: Invalid credentials for user '" + username.trim() + "' from " + clientIp);
            permissionManager.recordAudit(username, "LOGIN", "session", null, null, null, "FAILED",
                    "Password incorrect (attempts: " + user.getFailedAttempts() + ")");
            if (user.isLocked()) {
                permissionManager.recordAudit(username, "ACCOUNT_LOCK", "user", user.getId(), null, null, "LOCKED",
                        "Account locked after " + user.getFailedAttempts() + " failed attempts");
            }
            return new AuthResult(false, null, null, "Invalid username or password");
        }

        // Reset failed attempts on success
        user.resetFailedAttempts();
        userRepository.save(user);

        // Session fixation protection: create new session token
        String rawToken = generateSecureToken();
        String tokenHash = hashToken(rawToken);

        UserSession session = new UserSession(user.getId(), tokenHash, clientIp, userAgent, DEFAULT_SESSION_TTL_SECONDS);
        sessionRepository.save(session);

        permissionManager.recordAudit(username, "LOGIN", "session", session.getId(), null, null, "SUCCESS",
                "User logged in from " + clientIp);

        return new AuthResult(true, rawToken, user, null);
    }

    public Optional<SecurityContext> authenticateToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = hashToken(rawToken.trim());
        Optional<UserSession> sessionOpt = sessionRepository.findByTokenHash(tokenHash);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }

        UserSession session = sessionOpt.get();
        if (!session.isValid()) {
            return Optional.empty();
        }

        Optional<User> userOpt = userRepository.findById(session.getUserId());
        if (userOpt.isEmpty() || !userOpt.get().isActive() || userOpt.get().isLocked()) {
            return Optional.empty();
        }

        // Slide session
        session.touch(DEFAULT_SESSION_TTL_SECONDS);
        sessionRepository.save(session);

        return Optional.of(new SecurityContext(userOpt.get()));
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        String tokenHash = hashToken(rawToken.trim());
        Optional<UserSession> sessionOpt = sessionRepository.findByTokenHash(tokenHash);
        if (sessionOpt.isPresent()) {
            UserSession session = sessionOpt.get();
            session.revoke();
            sessionRepository.save(session);
            permissionManager.recordAudit(session.getUserId(), "LOGOUT", "session", session.getId(), null, null, "SUCCESS", "Logged out");
        }
    }

    public String generateSecureToken() {
        byte[] bytes = new byte[32]; // 256-bit entropy
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new TrystDomainException("SHA-256 algorithm missing", e);
        }
    }
}
