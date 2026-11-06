package eu.kalafatic.tryst.core.log;

/**
 * Authoritative log severity levels for TRYST logging facility.
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4);

    private final int level;

    LogLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isEnabled(LogLevel configuredLevel) {
        if (configuredLevel == null) {
            return true;
        }
        return this.level >= configuredLevel.level;
    }

    public static LogLevel fromString(String name) {
        if (name == null || name.isBlank()) {
            return INFO;
        }
        try {
            return LogLevel.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            if ("WARNING".equalsIgnoreCase(name.trim())) {
                return WARN;
            }
            return INFO;
        }
    }
}
