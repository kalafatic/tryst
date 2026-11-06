package eu.kalafatic.tryst.core.port;

import eu.kalafatic.tryst.core.log.TrystLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Port availability checker using actual socket binding as the final authority,
 * avoiding check-then-bind races where possible, and inspecting occupying processes
 * via Java 21 ProcessHandle and platform diagnostics (lsof, ss, netstat).
 */
public class PortAvailabilityChecker {

    /**
     * Checks if a port is currently available to bind on the given address.
     */
    public boolean isPortAvailable(String host, int port) {
        if (port < 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(host != null && !host.isBlank() ? host : "0.0.0.0", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Diagnoses and identifies the process occupying the specified port if possible.
     */
    public Optional<ProcessPortInfo> identifyConflictingProcess(int port) {
        // 1. Try lsof: lsof -i :<port> -P -n -F pcn
        Optional<ProcessPortInfo> fromLsof = queryViaLsof(port);
        if (fromLsof.isPresent()) {
            return fromLsof;
        }

        // 2. Try ss: ss -tulpn
        Optional<ProcessPortInfo> fromSs = queryViaSs(port);
        if (fromSs.isPresent()) {
            return fromSs;
        }

        return Optional.of(ProcessPortInfo.unidentifiable());
    }

    private Optional<ProcessPortInfo> queryViaLsof(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("lsof", "-i", ":" + port, "-P", "-n", "-F", "pcn");
            Process proc = pb.start();
            boolean done = proc.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                return Optional.empty();
            }
            if (proc.exitValue() != 0) {
                return Optional.empty();
            }

            long pid = -1;
            String cmd = "";
            String user = "";

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("p")) {
                        try {
                            pid = Long.parseLong(line.substring(1).trim());
                        } catch (NumberFormatException ignored) {}
                    } else if (line.startsWith("c")) {
                        cmd = line.substring(1).trim();
                    } else if (line.startsWith("u")) {
                        user = line.substring(1).trim();
                    }
                }
            }

            if (pid > 0) {
                return Optional.of(enrichWithProcessHandle(pid, cmd, user));
            }
        } catch (Throwable t) {
            TrystLog.warn(PortAvailabilityChecker.class, "lsof query failed for port " + port + ": " + t.getMessage());
        }
        return Optional.empty();
    }

    private Optional<ProcessPortInfo> queryViaSs(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ss", "-tulpn", "sport = :" + port);
            Process proc = pb.start();
            boolean done = proc.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                return Optional.empty();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Pattern: users:(("process_name",pid=1234,fd=4))
                    int usersIdx = line.indexOf("users:((");
                    if (usersIdx != -1) {
                        String fragment = line.substring(usersIdx + 8);
                        int end = fragment.indexOf("))");
                        if (end != -1) fragment = fragment.substring(0, end);
                        // fragment e.g.: "java",pid=1234,fd=4
                        String name = "";
                        long pid = -1;
                        for (String part : fragment.split(",")) {
                            part = part.trim();
                            if (part.startsWith("\"") && part.endsWith("\"")) {
                                name = part.substring(1, part.length() - 1);
                            } else if (part.startsWith("pid=")) {
                                try {
                                    pid = Long.parseLong(part.substring(4));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        if (pid > 0) {
                            return Optional.of(enrichWithProcessHandle(pid, name, ""));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            TrystLog.warn(PortAvailabilityChecker.class, "ss query failed for port " + port + ": " + t.getMessage());
        }
        return Optional.empty();
    }

    private ProcessPortInfo enrichWithProcessHandle(long pid, String defaultName, String defaultUser) {
        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        if (phOpt.isPresent()) {
            ProcessHandle ph = phOpt.get();
            ProcessHandle.Info info = ph.info();
            String name = info.command()
                    .map(c -> {
                        int lastSlash = c.lastIndexOf('/');
                        return lastSlash != -1 ? c.substring(lastSlash + 1) : c;
                    })
                    .orElse(defaultName.isBlank() ? "PID " + pid : defaultName);
            String commandLine = info.commandLine().orElse(info.command().orElse(defaultName));
            String user = info.user().orElse(defaultUser);
            boolean canTerminate = ph.isAlive() && pid != ProcessHandle.current().pid();
            return new ProcessPortInfo(pid, name, commandLine, user, canTerminate);
        }
        return new ProcessPortInfo(pid, defaultName.isBlank() ? "PID " + pid : defaultName, "", defaultUser, false);
    }
}
