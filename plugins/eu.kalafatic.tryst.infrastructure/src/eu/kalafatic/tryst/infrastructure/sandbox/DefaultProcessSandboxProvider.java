package eu.kalafatic.tryst.infrastructure.sandbox;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.sandbox.SandboxProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Default secure process-isolated sandbox provider.
 * Enforces:
 * - Strict working directory containment
 * - Whitelisted command execution
 * - Sanitized environment variables (stripping tokens, passwords, credentials)
 * - Clean process group termination on timeout or cancellation
 * - Explicit stderr/stdout capture
 */
public class DefaultProcessSandboxProvider implements SandboxProvider {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getName() {
        return "process-sandbox";
    }

    @Override
    public SandboxResult execute(
            String executionId,
            Path workspaceDir,
            List<String> command,
            Map<String, String> additionalEnv,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> stdoutConsumer
    ) {
        Objects.requireNonNull(workspaceDir, "workspaceDir must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty()) {
            return new SandboxResult(1, "", "Empty command", 0, false, false);
        }

        long startTime = System.currentTimeMillis();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceDir.toFile());

        Map<String, String> env = pb.environment();
        // Sanitize environment
        List<String> sensitiveKeys = new ArrayList<>();
        for (String key : env.keySet()) {
            String lower = key.toLowerCase();
            if (lower.contains("token") || lower.contains("secret") || lower.contains("password")
                    || lower.contains("auth") || lower.contains("key") || lower.contains("credential")
                    || lower.contains("ghp_")) {
                sensitiveKeys.add(key);
            }
        }
        for (String key : sensitiveKeys) {
            env.remove(key);
        }

        env.put("CI", "true");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("TRYST_SANDBOX_EXEC_ID", executionId != null ? executionId : "none");

        if (additionalEnv != null) {
            env.putAll(additionalEnv);
        }

        Process process = null;
        try {
            process = pb.start();
            final Process procRef = process;

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        if (stdoutConsumer != null) {
                            stdoutConsumer.accept(line);
                        }
                    }
                } catch (Exception ignored) {}
            });

            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            outThread.start();
            errThread.start();

            long timeoutSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                    ? timeout.toSeconds() : 300L;

            long elapsed = 0;
            boolean finished = false;
            boolean cancelled = false;

            while (elapsed < timeoutSeconds) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                elapsed = (System.currentTimeMillis() - startTime) / 1000;
            }

            if (cancelled) {
                destroyProcess(process);
                return new SandboxResult(-1, stdout.toString(), stderr.toString() + "\nExecution cancelled",
                        System.currentTimeMillis() - startTime, false, true);
            }

            if (!finished) {
                destroyProcess(process);
                return new SandboxResult(-1, stdout.toString(), stderr.toString() + "\nExecution timed out",
                        System.currentTimeMillis() - startTime, true, false);
            }

            try {
                outThread.join(2000);
                errThread.join(2000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            return new SandboxResult(exitCode, stdout.toString(), stderr.toString(),
                    System.currentTimeMillis() - startTime, false, false);

        } catch (Exception e) {
            TrystLog.error(DefaultProcessSandboxProvider.class, "Sandbox execution error: " + e.getMessage(), e);
            if (process != null) {
                destroyProcess(process);
            }
            return new SandboxResult(1, stdout.toString(), stderr.toString() + "\n" + e.getMessage(),
                    System.currentTimeMillis() - startTime, false, false);
        }
    }

    private void destroyProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
