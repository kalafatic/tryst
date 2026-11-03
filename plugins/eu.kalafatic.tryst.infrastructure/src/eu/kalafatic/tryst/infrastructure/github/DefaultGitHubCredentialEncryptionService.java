package eu.kalafatic.tryst.infrastructure.github;

import eu.kalafatic.tryst.core.error.TrystInfrastructureException;
import eu.kalafatic.tryst.domain.github.GitHubCredentialEncryptionService;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Production AES-256-GCM encryption service protecting GitHub credentials at rest.
 * Uses PBKDF2 with HMAC-SHA256 key derivation from server master secret.
 * Always masks sensitive tokens in process arguments and log outputs.
 */
public class DefaultGitHubCredentialEncryptionService implements GitHubCredentialEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12; // 96-bit IV recommended for GCM
    private static final int SALT_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATIONS = 100_000;

    private final String masterSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public DefaultGitHubCredentialEncryptionService() {
        this(resolveServerMasterSecret());
    }

    public DefaultGitHubCredentialEncryptionService(String masterSecret) {
        this.masterSecret = Objects.requireNonNull(masterSecret, "masterSecret must not be null");
        if (masterSecret.isBlank()) {
            throw new IllegalArgumentException("masterSecret must not be blank");
        }
    }

    private static String resolveServerMasterSecret() {
        String env = System.getenv("TRYST_MASTER_SECRET");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String prop = System.getProperty("tryst.master.secret");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        // Machine / host-scoped deterministic fallback secret based on user home and os
        String seed = System.getProperty("user.home", "/tmp") + ":" + System.getProperty("user.name", "tryst") + ":tryst-vcs-secret-key-material";
        return seed;
    }

    @Override
    public String encrypt(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new IllegalArgumentException("plaintextToken must not be blank");
        }

        try {
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            SecretKey key = deriveKey(masterSecret, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] ciphertext = cipher.doFinal(plaintextToken.getBytes(StandardCharsets.UTF_8));

            // Format: Base64(salt) + ":" + Base64(iv) + ":" + Base64(ciphertext)
            return Base64.getEncoder().encodeToString(salt) + ":" +
                   Base64.getEncoder().encodeToString(iv) + ":" +
                   Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new TrystInfrastructureException("Failed to encrypt GitHub credential: " + e.getMessage(), e);
        }
    }

    @Override
    public String decrypt(String ciphertextPayload) {
        if (ciphertextPayload == null || ciphertextPayload.isBlank()) {
            throw new IllegalArgumentException("ciphertextPayload must not be blank");
        }

        try {
            String[] parts = ciphertextPayload.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid encrypted payload format");
            }

            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

            SecretKey key = deriveKey(masterSecret, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new TrystInfrastructureException("Failed to decrypt GitHub credential: " + e.getMessage(), e);
        }
    }

    @Override
    public String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "********";
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 8) {
            return "********";
        }
        // If token starts with prefix (e.g. ghp_, github_pat_), keep prefix + trailing 4 chars
        int prefixIdx = trimmed.indexOf('_');
        String prefix = (prefixIdx > 0 && prefixIdx <= 15) ? trimmed.substring(0, prefixIdx + 1) : "";
        String suffix = trimmed.substring(trimmed.length() - 4);
        return prefix + "****" + suffix;
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        byte[] encoded = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(encoded, "AES");
    }
}
