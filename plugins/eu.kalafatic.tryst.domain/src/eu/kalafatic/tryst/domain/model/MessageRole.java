package eu.kalafatic.tryst.domain.model;

/**
 * Role of the participant who authored a conversation message.
 */
public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL;

    public static MessageRole fromString(String role) {
        if (role == null) {
            throw new IllegalArgumentException("Role string must not be null");
        }
        return MessageRole.valueOf(role.trim().toUpperCase());
    }
}
