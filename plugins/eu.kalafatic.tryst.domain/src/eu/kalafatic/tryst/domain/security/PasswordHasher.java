package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.core.error.TrystDomainException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

/**
 * Modern password hashing utility using PBKDF2WithHmacSHA256 with 210,000 iterations
 * and cryptographic random 32-byte salts (pure Java 21 standard crypto).
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 32;

    private PasswordHasher() {}

    public static String generateSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hashPassword(char[] password, String base64Salt) {
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(base64Salt, "base64Salt must not be null");
        try {
            byte[] saltBytes = Base64.getDecoder().decode(base64Salt);
            PBEKeySpec spec = new PBEKeySpec(password, saltBytes, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new TrystDomainException("Failed to hash password", e);
        }
    }

    public static boolean verifyPassword(char[] password, String expectedHash, String base64Salt) {
        String computedHash = hashPassword(password, base64Salt);
        return constantTimeEquals(computedHash, expectedHash);
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(aBytes, bBytes);
    }
}
