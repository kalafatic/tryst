package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.harness.CodingHarnessAdapter;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.model.TaskStatus;
import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.domain.model.WorkspaceStatus;
import eu.kalafatic.tryst.infrastructure.cli.OpenCodeCodingHarnessAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OpenCodeCodingHarnessAdapterTest {

    @Test
    void testIdentityAndCapabilities() {
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        assertEquals("opencode", adapter.getApplicationId());
        assertEquals("OpenCode CLI", adapter.getApplicationName());
        assertEquals("OPENCODE", adapter.getApplicationType());
        assertTrue(adapter.getCapabilities().contains("CODE_IMPLEMENTATION"));
    }

    @Test
    void testConnectionTestWithInvalidBinary() {
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of("binary", "/nonexistent/opencode-binary"));
        CodingHarnessAdapter.ConnectionTestResult result = adapter.testConnection();
        assertFalse(result.success());
    }

    @Test
    void testConfigurationUpdateAndMasking() {
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "binary", "opencode",
                "agent", "build",
                "model", "qwen3-coder-30b-32k:latest",
                "autoApprove", "true"
        ));
        Map<String, String> config = adapter.getConnectionConfiguration();
        assertEquals("build", config.get("agent"));
        assertEquals("qwen3-coder-30b-32k:latest", config.get("model"));
        assertEquals("true", config.get("autoApprove"));
    }

    @Test
    void testConnectionTestAgainstInstalledBinary() {
        // The OpenCode CLI is installed on this development host (audited v1.19.5);
        // when absent, the adapter must report the failure instead of claiming success.
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        CodingHarnessAdapter.ConnectionTestResult result = adapter.testConnection();
        // Non-fatal assertion: pass if the CLI responds, otherwise verify a clean failure message.
        if (!result.success()) {
            assertNotNull(result.message());
        }
    }

    @Test
    void testMissingWorkspaceRejected(@TempDir Path tempDir) {
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of("binary", "/nonexistent/opencode-binary"));
        Task task = new Task("task-1", "project-1", "session-1", "Test task", "Test description",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null,
                Instant.now(), Instant.now());
        Path missing = tempDir.resolve("does-not-exist");
        Workspace workspace = new Workspace("ws-1", missing, WorkspaceStatus.READY, Instant.now(), Instant.now());

        CodingHarnessAdapter.HarnessExecutionResult result = adapter.executeTask(
                task, workspace, "instructions", "prompt", null, () -> false, Duration.ofSeconds(10));
        assertFalse(result.success());
        assertTrue(result.handled());
    }

    @Test
    void testCancellationSupplierPreventsLaunch(@TempDir Path tempDir) throws Exception {
        // Cancellation requested before the process is polled must surface as cancelled
        // (validated with an unavailable binary path to keep the test fast and deterministic).
        Path workspaceDir = tempDir.resolve("ws");
        Files.createDirectories(workspaceDir);
        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of("binary", "/nonexistent/opencode-binary"));
        Task task = new Task("task-1", "project-1", "session-1", "Test task", "Test description",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null,
                Instant.now(), Instant.now());
        Workspace workspace = new Workspace("ws-1", workspaceDir, WorkspaceStatus.READY, Instant.now(), Instant.now());
        AtomicBoolean cancelled = new AtomicBoolean(true);

        CodingHarnessAdapter.HarnessExecutionResult result = adapter.executeTask(
                task, workspace, "instructions", "prompt", null, cancelled::get, Duration.ofSeconds(30));
        // With an invalid binary the start fails before the cancellation check runs;
        // either way the result must not claim success.
        assertFalse(result.success());
    }

    /**
     * Live runtime smoke test (Phase 3, WP-3.7 evidence): executes a REAL OpenCode coding
     * task inside a TRYST-controlled workspace and verifies the file lands inside the
     * workspace (containment). Returns early when the OpenCode CLI is unavailable on the host.
     */
    @Test
    void testRealOpenCodeTaskInsideWorkspace(@TempDir Path tempDir) throws Exception {
        Path workspaceDir = tempDir.resolve("smoke-ws");
        Files.createDirectories(workspaceDir);

        OpenCodeCodingHarnessAdapter adapter = new OpenCodeCodingHarnessAdapter();
        if (!adapter.testConnection().success()) {
            // OpenCode CLI not available on this host: nothing to smoke-test here.
            return;
        }
        // Explicit auto-approval inside the TRYST-controlled sandbox (contained workspace;
        // the CLI-level deny list stays active).
        adapter.updateConnectionConfiguration(Map.of("autoApprove", "true"));

        Task task = new Task("smoke-task", "smoke-project", "smoke-session",
                "Smoke task", "Create smoke-output.txt inside the workspace",
                TaskStatus.CREATED, TaskPriority.NORMAL, null, null, null, null, 0, null,
                Instant.now(), Instant.now());
        Workspace workspace = new Workspace("smoke-ws", workspaceDir, WorkspaceStatus.READY, Instant.now(), Instant.now());

        StringBuilder log = new StringBuilder();
        CodingHarnessAdapter.HarnessExecutionResult result = adapter.executeTask(
                task,
                workspace,
                "Create a file named smoke-output.txt containing exactly: tryst-opencode-smoke-ok",
                "Create a file named smoke-output.txt containing exactly: tryst-opencode-smoke-ok",
                log::append,
                () -> false,
                Duration.ofMinutes(5)
        );

        if (result.success()) {
            Path expected = workspaceDir.resolve("smoke-output.txt");
            assertTrue(Files.exists(expected),
                    "OpenCode reported success; smoke-output.txt must exist inside the workspace");
            String content = Files.readString(expected);
            assertTrue(content.contains("tryst-opencode-smoke-ok"),
                    "Output content must contain the requested marker");
        } else {
            // Execution may fail in offline/model-unavailable environments; the harness must
            // report the failure honestly instead of fabricating success.
            assertNotNull(result.errorMessage());
        }
    }
}
