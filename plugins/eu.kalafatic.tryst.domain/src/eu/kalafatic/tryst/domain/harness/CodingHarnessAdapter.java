package eu.kalafatic.tryst.domain.harness;

import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.Workspace;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Coding-harness adapter SPI for TRYST connected applications (Phase 3, A-14/A-15).
 *
 * <p>A coding harness is an external coding executor (e.g. OpenCode CLI, the EVO engine via
 * TEIP) that executes delegated coding tasks inside the TRYST-controlled workspace/sandbox.
 * TRYST remains authoritative for task state and lifecycle, build/test validation, review,
 * approval and commit. The local CodeAgent/CodeChangeExecutor remains the built-in fallback
 * and is not modelled as a connected application.</p>
 *
 * <p>Implementations must preserve repository isolation, support process lifecycle,
 * cancellation and timeouts, capture output and progress, identify changed files and return
 * control to the existing TRYST workflow.</p>
 */
public interface CodingHarnessAdapter {

    record ConnectionTestResult(boolean success, String message) {
        public static ConnectionTestResult ok(String message) {
            return new ConnectionTestResult(true, message);
        }

        public static ConnectionTestResult error(String message) {
            return new ConnectionTestResult(false, message != null ? message : "Connection test failed");
        }
    }

    record HarnessExecutionResult(
            boolean handled,
            boolean success,
            String summary,
            String errorMessage,
            boolean timedOut,
            boolean cancelled,
            List<String> modifiedFiles
    ) {
        public static HarnessExecutionResult notHandled() {
            return new HarnessExecutionResult(false, false, "", "", false, false, List.of());
        }

        public static HarnessExecutionResult ok(String summary, List<String> modifiedFiles) {
            return new HarnessExecutionResult(true, true, summary != null ? summary : "", "",
                    false, false, modifiedFiles != null ? List.copyOf(modifiedFiles) : List.of());
        }

        public static HarnessExecutionResult error(String error) {
            return new HarnessExecutionResult(true, false, "",
                    error != null ? error : "Coding harness execution failed", false, false, List.of());
        }

        public static HarnessExecutionResult timedOut(String error) {
            return new HarnessExecutionResult(true, false, "",
                    error != null ? error : "Coding harness execution timed out", true, false, List.of());
        }

        public static HarnessExecutionResult cancelled(String error) {
            return new HarnessExecutionResult(true, false, "",
                    error != null ? error : "Coding harness execution cancelled", false, true, List.of());
        }
    }

    /**
     * Stable application identity of this harness.
     */
    String getApplicationId();

    /**
     * Human-readable application name.
     */
    String getApplicationName();

    /**
     * Application type (e.g. "EVO", "OPENCODE", "LOCAL").
     */
    String getApplicationType();

    /**
     * Capabilities provided by this harness (e.g. "CODE_IMPLEMENTATION").
     */
    List<String> getCapabilities();

    /**
     * Whether the harness can be selected for execution right now.
     */
    boolean isAvailable();

    /**
     * Live connection test against the configured endpoint/binary.
     */
    ConnectionTestResult testConnection();

    /**
     * Updates the connection configuration of this harness. Implementations validate the
     * configuration they accept; unknown keys are ignored.
     */
    default void updateConnectionConfiguration(Map<String, String> configuration) {}

    /**
     * Returns the current connection configuration (never secrets; tokens are masked).
     */
    default Map<String, String> getConnectionConfiguration() {
        return Map.of();
    }

    /**
     * Executes a delegated coding task inside the TRYST-controlled workspace. Must be
     * cancellation- and timeout-safe and must never write outside the workspace boundary.
     */
    HarnessExecutionResult executeTask(
            Task task,
            Workspace workspace,
            String instructions,
            String prompt,
            Consumer<String> logConsumer,
            BooleanSupplier cancellationSupplier,
            Duration timeout
    );
}
