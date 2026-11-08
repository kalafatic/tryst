package eu.kalafatic.tryst.domain.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Strategy interface for executing untrusted project code, builds, and tests
 * within an isolated sandbox environment.
 */
public interface SandboxProvider {

    record SandboxResult(
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            boolean timedOut,
            boolean cancelled
    ) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut && !cancelled;
        }
    }

    /**
     * Executes a command inside an isolated sandbox boundary.
     */
    SandboxResult execute(
            String executionId,
            Path workspaceDir,
            List<String> command,
            Map<String, String> additionalEnv,
            Duration timeout,
            BooleanSupplier cancellationSupplier,
            Consumer<String> stdoutConsumer
    );

    /**
     * Checks if the sandbox implementation is available on the current operating system.
     */
    boolean isAvailable();

    /**
     * Name of the sandbox isolation provider (e.g., "process-isolation", "bwrap-container", "docker").
     */
    String getName();
}
