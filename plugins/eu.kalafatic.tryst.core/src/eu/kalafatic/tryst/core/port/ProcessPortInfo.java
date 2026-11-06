package eu.kalafatic.tryst.core.port;

/**
 * Diagnostic information regarding a process that is currently occupying a port.
 */
public record ProcessPortInfo(
        long pid,
        String name,
        String commandLine,
        String user,
        boolean canTerminate
) {
    public static ProcessPortInfo unknown(long pid) {
        return new ProcessPortInfo(pid, "unknown", "", "", false);
    }

    public static ProcessPortInfo unidentifiable() {
        return new ProcessPortInfo(-1, "unidentifiable", "Unknown process occupying port", "", false);
    }
}
