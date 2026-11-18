package eu.kalafatic.tryst.infrastructure.cli;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.sandbox.SandboxProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OpenCode CLI implementation of the SandboxProvider SPI. Executes commands inside the given
 * workspace directory with bounded timeout and cancellation; the OpenCode-specific command
 * line is built by {@link #executeTask(String, Path, Path, String, String, Duration,
 * BooleanSupplier, Consumer)} (non-interactive `opencode run`).
 *
 * <p>Registration happens programmatically through the application lifecycle (no OSGi DS
 * runtime for TRYST infrastructure components).</p>
 */
public class OpenCodeCliAdapter implements SandboxProvider {

    @Override
    public boolean isAvailable() {
        try {
            Process process = new ProcessBuilder(List.of("opencode", "--version")).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "opencode-cli";
    }

    @Override
    public SandboxProvider.SandboxResult execute(
            String executionId,
            Path workspaceDir,
            List<String> command,
            Map<String, String> additionalEnv,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> stdoutConsumer
    ) {
        if (command == null || command.isEmpty()) {
            return new SandboxResult(1, "", "No command provided for OpenCode sandbox execution", 0, false, false);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workspaceDir != null && java.nio.file.Files.isDirectory(workspaceDir)) {
            pb.directory(workspaceDir.toFile());
        }
        if (additionalEnv != null) {
            pb.environment().putAll(additionalEnv);
        }
        if (executionId != null) {
            pb.environment().put("TRYST_TASK_ID", executionId);
        }

        long startTime = System.currentTimeMillis();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Process process = null;
        try {
            process = pb.start();
            final Process procRef = process;

            Thread outThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                        if (stdoutConsumer != null) {
                            stdoutConsumer.accept(line);
                        }
                    }
                } catch (Exception ignored) {}
            }, "tryst-opencode-sp-out");
            Thread errThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(procRef.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            }, "tryst-opencode-sp-err");
            outThread.start();
            errThread.start();

            long timeoutSeconds = (timeout != null && !timeout.isZero() && !timeout.isNegative())
                    ? timeout.toSeconds() : 900L;

            boolean finished = false;
            boolean cancelled = false;
            while (!finished && (System.currentTimeMillis() - startTime) / 1000 < timeoutSeconds) {
                if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }

            if (cancelled) {
                destroyProcess(process);
                return new SandboxResult(-1, stdout.toString(), stderr + "\nExecution cancelled",
                        System.currentTimeMillis() - startTime, false, true);
            }
            if (!finished) {
                destroyProcess(process);
                return new SandboxResult(-1, stdout.toString(), stderr + "\nExecution timed out",
                        System.currentTimeMillis() - startTime, true, false);
            }

            outThread.join(2000);
            errThread.join(2000);

            int exitCode = process.exitValue();
            return new SandboxResult(exitCode, stdout.toString(), stderr.toString(),
                    System.currentTimeMillis() - startTime, false, false);
        } catch (Exception e) {
            TrystLog.error(OpenCodeCliAdapter.class, "OpenCode sandbox execution error: " + e.getMessage(), e);
            if (process != null) {
                destroyProcess(process);
            }
            return new SandboxResult(1, stdout.toString(), stderr + "\n" + e.getMessage(),
                    System.currentTimeMillis() - startTime, false, false);
        }
    }

    /**
     * Convenience entry point for task-shaped OpenCode executions: builds the non-interactive
     * `opencode run <title> <description>` command and delegates to {@link #execute}.
     */
    public SandboxProvider.SandboxResult executeTask(
            String taskId,
            Path workspaceDir,
            Path sandboxDir,
            String taskTitle,
            String taskDescription,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> stdoutConsumer
    ) {
        List<String> command = new ArrayList<>();
        command.add("opencode");
        command.add("run");
        if (taskTitle != null && !taskTitle.isBlank()) {
            command.add(taskTitle);
        }
        if (taskDescription != null && !taskDescription.isBlank()) {
            command.add(taskDescription);
        }
        command.add("--print-logs");
        Map<String, String> env = new java.util.HashMap<>();
        if (sandboxDir != null) {
            env.put("TRYST_SANDBOX", sandboxDir.toAbsolutePath().toString());
        }
        if (workspaceDir != null) {
            env.put("TRYST_WORKSPACE", workspaceDir.toAbsolutePath().toString());
        }
        return execute(taskId, workspaceDir, command, env, timeout, cancellationSupplier, stdoutConsumer);
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
