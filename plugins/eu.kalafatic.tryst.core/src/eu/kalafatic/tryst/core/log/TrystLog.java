package eu.kalafatic.tryst.core.log;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.time.Instant;
import java.util.Objects;

/**
 * Standard Eclipse Platform logging helper for TRYST.
 * Provides unified, structured diagnostic logging with plugin ID, timestamp, and severity.
 */
public final class TrystLog {

    private TrystLog() {}

    public static void info(Class<?> caller, String message) {
        log(IStatus.INFO, caller, message, null);
    }

    public static void warn(Class<?> caller, String message) {
        log(IStatus.WARNING, caller, message, null);
    }

    public static void warn(Class<?> caller, String message, Throwable throwable) {
        log(IStatus.WARNING, caller, message, throwable);
    }

    public static void error(Class<?> caller, String message) {
        log(IStatus.ERROR, caller, message, null);
    }

    public static void error(Class<?> caller, String message, Throwable throwable) {
        log(IStatus.ERROR, caller, message, throwable);
    }

    private static void log(int severity, Class<?> caller, String message, Throwable throwable) {
        String pluginId = resolvePluginId(caller);
        String formatted = formatMessage(caller, message);

        // Try standard Eclipse Platform log if running in OSGi/Eclipse runtime
        try {
            ILog log = Platform.getLog(caller);
            if (log != null) {
                Status status = new Status(severity, pluginId, formatted, throwable);
                log.log(status);
                return;
            }
        } catch (Throwable ignored) {
            // Fallback to stderr/stdout when platform log service is unavailable (e.g. basic unit test)
        }

        fallbackLog(severity, pluginId, formatted, throwable);
    }

    private static String resolvePluginId(Class<?> caller) {
        if (caller != null) {
            Bundle bundle = FrameworkUtil.getBundle(caller);
            if (bundle != null) {
                return bundle.getSymbolicName();
            }
        }
        return "eu.kalafatic.tryst.core";
    }

    private static String formatMessage(Class<?> caller, String message) {
        String className = caller != null ? caller.getSimpleName() : "TRYST";
        return "[" + className + "] " + Objects.requireNonNullElse(message, "");
    }

    private static void fallbackLog(int severity, String pluginId, String formatted, Throwable throwable) {
        String level = switch (severity) {
            case IStatus.ERROR -> "ERROR";
            case IStatus.WARNING -> "WARN";
            case IStatus.INFO -> "INFO";
            default -> "DEBUG";
        };
        String line = String.format("%s [%s] (%s) %s", Instant.now(), level, pluginId, formatted);
        if (severity == IStatus.ERROR) {
            System.err.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        } else {
            System.out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }
}
