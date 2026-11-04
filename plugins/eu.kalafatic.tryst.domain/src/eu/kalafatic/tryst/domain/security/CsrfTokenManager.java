package eu.kalafatic.tryst.domain.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure Java 21 CSRF token manager.
 */
public class CsrfTokenManager {

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, String> sessionCsrfTokens = new ConcurrentHashMap<>();

    public String getOrCreateToken(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return sessionCsrfTokens.computeIfAbsent(sessionId, k -> generateToken());
    }

    public String refreshToken(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String newToken = generateToken();
        sessionCsrfTokens.put(sessionId, newToken);
        return newToken;
    }

    public boolean validateToken(String sessionId, String providedToken) {
        if (sessionId == null || sessionId.isBlank() || providedToken == null || providedToken.isBlank()) {
            return false;
        }
        String expectedToken = sessionCsrfTokens.get(sessionId);
        if (expectedToken == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                providedToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    public void invalidateToken(String sessionId) {
        if (sessionId != null) {
            sessionCsrfTokens.remove(sessionId);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32]; // 256 bits entropy
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
