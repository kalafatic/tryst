package eu.kalafatic.tryst.infrastructure.persistence;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AES-GCM encrypt-at-rest codec for sensitive configuration values (API keys, tokens).
 *
 * <p>Used by the persisted AI-provider configuration: values whose keys look sensitive
 * ({@code token}, {@code apiKey}, {@code secret}, {@code password}, {@code credential})
 * are encrypted transparently on save and decrypted on load. The 256-bit master key
 * lives in {@code secrets.key} inside the TRYST configuration directory with owner-only
 * permissions; if the file is missing a fresh random key is generated on first use.</p>
 *
 * <p>Wire format: {@code enc:<base64 IV>:<base64 ciphertext>} (AES/GCM/NoPadding,
 * 12-byte IV, 128-bit authentication tag). Secrets are never exposed in logs,
 * diagnostics, UI or execution snapshots.</p>
 */
public final class SecretCodec {

    public static final String KEY_FILE_NAME = "secrets.key";
    private static final String PREFIX = "enc:";
    private final SecretKeySpec key;

    public SecretCodec(Path configurationDirectory) {
        this.key = loadOrCreateKey(configurationDirectory);
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static boolean isSensitiveKey(String keyName) {
        return eu.kalafatic.tryst.domain.ai.AiProviderRegistry.isSensitiveKey(keyName);
    }

    /** Encrypts sensitive values and passes everything else through unchanged. */
    public String encodeForStorage(String keyName, String value) {
        if (value == null || value.isBlank() || !isSensitiveKey(keyName)
                || isEncrypted(value)) {
            return value;
        }
        return encrypt(value);
    }

    /** Decrypts encrypted values and passes everything else through unchanged. */
    public String decodeFromStorage(String value) {
        if (value == null || !isEncrypted(value)) {
            return value;
        }
        return decrypt(value);
    }

    /** Encrypt-at-rest view of a string map for persistence. */
    public Map<String, String> encodeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result.put(entry.getKey(), encodeForStorage(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** Plaintext view of a string map loaded from persistence. */
    public Map<String, String> decodeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result.put(entry.getKey(), decodeFromStorage(entry.getValue()));
        }
        return result;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + encoder.encodeToString(iv) + ":"
                    + encoder.encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret: " + e.getMessage(), e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) {
            return stored;
        }
        try {
            String payload = stored.substring(PREFIX.length());
            int separator = payload.indexOf(':');
            if (separator < 0) {
                throw new IllegalArgumentException("Malformed encrypted value");
            }
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(payload.substring(0, separator));
            byte[] ciphertext = decoder.decode(payload.substring(separator + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret (wrong key or "
                    + "corrupted value): " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec loadOrCreateKey(Path configurationDirectory) {
        try {
            Files.createDirectories(configurationDirectory);
            Path keyFile = configurationDirectory.resolve(KEY_FILE_NAME);
            if (!Files.isRegularFile(keyFile)) {
                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                Files.write(keyFile, generated, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                restrictPermissions(keyFile);
            }
            byte[] keyBytes = Files.readAllBytes(keyFile);
            if (keyBytes.length < 32) {
                throw new IllegalStateException("Invalid secrets.key (expected 32 bytes, "
                        + "found " + keyBytes.length + ")");
            }
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load secrets key from "
                    + configurationDirectory + ": " + e.getMessage(), e);
        }
    }

    private static void restrictPermissions(Path keyFile) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyFile, ownerOnly);
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem: permissions are managed by the platform
        } catch (Exception e) {
            System.err.println("TRYST: could not restrict " + keyFile + " permissions: "
                    + e.getMessage());
        }
    }
}
