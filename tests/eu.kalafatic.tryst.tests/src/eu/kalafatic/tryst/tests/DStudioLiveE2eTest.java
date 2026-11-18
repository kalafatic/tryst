package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.model.Workspace;
import eu.kalafatic.tryst.evo.integration.service.DStudioCodingHarnessAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 live cross-product test: TRYST → TEIP 1.1 → DStudio Harness Platform.
 *
 * <p>Requires a real DStudio headless server at {@code http://localhost:48080} (or the
 * {@code dstudio.live.endpoint} system property). Skips silently when the server is not
 * running.</p>
 */
class DStudioLiveE2eTest {

    private static final String LIVE_ENDPOINT = System.getProperty(
            "dstudio.live.endpoint", "http://localhost:48080");

    @TempDir
    Path tempDir;

    @Test
    void trystDelegatesRealTaskThroughLiveDStudio() throws Exception {
        DStudioCodingHarnessAdapter adapter = new DStudioCodingHarnessAdapter();
        adapter.updateConnectionConfiguration(Map.of(
                "endpoint", LIVE_ENDPOINT,
                "harness", "opencode",
                "timeoutSeconds", "300"));
        if (!adapter.testConnection().success()) {
            System.out.println("[DStudioLiveE2e] DStudio not reachable at "
                    + LIVE_ENDPOINT + " — skipping live cross-product test");
            return;
        }
        Path workspace = tempDir.resolve("tryst-dstudio-e2e");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("README.md"), "# TRYST-DStudio E2E\n");
        runGit(workspace, "init", "-b", "main");
        runGit(workspace, "add", ".");
        runGit(workspace, "-c", "user.name=Tryst Test", "-c",
                "user.email=test@tryst.local", "commit", "-m", "initial commit");
        List<String> logs = new ArrayList<>();
        var result = adapter.executeTask(null, new Workspace("ws-e2e", workspace),
                "Create a new file named tryst-dstudio-marker.txt containing exactly "
                        + "the text: tryst-dstudio-e2e-ok",
                "Create a new file named tryst-dstudio-marker.txt containing exactly "
                        + "the text: tryst-dstudio-e2e-ok",
                logs::add, () -> false, Duration.ofMinutes(5));
        assertTrue(result.success(), "TRYST→DStudio execution failed: "
                + result.errorMessage() + "\nlogs:\n" + String.join("\n", logs));
        Path marker = workspace.resolve("tryst-dstudio-marker.txt");
        assertTrue(Files.isRegularFile(marker), "marker file not created");
        assertTrue(Files.readString(marker).contains("tryst-dstudio-e2e-ok"),
                "marker content mismatch");
        System.out.println("[DStudioLiveE2e] TRYST → DStudio → harness: PASS ("
                + result.modifiedFiles() + ")");
    }

    private void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git command failed with exit " + exit);
        }
    }
}
