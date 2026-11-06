package eu.kalafatic.tryst.core.log;

import java.util.regex.Pattern;

/**
 * Utility for sanitizing sensitive credentials, tokens, and secrets from log messages.
 * Prevents accidental credential leakage into persistent files, buffers, and consoles.
 */
public final class LogSanitizer {

    private static final Pattern PRIVATE_KEY_PATTERN =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private static final Pattern GITHUB_TOKEN_PATTERN =
            Pattern.compile("(gh[pousr]_[A-Za-z0-9_]{16,255})");

    private static final Pattern BEARER_HEADER_PATTERN =
            Pattern.compile("(?i)(Authorization\\s*:\\s*bearer|bearer)\\s+([a-zA-Z0-9_\\-\\.~+/]+=*)");

    private static final Pattern KEY_VALUE_PATTERN =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|accessToken|personalAccessToken|bearer|authorization|apiKey|privateKey|credential)\\s*[:=]\\s*['\"]?([\\w\\-\\.~!$&'()*+,;=:@%/]+)['\"]?");

    private LogSanitizer() {}

    /**
     * Redacts known sensitive patterns from the provided text.
     *
     * @param input the raw text to sanitize
     * @return redacted string with credentials replaced by [REDACTED] or masked representation
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        String result = input;

        // 1. Private keys
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_PRIVATE_KEY]");

        // 2. Bearer tokens (e.g. Authorization: Bearer abc, Bearer abc)
        result = BEARER_HEADER_PATTERN.matcher(result).replaceAll("$1 [REDACTED]");

        // 3. GitHub personal access tokens
        result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED]");

        // 4. Key-value secrets (e.g. password=secret, token: "secret")
        result = KEY_VALUE_PATTERN.matcher(result).replaceAll("$1=[REDACTED]");

        return result;
    }
}
