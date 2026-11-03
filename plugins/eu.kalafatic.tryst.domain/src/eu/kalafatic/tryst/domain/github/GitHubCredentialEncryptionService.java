package eu.kalafatic.tryst.domain.github;

/**
 * Service interface for encrypting and decrypting sensitive credentials at rest,
 * and securely masking tokens in logs, console views, and diagnostic dumps.
 */
public interface GitHubCredentialEncryptionService {

    /**
     * Encrypts a sensitive credential using server-side AES-256-GCM.
     */
    String encrypt(String plaintextToken);

    /**
     * Decrypts an encrypted credential ciphertext.
     */
    String decrypt(String ciphertext);

    /**
     * Securely masks an access token for display or logs (e.g. "ghp_****a1b2").
     */
    String maskToken(String token);
}
