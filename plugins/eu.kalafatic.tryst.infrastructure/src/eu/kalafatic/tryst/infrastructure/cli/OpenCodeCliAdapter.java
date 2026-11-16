package eu.kalafatic.tryst.infrastructure.cli;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.sandbox.SandboxProvider;
import eu.kalafatic.tryst.domain.sandbox.SandboxProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.osgi.service.component.annotations.Component;

@Component(service = SandboxProvider.class, property = "name=opencode-cli")
public class OpenCodeCliAdapter implements SandboxProvider {

    @Override
    public boolean isAvailable() {
        return true;
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
        throw new UnsupportedOperationException("OpenCode uses predefined task execution, not custom commands");
    }

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
        command.add("task");
        command.add("--task-id");
        command.add(taskId);
        command.add("--workspace");
        command.add(workspaceDir.toAbsolutePath().toString());
        command.add("--sandbox");
        command.add(sandboxDir.toAbsolutePath().toString());
        command.add("--title");
        command.add(taskTitle);
        command.add("--description");
        command.add(taskDescription);

        Map<String, String> env = new HashMap<>();
        env.put("TRYST_TASK_ID", taskId);
        env.put("TRYST_WORKSPACE", workspaceDir.toAbsolutePath().toString());
        env.put("TRYST_SANDBOX", sandboxDir.toAbsolutePath().toString());

        long startTime = System.currentTimeMillis();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceDir.toFile());
        pb.environment().putAll(env);

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
                return new SandboxProvider.SandboxResult(-1, stdout.toString(), stderr.toString() + "\nExecution cancelled",
                        System.currentTimeMillis() - startTime, false, true);
            }

            if (!finished) {
                destroyProcess(process);
                return new SandboxProvider.SandboxResult(-1, stdout.toString(), stderr.toString() + "\nExecution timed out",
                        System.currentTimeMillis() - startTime, true, false);
            }

            try {
                outThread.join(2000);
                errThread.join(2000);
            } catch (InterruptedException ignored) {}

            int exitCode = process.exitValue();
            return new SandboxProvider.SandboxResult(exitCode, stdout.toString(), stderr.toString(),
                    System.currentTimeMillis() - startTime, false, false);

        } catch (Exception e) {
            TrystLog.error(OpenCodeCliAdapter.class, "OpenCode execution error: " + e.getMessage(), e);
            if (process != null) {
                destroyProcess(process);
            }
            return new SandboxProvider.SandboxResult(1, stdout.toString(), stderr.toString() + "\n" + e.getMessage(),
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
