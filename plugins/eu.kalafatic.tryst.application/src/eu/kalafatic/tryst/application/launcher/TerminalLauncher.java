package eu.kalafatic.tryst.application.launcher;

import eu.kalafatic.tryst.core.log.TrystLog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable terminal spawning utility (Family UX standard v1.0).
 *
 * <p>Probes the common Linux terminal emulators ({@code x-terminal-emulator},
 * {@code gnome-terminal}, {@code konsole}, {@code xterm}) on {@code PATH} and starts the
 * first available one in the requested directory. The core launch logic is deliberately
 * free of any SWT/UI coupling so it can be shared by:
 * <ul>
 *   <li>the main menu "Tools &rarr; Open Terminal" action,</li>
 *   <li>the RCP view context menus (Project Explorer, TRYST Navigator),</li>
 *   <li>the headless CLI {@code /terminal} command.</li>
 * </ul>
 */
public final class TerminalLauncher {

    /** Outcome of a terminal launch attempt (UI-agnostic). */
    public enum Status {
        /** A terminal emulator process was started successfully. */
        LAUNCHED,
        /** No supported terminal emulator was found on {@code PATH}. */
        NO_EMULATOR,
        /** A terminal emulator exists but spawning the process failed. */
        FAILED
    }

    /** Structured result of a launch attempt, safe to evaluate without any UI. */
    public record LaunchResult(Status status, String command, String detail) {
        public boolean launched() {
            return status == Status.LAUNCHED;
        }
    }

    private static final String[] EMULATORS = {
            "x-terminal-emulator", "gnome-terminal", "konsole", "xterm"
    };

    private TerminalLauncher() {
    }

    /**
     * Attempts to open a terminal emulator in the given directory without any UI coupling.
     * A {@code null} or non-existent directory falls back to the user home directory.
     *
     * @param directory target working directory (may be {@code null})
     * @return structured launch result
     */
    public static LaunchResult launch(Path directory) {
        Path dir = normalizeDirectory(directory);
        String[] command = terminalCommand(dir.toString());
        if (command == null) {
            return new LaunchResult(Status.NO_EMULATOR, null,
                    "No supported terminal emulator found on PATH (tried " + String.join(", ", EMULATORS) + ")");
        }
        try {
            new ProcessBuilder(command).start();
            return new LaunchResult(Status.LAUNCHED, command[0], dir.toString());
        } catch (IOException e) {
            TrystLog.error(TerminalLauncher.class,
                    "Failed to open terminal in " + dir + ": " + e.getMessage(), e);
            return new LaunchResult(Status.FAILED, command[0], e.getMessage());
        }
    }

    /**
     * UI-aware convenience used by RCP menu actions: launches the terminal and reports
     * failures through a dialog on the given shell.
     *
     * @return {@code true} when a terminal emulator was spawned
     */
    public static boolean launchWithUi(Shell shell, Path directory) {
        LaunchResult result = launch(directory);
        switch (result.status()) {
            case LAUNCHED -> {
                return true;
            }
            case NO_EMULATOR -> {
                if (shell != null && !shell.isDisposed()) {
                    MessageDialog.openWarning(shell, "Open Terminal",
                            "No supported terminal emulator found on this system "
                                    + "(tried " + String.join(", ", EMULATORS) + ").");
                }
                return false;
            }
            default -> {
                if (shell != null && !shell.isDisposed()) {
                    MessageDialog.openError(shell, "Open Terminal",
                            "Failed to open terminal:\n\n" + result.detail());
                }
                return false;
            }
        }
    }

    /**
     * Resolves the directory the terminal should open in: the given directory when it
     * exists, otherwise the user home directory.
     */
    private static Path normalizeDirectory(Path directory) {
        if (directory != null) {
            try {
                if (Files.isDirectory(directory)) {
                    return directory.toAbsolutePath().normalize();
                }
            } catch (Exception ignored) {
                // fall through to user.home
            }
        }
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    private static String[] terminalCommand(String directory) {
        if (hasCommand("x-terminal-emulator")) {
            return new String[]{"x-terminal-emulator", "--working-directory=" + directory};
        }
        if (hasCommand("gnome-terminal")) {
            return new String[]{"gnome-terminal", "--working-directory=" + directory};
        }
        if (hasCommand("konsole")) {
            return new String[]{"konsole", "--workdir", directory};
        }
        if (hasCommand("xterm")) {
            return new String[]{"xterm", "-e", "cd " + shellQuote(directory) + " && $SHELL"};
        }
        return null;
    }

    private static boolean hasCommand(String command) {
        Objects.requireNonNull(command);
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            try {
                if (Files.isExecutable(Path.of(dir, command))) {
                    return true;
                }
            } catch (Exception ignored) {
                // Invalid PATH entry; keep probing.
            }
        }
        return false;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Emulator existence probe used by the headless CLI to print a guarded status line
     * before attempting a launch. Exposed for the {@code /terminal} command.
     */
    public static String probeEmulatorNames() {
        return java.util.Arrays.stream(EMULATORS)
                .filter(TerminalLauncher::hasCommand)
                .findFirst()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .orElse(null);
    }
}
